package com.sexidium.core.network;

import com.sexidium.core.platform.ConfigurationAdapter;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the {@code network:} config block into a {@link NodeIdentity}.
 *
 * <p>There is exactly one switch — {@code network.enabled}. When it is false (the shipped default and
 * the only thing a standalone server ever sees) this returns {@link NodeIdentity#standalone()}, every
 * capability gate passes, and no networking code is reachable. That is what keeps one code path serving
 * both topologies instead of {@code if (networked)} scattered through gameplay.</p>
 *
 * <pre>{@code
 * network:
 *   enabled: false          # THE switch
 *   node:
 *     id: ''                # blank -> the role name, or "standalone"
 *     display-name: ''
 *     role: standalone      # standalone | lobby | worker | proxy
 *     capabilities: []      # optional explicit override of the role's preset
 * }</pre>
 */
public final class NetworkSettings {

  private NetworkSettings() {
  }

  /** Resolve this process's identity from configuration. Never throws for a standalone server. */
  public static NodeIdentity resolve(ConfigurationAdapter configuration) {
    if (configuration == null || !configuration.getBoolean("network.enabled", false)) {
      return NodeIdentity.standalone();
    }

    String role = configuration.getString("network.node.role", "standalone").trim();
    // network.enabled: true with role: standalone is a contradiction an operator can
    // reach by flipping one key. Treat the role as authoritative and stay standalone
    // rather than inventing a networked identity nothing else is configured for.
    if (role.isEmpty() || "standalone".equalsIgnoreCase(role)) {
      return NodeIdentity.standalone();
    }

    Set<NodeCapability> capabilities = explicitCapabilities(configuration);
    if (capabilities.isEmpty()) {
      capabilities = NodeIdentity.capabilitiesForRole(role);
    }

    String nodeId = configuration.getString("network.node.id", "").trim();
    if (nodeId.isEmpty()) {
      // Deliberately the role and not a random UUID: the id has to survive a restart,
      // because it is the routing key a world placement is pinned to.
      nodeId = role.toLowerCase(Locale.ROOT);
    }
    String displayName = configuration.getString("network.node.display-name", "").trim();

    return NodeIdentity.of(nodeId, displayName, capabilities);
  }

  /** Default seconds between heartbeats. Also the tick on which world leases are renewed. */
  public static final long DEFAULT_HEARTBEAT_SECONDS = 5L;
  /**
   * Default seconds a world claim survives without a renewal.
   *
   * <p>Was 60, which is LONGER than the node timeout — so a node could be declared dead, its worlds
   * handed to a peer, and its own claim still be live for another half minute. Two writers on one set
   * of region files. The lease has to expire inside the window in which the network still believes the
   * holder is alive, which is what {@link #validateTimings} now refuses to let an operator undo.</p>
   */
  public static final long DEFAULT_WORLD_LEASE_SECONDS = 15L;
  /** Default seconds without a heartbeat after which a node is reaped. */
  public static final long DEFAULT_NODE_TIMEOUT_SECONDS = 30L;

  /**
   * How many ENTRY transfers into one node's worlds are allowed per breaker window.
   *
   * <p>Declared here because it was declared twice: the backend defaulted it to 6 and the proxy to 3,
   * from two separate literals with no relationship. Inert while the proxy never calls
   * {@code request()}, but it is a config-drift trap that only shows up under load — and a drain
   * generates exactly that load. One constant, read by both.</p>
   *
   * <p>6 and not 3 because only entries are counted (an exit is never bounded), so this is "how many
   * times may a player be sent INTO worlds on one node per minute". Three was chosen against an
   * observed transfer loop and turned out to be inside the range of ordinary play: a player trying a
   * few experiences in a row hit it and was told the server was offline.</p>
   */
  public static final long DEFAULT_BREAKER_MAX_PER_NODE = 6L;
  /** Default seconds the breaker counts entries over. */
  public static final long DEFAULT_BREAKER_WINDOW_SECONDS = 60L;

  /** The three timers that have to agree with one another, resolved together so they cannot drift. */
  public record Timings(long heartbeatSeconds, long worldLeaseSeconds, long nodeTimeoutSeconds) {
    public long heartbeatMillis() {
      return heartbeatSeconds * 1000L;
    }

    public long worldLeaseMillis() {
      return worldLeaseSeconds * 1000L;
    }

    public long nodeTimeoutMillis() {
      return nodeTimeoutSeconds * 1000L;
    }
  }

  /** Reads the three timers from config, applying the defaults, and asserts they are consistent. */
  public static Timings timings(ConfigurationAdapter configuration) {
    if (configuration == null) {
      return new Timings(
          DEFAULT_HEARTBEAT_SECONDS, DEFAULT_WORLD_LEASE_SECONDS, DEFAULT_NODE_TIMEOUT_SECONDS);
    }
    long heartbeat =
        Math.max(1L, configuration.getLong("network.heartbeat-seconds", DEFAULT_HEARTBEAT_SECONDS));
    long worldLease =
        configuration.getLong("network.world-lease-seconds", DEFAULT_WORLD_LEASE_SECONDS);
    long nodeTimeout =
        configuration.getLong("network.node-timeout-seconds", DEFAULT_NODE_TIMEOUT_SECONDS);
    validateTimings(heartbeat, worldLease, nodeTimeout);
    return new Timings(heartbeat, worldLease, nodeTimeout);
  }

  /**
   * Invariant I9: {@code world-lease-seconds ∈ [3 × heartbeat, node-timeout)}.
   *
   * <p>Both halves are load-bearing and both were violated by the shipped defaults (60 s lease,
   * 30 s timeout). Below three heartbeats a single dropped renewal — one long GC, one slow query on
   * the shared connection — evicts a healthy holder from a world full of players. At or above the
   * node timeout the reaper can declare a node dead and hand its worlds to a peer while the dead
   * node's own claim is still valid, and nothing in Minecraft stops the two of them writing the same
   * region files (a keyed dimension folder carries no {@code session.lock}).</p>
   *
   * <p>Fails at boot, naming both keys, rather than warning: a warning nobody reads is exactly how
   * this shipped.</p>
   */
  public static void validateTimings(
      long heartbeatSeconds, long worldLeaseSeconds, long nodeTimeoutSeconds) {
    if (heartbeatSeconds <= 0) {
      throw new IllegalStateException(
          "network.heartbeat-seconds must be at least 1 (got " + heartbeatSeconds + ").");
    }
    if (worldLeaseSeconds < 3 * heartbeatSeconds) {
      throw new IllegalStateException(
          "network.world-lease-seconds (" + worldLeaseSeconds + ") must be at least three times"
              + " network.heartbeat-seconds (" + heartbeatSeconds + "), i.e. "
              + (3 * heartbeatSeconds) + " or more. A shorter lease evicts a healthy world holder"
              + " the first time one renewal is late.");
    }
    if (worldLeaseSeconds >= nodeTimeoutSeconds) {
      throw new IllegalStateException(
          "network.world-lease-seconds (" + worldLeaseSeconds + ") must be strictly less than"
              + " network.node-timeout-seconds (" + nodeTimeoutSeconds + "). Otherwise a node can be"
              + " reaped and its worlds re-homed while its own claim is still valid, and two servers"
              + " write the same region files.");
    }
  }

  /**
   * An explicit {@code network.node.capabilities} list overrides the role preset, for topologies the
   * four presets do not cover (a two-machine deployment folding the queue onto a worker, say).
   */
  private static Set<NodeCapability> explicitCapabilities(ConfigurationAdapter configuration) {
    List<String> configured = configuration.getStringList("network.node.capabilities");
    if (configured == null || configured.isEmpty()) {
      return EnumSet.noneOf(NodeCapability.class);
    }
    Set<NodeCapability> capabilities = EnumSet.noneOf(NodeCapability.class);
    for (String entry : configured) {
      if (entry == null || entry.isBlank()) {
        continue;
      }
      // Accept the config-friendly spelling (queue-authority) as well as the enum name.
      String name = entry.trim().replace('-', '_').toUpperCase(Locale.ROOT);
      try {
        capabilities.add(NodeCapability.valueOf(name));
      } catch (IllegalArgumentException unknown) {
        throw new IllegalArgumentException(
            "Unknown network.node.capabilities entry '" + entry + "'; expected one of "
                + EnumSet.allOf(NodeCapability.class));
      }
    }
    return capabilities;
  }
}
