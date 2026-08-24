package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Who is in the network, and which of them are alive.
 *
 * <p>Each node upserts its own row on a heartbeat; a node whose {@code heartbeat_at} falls outside
 * the timeout is treated as DOWN by whoever notices. There is no central coordinator: the check is a
 * conditional UPDATE, so two nodes racing to declare a third dead both simply succeed.</p>
 *
 * <p><b>{@code epoch} is the fencing token.</b> It is set once per boot from the wall clock, so a
 * node that crashed and came back is distinguishable from the one that died — which is what stops it
 * adopting rows (world placements, matches) written after its death by whoever cleaned up.</p>
 */
public final class NodeRegistry {

  /**
   * A peer as recorded in the registry.
   *
   * <p>The trailing block — protocol through heap — is what a rolling update reads: which build a
   * node came back on, what content it can run, and whether it is well enough to be handed a world.
   * Every one of those fields has an explicit "unknown" value ({@code 0} for protocol and capacity,
   * {@code -1} for tick health, null for the strings), because a node running the previous build
   * writes nothing there and a reader must treat that as <em>unknown</em>, never as <em>bad</em>.</p>
   */
  public record Node(
      String nodeId,
      String displayName,
      String role,
      String capabilities,
      String address,
      int port,
      String state,
      int players,
      int worlds,
      long epoch,
      long heartbeatAt,
      long protocol,
      String pluginVersion,
      String buildSha,
      String contentDigest,
      String contentCodes,
      int maxPlayers,
      int maxWorlds,
      int tps,
      int mspt,
      int heapUsedMb,
      int heapMaxMb) {

    /** The pre-telemetry shape, so every existing caller and test keeps compiling untouched. */
    public Node(String nodeId, String displayName, String role, String capabilities, String address,
        int port, String state, int players, int worlds, long epoch, long heartbeatAt) {
      this(nodeId, displayName, role, capabilities, address, port, state, players, worlds, epoch,
          heartbeatAt, Protocol.UNKNOWN, null, null, null, null, 0, 0,
          com.sexidium.core.platform.NodeHealthPort.UNKNOWN,
          com.sexidium.core.platform.NodeHealthPort.UNKNOWN, 0, 0);
    }

    /** The pre-address shape, for the many callers that only care about liveness. */
    public Node(String nodeId, String displayName, String role, String capabilities, String state,
        int players, int worlds, long epoch, long heartbeatAt) {
      this(nodeId, displayName, role, capabilities, null, 0, state, players, worlds, epoch, heartbeatAt);
    }

    public boolean alive(long now, long timeoutMillis) {
      return !"DOWN".equals(state) && now - heartbeatAt <= timeoutMillis;
    }

    /** Whether this node has published an address a proxy could actually connect to. */
    public boolean addressable() {
      return address != null && !address.isBlank() && port > 0;
    }

    /** Whether this node is draining, i.e. still routable but taking nothing new. */
    public boolean draining() {
      return STATE_DRAINING.equals(state);
    }

    /** The content codes this node published, decoded. Empty when it published none. */
    public java.util.Set<String> codes() {
      return ContentManifest.decode(contentCodes);
    }
  }

  /**
   * What a node publishes about itself beyond "I am alive": which build it is, what content it can
   * run, and how much room it has left.
   *
   * <p>Resolved once at wiring time rather than read per heartbeat, because none of it changes while
   * the process lives. {@link #unknown()} is what a node publishes before anything is wired, and it
   * is exactly what a node on the previous build publishes — so the two are indistinguishable to a
   * reader, which is the point.</p>
   */
  public record Telemetry(
      String pluginVersion,
      String buildSha,
      String contentDigest,
      String contentCodes,
      int maxPlayers,
      int maxWorlds) {

    public static Telemetry unknown() {
      return new Telemetry(null, null, null, null, 0, 0);
    }
  }

  public static final String STATE_UP = "UP";
  public static final String STATE_DRAINING = "DRAINING";
  public static final String STATE_DOWN = "DOWN";

  private final Database database;
  private final LoggerAdapter logger;
  private final NodeIdentity identity;
  private final long timeoutMillis;
  private final long epoch;
  /**
   * Where peers can reach this node, published on every heartbeat.
   *
   * <p>The columns existed and were NEVER written, so the proxy's backend list had to be rendered
   * into velocity.toml at provision time — which is why adding a worker meant editing the stack,
   * re-running the provisioner and restarting the proxy, while the capability side was already
   * dynamic and would happily plan worlds onto a node the proxy could not reach.</p>
   */
  private volatile String address;
  private volatile int port;

  /**
   * Build, content and capacity, published on every heartbeat. See {@link Telemetry}.
   *
   * <p>The columns {@code protocol}, {@code plugin_version}, {@code max_players} and
   * {@code max_worlds} existed in the schema and were never written by anybody, so "is that node on
   * the build I staged?" — the one question a rolling update has to answer before it moves on — was
   * unanswerable from outside the container.</p>
   */
  private volatile Telemetry telemetry = Telemetry.unknown();
  /** Tick health, re-read every heartbeat because unlike {@link #telemetry} it genuinely moves. */
  private volatile com.sexidium.core.platform.NodeHealthPort health =
      com.sexidium.core.platform.NodeHealthPort.UNAVAILABLE;

  /** Tell the registry where this node listens, so peers can discover it. */
  public void publishAddress(String address, int port) {
    this.address = address;
    this.port = port;
  }

  /** Tell the registry which build this is and what it can run. Null resets to "unknown". */
  public void publishTelemetry(Telemetry telemetry) {
    this.telemetry = telemetry == null ? Telemetry.unknown() : telemetry;
  }

  /** Where the live tick/heap numbers come from. Null resets to the heap-only default. */
  public void publishHealth(com.sexidium.core.platform.NodeHealthPort health) {
    this.health = health == null ? com.sexidium.core.platform.NodeHealthPort.UNAVAILABLE : health;
  }

  /** What this node publishes about itself right now. */
  public Telemetry telemetry() {
    return telemetry;
  }

  public NodeRegistry(Database database, LoggerAdapter logger, NodeIdentity identity, long timeoutMillis) {
    this(database, logger, identity, timeoutMillis, ProtocolTag.current());
  }

  /**
   * The overload that lets a test stand two builds up against one database.
   *
   * <p>{@link Protocol#VERSION} is never read at a call site — everything reads an injected
   * {@link ProtocolTag} — so a mixed-version test is two tags in one JVM and no classloaders.</p>
   */
  public NodeRegistry(Database database, LoggerAdapter logger, NodeIdentity identity,
      long timeoutMillis, ProtocolTag protocolTag) {
    this.database = database;
    this.logger = logger;
    this.identity = identity;
    this.timeoutMillis = timeoutMillis;
    this.protocolTag = protocolTag == null ? ProtocolTag.current() : protocolTag;
    this.epoch = System.currentTimeMillis();
  }

  private final ProtocolTag protocolTag;

  /** This build's tag, as injected. */
  public ProtocolTag protocolTag() {
    return protocolTag;
  }

  public long epoch() {
    return epoch;
  }

  /** Record this node as UP with a fresh heartbeat. Called on a timer. */
  public void heartbeat(int players, int worlds) {
    heartbeat(STATE_UP, players, worlds);
  }

  /**
   * As {@link #heartbeat(int, int)} but publishing the state the caller knows to be true.
   *
   * <p>One write, and that is the point. The heartbeat used to publish UP unconditionally and the
   * drain coordinator overwrote it with DRAINING immediately afterwards, in a second transaction —
   * so a peer whose {@code registry.all()} landed between the two saw a draining node as UP and could
   * pick it in {@code choose}, in {@code LobbySelector} or as a lobby target. A short window, and a
   * world or a player planted on a node that is leaving.</p>
   */
  public void heartbeat(String state, int players, int worlds) {
    upsert(state == null ? STATE_UP : state, players, worlds);
  }

  /**
   * Mark this node DRAINING: still serving the players it has, accepting no new placements.
   * Called on shutdown so peers stop routing here before the process actually goes away.
   */
  public void draining() {
    upsert(STATE_DRAINING, 0, 0);
  }

  /**
   * The column list, written in one place so the UPDATE and the INSERT cannot drift apart.
   *
   * <p>{@code ProtocolContractTest} extracts the names out of these two constants by regex — brittle
   * on purpose. Changing what this node publishes is a wire change, and the test is how the developer
   * making it is put in front of {@link Protocol}'s bump rules.</p>
   */
  static final String UPSERT_UPDATE_SQL =
      "UPDATE network_nodes SET display_name = ?, role = ?, capabilities = ?, address = ?,"
          + " port = ?, state = ?, players = ?, worlds = ?, epoch = ?, heartbeat_at = ?,"
          + " protocol = ?, plugin_version = ?, build_sha = ?, content_digest = ?,"
          + " content_codes = ?, max_players = ?, max_worlds = ?, tps = ?, mspt = ?,"
          + " heap_used_mb = ?, heap_max_mb = ?"
          + " WHERE node_id = ?";

  static final String UPSERT_INSERT_SQL =
      "INSERT INTO network_nodes (node_id, display_name, role, capabilities, address, port,"
          + " state, players, worlds, epoch, started_at, heartbeat_at,"
          + " protocol, plugin_version, build_sha, content_digest, content_codes,"
          + " max_players, max_worlds, tps, mspt, heap_used_mb, heap_max_mb)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  static final String SELECT_ALL_SQL =
      "SELECT node_id, display_name, role, capabilities, address, port, state, players,"
          + " worlds, epoch, heartbeat_at, protocol, plugin_version, build_sha, content_digest,"
          + " content_codes, max_players, max_worlds, tps, mspt, heap_used_mb, heap_max_mb"
          + " FROM network_nodes ORDER BY node_id";

  private void upsert(String state, int players, int worlds) {
    String capabilities = identity.capabilities().stream()
        .map(capability -> capability.name().toLowerCase(Locale.ROOT))
        .collect(Collectors.joining(","));
    long now = System.currentTimeMillis();
    Telemetry published = telemetry;
    com.sexidium.core.platform.NodeHealthPort currentHealth = health;
    // Read once per heartbeat and defensively: a platform health probe that throws must cost the
    // reading, not the heartbeat -- a node that stops checking in gets reaped and loses its worlds.
    int tps = readHealth(currentHealth::tpsTimes100);
    int mspt = readHealth(currentHealth::msptTimes100);
    int heapUsed = readHealth(currentHealth::heapUsedMb);
    int heapMax = readHealth(currentHealth::heapMaxMb);
    int maxPlayers = published.maxPlayers() > 0
        ? published.maxPlayers()
        : Math.max(0, readHealth(currentHealth::maxPlayers));

    synchronized (database.lock()) {
      try {
        // UPDATE-then-INSERT rather than a dialect-specific upsert: the row is written every few
        // seconds forever and only ever inserted once, so the common path is a plain UPDATE.
        try (PreparedStatement update = database.connection().prepareStatement(UPSERT_UPDATE_SQL)) {
          update.setString(1, identity.displayName());
          update.setString(2, roleOf(identity));
          update.setString(3, capabilities);
          update.setString(4, address);
          update.setInt(5, port);
          update.setString(6, state);
          update.setInt(7, players);
          update.setInt(8, worlds);
          update.setLong(9, epoch);
          update.setLong(10, now);
          update.setLong(11, protocolTag.version());
          update.setString(12, published.pluginVersion());
          update.setString(13, published.buildSha());
          update.setString(14, published.contentDigest());
          update.setString(15, published.contentCodes());
          update.setInt(16, maxPlayers);
          update.setInt(17, published.maxWorlds());
          update.setInt(18, tps);
          update.setInt(19, mspt);
          update.setInt(20, heapUsed);
          update.setInt(21, heapMax);
          update.setString(22, identity.nodeId());
          if (update.executeUpdate() > 0) {
            return;
          }
        }
        try (PreparedStatement insert = database.connection().prepareStatement(UPSERT_INSERT_SQL)) {
          insert.setString(1, identity.nodeId());
          insert.setString(2, identity.displayName());
          insert.setString(3, roleOf(identity));
          insert.setString(4, capabilities);
          insert.setString(5, address);
          insert.setInt(6, port);
          insert.setString(7, state);
          insert.setInt(8, players);
          insert.setInt(9, worlds);
          insert.setLong(10, epoch);
          insert.setLong(11, now);
          insert.setLong(12, now);
          insert.setLong(13, protocolTag.version());
          insert.setString(14, published.pluginVersion());
          insert.setString(15, published.buildSha());
          insert.setString(16, published.contentDigest());
          insert.setString(17, published.contentCodes());
          insert.setInt(18, maxPlayers);
          insert.setInt(19, published.maxWorlds());
          insert.setInt(20, tps);
          insert.setInt(21, mspt);
          insert.setInt(22, heapUsed);
          insert.setInt(23, heapMax);
          insert.executeUpdate();
        }
      } catch (SQLException failed) {
        // A missed heartbeat costs liveness, not correctness: peers see this node age out and
        // route elsewhere. It must never take down the caller.
        logger.warning("Node heartbeat failed: " + failed.getMessage());
      }
    }
  }

  private static int readHealth(java.util.function.IntSupplier probe) {
    try {
      return probe.getAsInt();
    } catch (RuntimeException unavailable) {
      return com.sexidium.core.platform.NodeHealthPort.UNKNOWN;
    }
  }

  private static String roleOf(NodeIdentity identity) {
    if (identity.isStandalone()) {
      return "standalone";
    }
    if (identity.can(NodeCapability.ROUTER)) {
      return "proxy";
    }
    return identity.can(NodeCapability.LOBBY) ? "lobby" : "worker";
  }

  /** Every registered node, alive or not. */
  public List<Node> all() {
    List<Node> nodes = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(SELECT_ALL_SQL);
           ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          nodes.add(new Node(
              rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
              rs.getString(5), rs.getInt(6), rs.getString(7), rs.getInt(8), rs.getInt(9),
              rs.getLong(10), rs.getLong(11),
              rs.getLong(12), rs.getString(13), rs.getString(14), rs.getString(15),
              rs.getString(16), rs.getInt(17), rs.getInt(18),
              // getInt() reads a SQL NULL as 0, and 0 TPS is a reading, not an absence. A row
              // written by a node that predates these columns must read as UNKNOWN or the
              // saturation gate would exclude every one of them for being "at 0 TPS".
              unknownIfNull(rs, 19), unknownIfNull(rs, 20), rs.getInt(21), rs.getInt(22)));
        }
      } catch (SQLException failed) {
        logger.warning("Could not read the node registry: " + failed.getMessage());
      }
    }
    return nodes;
  }

  private static int unknownIfNull(ResultSet rs, int column) throws SQLException {
    int value = rs.getInt(column);
    return rs.wasNull() ? com.sexidium.core.platform.NodeHealthPort.UNKNOWN : value;
  }

  /** Nodes currently alive, by heartbeat age. */
  public List<Node> alive() {
    long now = System.currentTimeMillis();
    return all().stream().filter(node -> node.alive(now, timeoutMillis)).toList();
  }

  /** Alive nodes holding a capability — the placement question, "who could host this?". */
  public List<Node> with(NodeCapability capability) {
    String wanted = capability.name().toLowerCase(Locale.ROOT);
    return alive().stream()
        .filter(node -> node.capabilities() != null)
        .filter(node -> List.of(node.capabilities().split(",")).contains(wanted))
        .toList();
  }

  /**
   * Whether ANY live node holds a capability — "could this network do X at all right now?".
   *
   * <p>Distinct from {@link #with(NodeCapability)} in intent rather than in mechanism: the caller is
   * a node that does <em>not</em> hold the capability and needs to know whether someone else does.
   * The proxy asks this about {@link NodeCapability#BOT_HOST} because {@code auth.require-for-login:
   * auto} resolves through {@code bot.enabled}/{@code bot.token}, and those keys live on the bot's
   * node — so on the proxy {@code auto} silently meant "off" and the gate allowed everybody.</p>
   *
   * <p>Never throws: a registry read that fails answers {@code false}, because the callers of this
   * degrade to a documented fallback rather than to a stack trace on a login.</p>
   */
  public boolean anyAlive(NodeCapability capability) {
    if (capability == null) {
      return false;
    }
    try {
      return !with(capability).isEmpty();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  /**
   * Mark every node whose heartbeat has aged out as DOWN.
   *
   * <p>Idempotent and safe to run from several nodes at once. Returns the ids it transitioned, so the
   * caller can announce them on the bus.</p>
   */
  public List<String> reapStaleNodes() {
    long now = System.currentTimeMillis();
    List<String> reaped = new ArrayList<>();
    for (Node node : all()) {
      if (node.nodeId().equals(identity.nodeId()) || STATE_DOWN.equals(node.state())) {
        continue;
      }
      if (node.alive(now, timeoutMillis)) {
        continue;
      }
      synchronized (database.lock()) {
        try (PreparedStatement ps = database.connection().prepareStatement(
            "UPDATE network_nodes SET state = ? WHERE node_id = ? AND heartbeat_at = ?")) {
          ps.setString(1, STATE_DOWN);
          ps.setString(2, node.nodeId());
          // Guarded on the heartbeat we read: if the node checked in between our read and this
          // write, the UPDATE matches nothing and we correctly leave it alone.
          ps.setLong(3, node.heartbeatAt());
          if (ps.executeUpdate() > 0) {
            reaped.add(node.nodeId());
          }
        } catch (SQLException failed) {
          logger.warning("Could not reap node " + node.nodeId() + ": " + failed.getMessage());
        }
      }
    }
    return reaped;
  }
}
