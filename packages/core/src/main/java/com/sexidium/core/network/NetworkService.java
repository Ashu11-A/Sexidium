package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.NodeRuntime;
import com.sexidium.core.platform.ScheduledTask;

import java.util.List;

/**
 * The single place that decides whether this process is networked, and wires the network ports
 * accordingly.
 *
 * <p>Everything else asks {@link #bus()} and gets an implementation that behaves the same either
 * way. That is the whole design: one code path, one factory, no {@code if (networked)} scattered
 * through gameplay.</p>
 *
 * <p>Standalone costs nothing measurable — an in-process fan-out, no registry, no timers, and not a
 * single row written to any {@code network_*} table.</p>
 */
public final class NetworkService implements AutoCloseable {

  private static final long TICKS_PER_SECOND = 20L;

  private final NodeRuntime runtime;
  private final Database database;
  private final NodeIdentity identity;
  private final NetworkBus bus;
  private final NodeRegistry registry;
  /** This build's wire tag. Read from here, never from {@link Protocol}, so a test can inject one. */
  private final ProtocolTag protocolTag;
  /** Which build this process is, as published in {@code network_nodes.plugin_version}. */
  private final BuildIdentity buildIdentity;
  private final DbWorldLeaseAuthority placements;
  private final com.sexidium.core.network.transfer.DbTransferService transfers;
  private final LobbyDirectory lobbyDirectory;
  private final MatchHandoffService handoffs;
  private final ExperienceCommandStore experienceCommands;
  private final NodePlacementPlanner planner;
  private final boolean networked;
  private final NetworkSettings.Timings timings;
  private boolean sharedWorldStorage;

  /**
   * Whether a world key's folder is on this node's disk.
   *
   * <p>Installed by the platform once the world control exists. It is the tie-breaker of last resort
   * in {@link #placementGate()}: a world nobody has registered but which is right here must be
   * claimed here, never planned onto a peer that would generate an empty one over it. Defaults to
   * "no" so an un-wired platform simply plans normally.</p>
   */
  private volatile java.util.function.Predicate<String> localDisk = worldKey -> false;

  private ScheduledTask heartbeatTask;
  /** Defaults that make the lease heartbeat a no-op until the world layer wires itself in. */
  private volatile java.util.function.Supplier<java.util.Set<String>> openWorlds = java.util.Set::of;
  private volatile java.util.function.Supplier<java.util.Map<String, Integer>> worldPopulations =
      java.util.Map::of;
  /** What was open at the previous tick, so a world that closed can have its lease dropped. */
  private volatile java.util.Set<String> leasedLastTick = java.util.Set.of();

  public NetworkService(NodeRuntime runtime, Database database) {
    this(runtime, database, ProtocolTag.current());
  }

  /**
   * The overload a mixed-version test uses: two tags, one JVM, one database.
   *
   * <p>Nothing reads {@link Protocol#VERSION} at a call site — it enters the system here and here
   * only, which is what makes "an old node and a new node against the same tables" a plain unit
   * test instead of a second JVM.</p>
   */
  public NetworkService(NodeRuntime runtime, Database database, ProtocolTag protocolTag) {
    this.runtime = runtime;
    this.database = database;
    this.identity = runtime.identity();
    this.protocolTag = protocolTag == null ? ProtocolTag.current() : protocolTag;
    // The build id comes from the RUNTIME property (-Dsexidium.build.id), not from the jar: it is
    // chosen by whatever pinned this node's jar, so it is race-free and it is the value an
    // orchestrator correlates on. The version half comes from the generated build stamp.
    this.buildIdentity = BuildIdentity.load(
        runtime.resources(), runtime.configuration().getString("build.id", ""));

    // Networked means BOTH a configured role and a database to share through. A node with
    // network.enabled but no shared database would heartbeat into its own private SQLite and
    // believe it was alone, which is a more confusing failure than staying standalone.
    this.networked = !identity.isStandalone() && database != null;

    if (networked) {
      // Resolved and cross-checked BEFORE anything is constructed: a network whose lease outlives its
      // own node timeout must not reach the point of opening a world (invariant I9).
      this.timings = NetworkSettings.timings(runtime.configuration());
      long retention = runtime.configuration().getLong("network.bus.retention-seconds", 300L) * 1000L;
      long sweepInterval =
          runtime.configuration().getLong("network.bus.sweep-interval-seconds", 60L) * 1000L;
      long pollTicks = Math.max(5L, runtime.configuration().getLong("network.bus.poll-ticks", 20L));
      this.bus = new DbNetworkBus(
          database, runtime.logger(), identity.nodeId(),
          poll -> {
            ScheduledTask task = runtime.scheduler().runTimer(poll, pollTicks, pollTicks);
            return task::cancel;
          },
          retention, sweepInterval);
      long timeout = timings.nodeTimeoutMillis();
      this.registry = new NodeRegistry(database, runtime.logger(), identity, timeout, this.protocolTag);
      this.registry.publishHealth(runtime.health());
      // Every node publishes where it can be REACHED, not just that it is alive. Only the proxy did
      // this, so `network_nodes.address`/`port` stayed empty for every backend and the proxy's
      // BackendDirectory skipped all of them -- which makes "a worker joins by starting up" (D5)
      // inert, and would break every transfer the moment velocity.toml stops listing the workers
      // explicitly. The address is what the OTHER side must dial, so it defaults to the node id: on
      // the Docker network that is exactly the container hostname the proxy resolves.
      this.registry.publishAddress(
          runtime.configuration().getString("network.node.address", identity.nodeId()),
          (int) runtime.configuration().getLong("network.node.port", 0L));
      long worldLease = timings.worldLeaseMillis();
      // Shared world storage turns the workers into interchangeable hosts: any of them may take over
      // an idle world, because all of them see the same folders. It is opt-in, and it MUST match how
      // the volumes are actually mounted -- claiming that per-node disks are shared would let a peer
      // "take over" a world whose bytes it cannot see and generate an empty one in its place.
      //
      // ON by default on a network, because a network is precisely where it is both safe and wanted:
      // the provisioner points every node's experiences folder at one shared tree (see
      // docker/provision.sh), and without this the first node to open a world becomes its permanent
      // home no matter how loaded it gets. An operator whose nodes do NOT share storage -- separate
      // machines, separate disks -- must set it to false, and the check below is what tells them.
      this.sharedWorldStorage =
          runtime.configuration().getBoolean("network.shared-world-storage", true);
      this.placements = new DbWorldLeaseAuthority(
          database, runtime.logger(), identity, registry.epoch(), worldLease);
      long ticketTtl = runtime.configuration().getLong("network.transfer.ttl-seconds", 30L) * 1000L;
      this.transfers = new com.sexidium.core.network.transfer.DbTransferService(
          database, runtime.logger(), identity.nodeId(), ticketTtl,
          (int) runtime.configuration().getLong("network.transfer.max-attempts", 3L),
          runtime.configuration().getLong("network.transfer.breaker-window-seconds",
              NetworkSettings.DEFAULT_BREAKER_WINDOW_SECONDS) * 1000L,
          // 6, not 3. Only ENTRY transfers are counted now (an exit is never bounded), so this is
          // "how many times may a player be sent INTO worlds on one node per minute". Three was
          // chosen against the observed loop and turned out to be inside the range of ordinary play:
          // a player trying a few experiences in a row hit it and was told the server was offline.
          (int) runtime.configuration().getLong("network.transfer.breaker-max-per-node",
              NetworkSettings.DEFAULT_BREAKER_MAX_PER_NODE));
      this.lobbyDirectory = new LobbyDirectory(database, runtime.logger());
      this.handoffs = new MatchHandoffService(database, runtime.logger());
      // The durable half of an owner action that has to run on another node. See
      // ExperienceCommandStore: the bus can announce a delete, it cannot promise one.
      this.experienceCommands = new DbExperienceCommandStore(database, runtime.logger());
      this.planner = new NodePlacementPlanner(registry, identity);
    } else {
      // Standalone never reads the timers, but keeping the field final and non-null means every
      // caller can ask without a null check.
      this.timings = new NetworkSettings.Timings(
          NetworkSettings.DEFAULT_HEARTBEAT_SECONDS,
          NetworkSettings.DEFAULT_WORLD_LEASE_SECONDS,
          NetworkSettings.DEFAULT_NODE_TIMEOUT_SECONDS);
      // Deferred onto the scheduler, so local delivery is asynchronous exactly like the
      // networked bus. Code that works standalone then keeps working on a network.
      this.bus = new LocalNetworkBus(identity.nodeId(), task -> runtime.scheduler().runNow(task));
      this.registry = null;
      // Standalone owns every world it has by definition; there is nothing to arbitrate,
      // and there is nowhere to route a player to.
      this.placements = null;
      this.transfers = null;
      // Standalone: the in-memory lobby maps are already the whole truth.
      this.lobbyDirectory = null;
      this.handoffs = null;
      // Standalone runs every owner action on the spot; there is nobody to ask and nothing to record.
      this.experienceCommands = null;
      // Standalone still gets a planner: it answers "this node" to everything, which lets callers
      // ask the same question either way instead of branching on networked().
      this.planner = new NodePlacementPlanner(null, identity);
    }
  }

  public NodeIdentity identity() {
    return identity;
  }

  public NetworkBus bus() {
    return bus;
  }

  /** The node registry, or null when standalone (there are no peers to register with). */
  public NodeRegistry registry() {
    return registry;
  }

  /** World placement, or null when standalone (this node owns every world it has). */
  public DbWorldLeaseAuthority placements() {
    return placements;
  }

  /**
   * The gate {@code AbstractWorldControl} consults before creating or loading a persistent world.
   *
   * <p>Standalone returns the allow-all gate, so a single server never queries the database on the
   * world-acquisition path — the hot path stays exactly as fast as it is today.</p>
   */
  public com.sexidium.core.world.WorldPlacementGate placementGate() {
    if (!networked) {
      return com.sexidium.core.world.WorldPlacementGate.ALLOW_ALL;
    }
    // Constructed per call rather than cached so the disk index installed by the platform (which
    // only exists once the world control does) is always the current one.
    //
    // Both content seams are LAMBDAS over the volatile fields rather than the fields themselves: the
    // gate is installed once at startup and the requirements lookup later still by the experience
    // layer, so capturing either value here would freeze the pre-wiring default forever.
    return new PlacementDecider(placements, planner, identity, localDisk,
        this::canHostContent, worldKey -> contentRequirements.requiredCodes(worldKey));
  }

  /** The node chooser. Never null; standalone's answers to everything is "here". */
  public NodePlacementPlanner planner() {
    return planner;
  }

  /** Tell the network which world folders this node actually has. See {@link #localDisk}. */
  public void setLocalDiskIndex(java.util.function.Predicate<String> localDisk) {
    this.localDisk = localDisk == null ? worldKey -> false : localDisk;
  }

  // ----- build identity, protocol and content -------------------------------------------------

  /** This build's wire tag. The single entry point for {@link Protocol}'s constants. */
  public ProtocolTag protocolTag() {
    return protocolTag;
  }

  /** Which build this process is running. Never null. */
  public BuildIdentity buildIdentity() {
    return buildIdentity;
  }

  /**
   * The shared database, for the subsystems that own their own tables ({@code node_drains},
   * {@code network_leases}). Null when standalone, where none of those tables are ever touched.
   */
  public Database database() {
    return networked ? database : null;
  }

  /**
   * What content this node can run. Defaults to an empty manifest, which — because
   * {@link ContentManifest#covers} treats an empty requirement as satisfied — leaves placement
   * behaving exactly as it did before anything is wired.
   */
  public ContentManifest contentManifest() {
    return contentManifest;
  }

  /** Publish this node's content set. Called once, at startup, from the core. */
  public void setContentManifest(ContentManifest contentManifest) {
    this.contentManifest = contentManifest == null ? new ContentManifest(java.util.List.of())
        : contentManifest;
    republishTelemetry();
  }

  /**
   * The sha256 of the plugin jar this process loaded, published as a cross-check against the
   * artifact an orchestrator staged. Best-effort: null when the platform cannot compute it.
   */
  public void setBuildSha(String buildSha) {
    this.buildSha = buildSha;
    republishTelemetry();
  }

  /** What a world needs of a node before that node may host it. See {@link WorldContentRequirements}. */
  public WorldContentRequirements contentRequirements() {
    return contentRequirements;
  }

  /** Install the world → required-codes lookup. Null restores {@link WorldContentRequirements#NONE}. */
  public void setContentRequirements(WorldContentRequirements contentRequirements) {
    this.contentRequirements = contentRequirements == null
        ? WorldContentRequirements.NONE : contentRequirements;
  }

  /**
   * Whether this node can run everything {@code worldKey} needs.
   *
   * <p>The one call every enforcement point makes, so the four of them cannot answer differently.
   * Fails OPEN on any lookup error: a requirements probe that throws must not turn into a refused
   * world, because a refused world is a player who cannot get into their own save.</p>
   */
  public boolean canHostContent(String worldKey) {
    return missingContentFor(worldKey).isEmpty();
  }

  /** What this node is missing for {@code worldKey}, for the refusal message and the log line. */
  public java.util.List<String> missingContentFor(String worldKey) {
    try {
      return contentManifest.missing(contentRequirements.requiredCodes(worldKey));
    } catch (RuntimeException unavailable) {
      return java.util.List.of();
    }
  }

  /**
   * Say, once, that this node refused a world it cannot run.
   *
   * <p>Emitted from here rather than at each refusal site so the four enforcement points cannot
   * word it differently — an orchestrator greps this exact prefix, and a content refusal is the
   * signal that the fleet's builds have drifted far enough to strand a world.</p>
   *
   * <p>The refusal itself is not a failure of this node: the world is never deleted and never
   * regenerated, it simply stays shut until a node that has the content opens it. That is strictly
   * better than the old behaviour, which was to open it <em>wrong</em>.</p>
   */
  public void logContentRefusal(String worldKey, java.util.List<String> missing) {
    runtime.logger().warning("SX-CONTENT refused world=" + worldKey + " missing="
        + (missing == null ? "[]" : missing.toString()));
  }

  // ----- drain ---------------------------------------------------------------------------------

  /**
   * The drain control surface: the HTTP endpoints, the console commands and the heartbeat tick all
   * go through this. {@link DrainControlPort#INERT} until a coordinator is installed, which reports
   * NONE and refuses every request with {@code UNAVAILABLE} rather than pretending to accept one.
   */
  public DrainControlPort drainControl() {
    return drainControl;
  }

  /** Install the drain coordinator. Null reverts to {@link DrainControlPort#INERT}. */
  public void setDrainControl(DrainControlPort drainControl) {
    this.drainControl = drainControl == null ? DrainControlPort.INERT : drainControl;
  }

  /** Whether this node is draining right now — the question the placement and arrival paths ask. */
  public boolean draining() {
    return drainControl.state().phase() != DrainPhase.NONE;
  }

  /**
   * "Is this node fit to serve?" — see {@link NodeSelfTest}. Held here rather than on the API server
   * because both the console command and the endpoint have to give the same answer, and because a
   * node that never wired one must say so rather than report a healthy default.
   */
  public NodeSelfTest selfTest() {
    return selfTest;
  }

  /** Install the selftest. Null leaves this node answering "no selftest is available". */
  public void setSelfTest(NodeSelfTest selfTest) {
    this.selfTest = selfTest;
  }

  private volatile NodeSelfTest selfTest;
  private volatile ContentManifest contentManifest = new ContentManifest(java.util.List.of());
  private volatile WorldContentRequirements contentRequirements = WorldContentRequirements.NONE;
  private volatile DrainControlPort drainControl = DrainControlPort.INERT;
  private volatile String buildSha;

  /**
   * Push build/content/capacity into the registry, so the next heartbeat publishes it.
   *
   * <p>Resolved here rather than per heartbeat because none of it moves while the process lives —
   * the only genuinely live numbers (tick health, heap) come from {@code NodeHealthPort} and are
   * re-read on every write.</p>
   */
  private void republishTelemetry() {
    if (registry == null) {
      return;
    }
    registry.publishTelemetry(new NodeRegistry.Telemetry(
        buildIdentity.publishedVersion(),
        buildSha,
        contentManifest.digest(),
        contentManifest.encoded(),
        (int) runtime.configuration().getLong("network.node.max-players", 0L),
        (int) runtime.configuration().getLong("network.node.max-worlds", 0L)));
  }

  /**
   * Reconcile disk, placements and experiences. Null standalone, where a single node's disk is the
   * only truth there is and nothing can disagree with it.
   */
  public PlacementReconciler reconciler(
      java.util.function.Supplier<PlacementReconciler.DiskScan> localWorlds,
      java.util.function.Predicate<String> isKnownExperience) {
    if (!networked) {
      return null;
    }
    return new PlacementReconciler(
        placements, identity, runtime.logger(), localWorlds, isKnownExperience, sharedWorldStorage);
  }

  /** The requesting half of the transfer protocol, or null when standalone (nowhere to send anyone). */
  public com.sexidium.core.network.transfer.TransferDispatcher transfers() {
    return transfers;
  }

  /** The destination half: "was this player sent HERE, and what for?". Null standalone. */
  public com.sexidium.core.network.transfer.ArrivalGate arrivals() {
    return transfers;
  }

  /** The loop breaker. Null standalone, where there is nowhere to bounce a player to. */
  public com.sexidium.core.network.transfer.TransferCircuitBreaker breaker() {
    return transfers;
  }

  /** The concrete transfer service, for the admin surface. Null standalone. */
  public com.sexidium.core.network.transfer.DbTransferService transferService() {
    return transfers;
  }

  /** The proxy-side executor. Null standalone; the proxy builds its own against the shared database. */
  public com.sexidium.core.network.transfer.TransferExecutor transferExecutor() {
    return transfers;
  }

  /** Shared party membership, or null when standalone. */
  public LobbyDirectory lobbyDirectory() {
    return lobbyDirectory;
  }

  /** Cross-node match assembly, or null when standalone. */
  public MatchHandoffService handoffs() {
    return handoffs;
  }

  /** Where cross-node owner actions are recorded, or null when standalone. */
  public ExperienceCommandStore experienceCommands() {
    return experienceCommands;
  }

  public boolean networked() {
    return networked;
  }

  /** Whether every node sees the same world folders (and so may take an idle world over). */
  public boolean sharedWorldStorage() {
    return sharedWorldStorage;
  }

  /**
   * A node that can do FOLDER work for a world nothing records a home for, or null.
   *
   * <p>The recovery half of the unplaced-copy fix. Prevention lives in
   * {@code ExperienceBackupService.PlacementRecorder}, which records a home the moment a copy's
   * folders land; this is for every copy that already exists without one — an owner's backup made
   * before that fix, sitting on the shared tree, refused by the lobby every time they try to delete
   * it. Wired into {@code ExperienceCommandRouter.HomeFinder}, which asks only when the placement
   * table was read cleanly and holds nothing.</p>
   *
   * <p><b>Shared storage only, and that is the whole safety argument.</b> There, every node sees
   * every folder, so any capable node can run the op on the real bytes. Without it a world's folder
   * is on exactly one disk and this method has no way to know which — and a delete sent to the wrong
   * node deletes nothing, reports success (a folder that was never there deletes cleanly), and the
   * caller then drops the rows that were the only thing naming the real one. Answering null on
   * per-node disks is not a limitation: there the reconciler adopts unregistered folders on boot, so
   * the state this repairs does not survive a restart in the first place.</p>
   */
  public String homeForUnplacedWorld(String worldKey) {
    if (!networked || placements == null || !sharedWorldStorage
        || worldKey == null || worldKey.isBlank()) {
      return null;
    }
    // A world that HAS a home is not this method's business, and answering for one would let a
    // caller route around a placement it should have read. The router only asks about worlds it
    // already read as unplaced; this is the guard against that read having gone stale.
    if (placements.lookup(worldKey).isPresent()) {
      return null;
    }
    String chosen = planner.choose(NodeCapability.EXPERIENCES, requiredCodesFor(worldKey))
        .orElse(null);
    if (chosen == null || chosen.equals(identity.nodeId())) {
      // `choose` is deliberately generous about peers and falls back to "well, me" when nothing else
      // answers. About OURSELVES there is nothing to be generous with: the only caller asks because
      // this node may not do the work, so self is the one answer that cannot help.
      return null;
    }
    return chosen;
  }

  /** What a world needs of a host, never throwing: an unwired lookup means "nothing in particular". */
  private java.util.List<String> requiredCodesFor(String worldKey) {
    try {
      java.util.List<String> codes = contentRequirements.requiredCodes(worldKey);
      return codes == null ? java.util.List.of() : codes;
    } catch (RuntimeException unavailable) {
      return java.util.List.of();
    }
  }

  public void start() {
    bus.start();
    if (!networked) {
      return;
    }
    // Publish identity and capacity BEFORE the first heartbeat, so a node is never visible to a
    // rolling update as "up, on an unknown build" -- which is indistinguishable from the previous
    // build and would make an orchestrator wait out its whole timeout on a node that is fine.
    republishTelemetry();
    heartbeatTask = runtime.scheduler().runTimer(
        this::heartbeat, TICKS_PER_SECOND, timings.heartbeatSeconds() * TICKS_PER_SECOND);

    runtime.logger().info("Network node '" + identity.nodeId() + "' online with capabilities "
        + identity.capabilities() + " (heartbeat " + timings.heartbeatSeconds() + "s, world lease "
        + timings.worldLeaseSeconds() + "s, node timeout " + timings.nodeTimeoutSeconds() + "s)");
  }

  /** The three cross-checked timers this node runs on. Never null. */
  public NetworkSettings.Timings timings() {
    return timings;
  }

  /**
   * Three jobs on one timer used to be one method, and they are not the same job.
   *
   * <p>"I am alive", "my worlds are still mine" and "somebody else is dead" run at different rates and
   * have different blast radii — and running the third from every node is O(N) guarded UPDATEs per
   * node per window, i.e. O(N²) network-wide, all serialised behind one JDBC connection. Reaping is
   * therefore rate-limited here and will move under an elected lease in stage 9.</p>
   */
  private void heartbeat() {
    // The world count is REPORTED, not hard-coded 0 as it was. The planner's tiebreak
    // (NodePlacementPlanner.byPreference -> thenComparingInt(Node::worlds)) reads this column, so a
    // constant 0 made it dead and sent every new world to the alphabetically first worker during
    // exactly the burst where spreading them matters.
    // The state comes from the drain port, so "I am alive" and "I am leaving" are ONE write. They
    // used to be two: UP here, DRAINING from the tick below, in separate transactions -- and a peer
    // reading network_nodes between them saw a draining node as UP and could plant a world or a
    // player on a node that is on its way out.
    registry.heartbeat(
        drainControl.state().phase() == DrainPhase.NONE
            ? NodeRegistry.STATE_UP : NodeRegistry.STATE_DRAINING,
        runtime.onlinePlayers().size(), openWorldCount());
    renewWorldLeases();
    // The drain coordinator owns no timer: it advances here, on the beat the rest of the network
    // already runs on. A throw must not cost the heartbeat -- a node that stops checking in is
    // reaped and loses its worlds, which is a far worse outcome than a drain that stalls.
    try {
      drainControl.tick();
    } catch (RuntimeException failed) {
      runtime.logger().warning("Drain tick failed: " + failed.getMessage());
    }
    reportProtocolSkew();

    long now = System.currentTimeMillis();
    if (now - lastReap < timings.nodeTimeoutMillis() / 3) {
      return;
    }
    lastReap = now;
    reap();
  }

  /**
   * Say once, per peer, per boot, how this build relates to that one.
   *
   * <p>An orchestrator tails these: an {@code INCOMPATIBLE} verdict anywhere in the fleet is a
   * roll-abort condition, because it means one node will refuse to adopt what another releases and a
   * drain would stall with nowhere to put its worlds. Everything else is informational — an
   * incompatible peer stays routable and keeps its players (nothing player-facing is ever gated on
   * the tag), it is only excluded as an ownership target.</p>
   */
  private void reportProtocolSkew() {
    for (NodeRegistry.Node peer : registry.all()) {
      if (peer.nodeId() == null || peer.nodeId().equals(identity.nodeId())
          || !reportedProtocolOf.add(peer.nodeId())) {
        continue;
      }
      String verdict = protocolTag.verdict(peer.protocol());
      String line = "SX-PROTOCOL peer=" + peer.nodeId() + " peerProtocol=" + peer.protocol()
          + " mine=" + protocolTag.version() + " verdict=" + verdict;
      if ("INCOMPATIBLE".equals(verdict)) {
        runtime.logger().warning(line);
      } else {
        runtime.logger().info(line);
      }
    }
  }

  /** Peers already reported on, so the skew verdict is one line per peer and not one per heartbeat. */
  private final java.util.Set<String> reportedProtocolOf =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  /** Declare unresponsive peers DOWN, and tidy up leases that have ALREADY expired. */
  private void reap() {
    // Any node may reap; the transition is a guarded UPDATE, so several racing sweeps are safe.
    List<String> reaped = registry.reapStaleNodes();
    for (String nodeId : reaped) {
      runtime.logger().warning("Node '" + nodeId + "' stopped responding and was marked DOWN");
      bus.publish(NetworkBus.Topics.NODE_STATE, nodeId, NodeRegistry.STATE_DOWN);

      // Clears ONLY leases that have already expired by the clock. It used to zero the lease on every
      // row of the named node including LOADED ones whose lease was still perfectly valid, which made
      // node liveness a fencing token: a 35-second GC pause was enough to have a healthy worker
      // declared dead and its open worlds handed to a peer while it was still writing them.
      int cleared = placements.clearExpiredLeasesOf(nodeId);
      if (cleared > 0) {
        runtime.logger().warning("Cleared " + cleared + " expired world lease(s) held by '" + nodeId
            + "'; any world it still holds a LIVE lease on was left completely alone");
      }
      // NetworkBus.Topics.PLACEMENT_CHANGED used to be published here. It had zero subscribers
      // anywhere in the tree, was keyed by NODE id with the state as payload so it did not even name
      // the world, and the fence makes it unnecessary: a dispossessed holder learns it was evicted
      // from its own next renew, which is the only place that can act on the news anyway.
    }
  }

  /** When the last reap ran, so N nodes do not all sweep N peers on every heartbeat. */
  private long lastReap;

  /**
   * Keep the lease of every world this node has open, and drop the lease of every world it has just
   * closed.
   *
   * <p>Nothing did either before. {@code renew} and {@code release} existed on the placement service
   * and had no callers, so a lease was a 20-second reservation that expired under a world which
   * stayed open for hours — harmless only while a world's home was also the only disk that had its
   * folder. With shared world storage the lease IS the ownership, so this is the mutual exclusion:
   * a live lease is the proof that a node is serving the world, and its absence is the invitation for
   * a peer to take it over.</p>
   *
   * <p>Released here on the tick after a world closes, rather than at the close itself, so a world
   * that closes and immediately reopens (a reset swaps folders under a running match) does not
   * publish a moment of "nobody owns this" for a peer to act on.</p>
   */
  /** How many worlds this node has open, or 0 when the world layer cannot answer. */
  private int openWorldCount() {
    try {
      return openWorlds.get().size();
    } catch (RuntimeException unavailable) {
      return 0;
    }
  }

  private void renewWorldLeases() {
    if (placements == null) {
      return;
    }
    java.util.Set<String> open;
    try {
      open = openWorlds.get();
    } catch (RuntimeException unavailable) {
      // A world layer that cannot answer is not a reason to drop leases: saying nothing keeps the
      // existing ones alive until they expire, while releasing would hand live worlds to a peer.
      runtime.logger().warning("Could not read the open worlds for the lease heartbeat: "
          + unavailable.getMessage());
      return;
    }
    java.util.Map<String, Integer> populations;
    try {
      populations = worldPopulations.get();
    } catch (RuntimeException unavailable) {
      populations = java.util.Map.of();
    }
    // ONE renewal loop, and it is the fenced one.
    //
    // There used to be an unfenced loop above this, renewing by (world_key, node_id) for everything
    // the WORLD LAYER reported open -- the Bukkit-derived set, the source of truth the placement
    // table is supposed to replace. It ran BEFORE the fenced loop, so on the very tick the fence
    // discovered this node had been evicted, the unfenced statement re-armed the lease it had just
    // lost: `/sx admin net evict` cleared the fence and the world resurrected itself 5 seconds later,
    // permanently un-takeable by any peer. A write outside the fence is exactly what the fence
    // exists to prevent, and having both meant the guarantee was only as good as the ordering.
    //
    // A renewal that is REFUSED means this node has been evicted, and the contract is not "log it":
    // freeze writes now and unload within one lease period.
    java.util.function.Consumer<com.sexidium.core.world.WorldClaim> evict = onLeaseLost;
    for (com.sexidium.core.world.WorldClaim claim : heldClaims.get()) {
      if (!placements.renew(claim, populations.getOrDefault(claim.key().key(), 0)) && evict != null) {
        evict.accept(claim);
      }
    }
    // Releasing is likewise the claim holder's job now (AbstractWorldControl hands the claim back the
    // moment a world is really closed, and only then). Releasing by node id from here raced that: a
    // world that closed and reopened across two heartbeats -- a reset swapping folders, a friend
    // re-entering -- was released out from under a claim this node still held and still had open.
    leasedLastTick = open;
  }

  /**
   * The claims this node holds, and what to do when one of them is refused.
   *
   * <p>Both default to no-ops so a standalone server and an un-wired platform behave exactly as
   * before: no claims to renew, and nothing to evict.</p>
   */
  private volatile java.util.function.Supplier<java.util.Collection<com.sexidium.core.world.WorldClaim>>
      heldClaims = java.util.List::of;
  private volatile java.util.function.Consumer<com.sexidium.core.world.WorldClaim> onLeaseLost;

  public void setClaimHeartbeat(
      java.util.function.Supplier<java.util.Collection<com.sexidium.core.world.WorldClaim>> heldClaims,
      java.util.function.Consumer<com.sexidium.core.world.WorldClaim> onLeaseLost) {
    if (heldClaims != null) {
      this.heldClaims = heldClaims;
    }
    this.onLeaseLost = onLeaseLost;
  }

  /** What this node currently has open, and who is in it — wired by the core at startup. */
  public void setOpenWorlds(
      java.util.function.Supplier<java.util.Set<String>> openWorlds,
      java.util.function.Supplier<java.util.Map<String, Integer>> worldPopulations) {
    if (openWorlds != null) {
      this.openWorlds = openWorlds;
    }
    if (worldPopulations != null) {
      this.worldPopulations = worldPopulations;
    }
  }

  @Override
  public void close() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel();
      heartbeatTask = null;
    }
    // Claims are NOT released here, and that is the whole point.
    //
    // They used to be, by claim, right here -- which is still one step ahead of the truth. This runs
    // FIRST in SexidiumCore.close(), and the worlds are unloaded near the END of it; an experience
    // world is not unloaded by the world layer's own disposal policy at all, so its region files are
    // written by the platform's final save, seconds later. Between the release and that save every
    // gate on a peer says yes -- idle placement, lapsed lease, persistent kind, folder present on the
    // shared tree -- and a second server opens the same dimension folder. The lease has to outlive
    // the close, so the release now happens in AbstractWorldControl.shutdown(), per world, after that
    // world's unload has actually returned.
    leasedLastTick = java.util.Set.of();
    if (networked && registry != null) {
      // Tell peers on the way out rather than making them wait for the timeout.
      registry.draining();
    }
    bus.close();
  }
}
