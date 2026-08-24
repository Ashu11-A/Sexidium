package com.sexidium.core.network;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;

import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * The drain state machine: "this node needs to restart — hand everything over first".
 *
 * <p><b>The response channel is the DATABASE, not the bus.</b> This node <em>releases</em> what it
 * holds and its peers answer by taking it through the conditional UPDATE they already use for every
 * world claim; the acknowledgement is the row. The bus carries one advisory {@code node.drain} line
 * and nothing here derives state from it, because it cannot: {@code DbNetworkBus} is broadcast-only
 * with no ack and no dedupe, and it seeds its cursor to {@code MAX(id)} at boot — so the node most in
 * need of the news (one that was restarting) is the one guaranteed not to receive it. That is also
 * why {@link #reclaim()} is a query and never a replay.</p>
 *
 * <p><b>No timer.</b> {@link #tick()} is driven from the network heartbeat and the clock is injected,
 * which is what makes every transition — including the timeouts — a plain unit test with no sleeping.
 * The shape is deliberately the one {@code TransferConsumer.tick()} already uses.</p>
 *
 * <p><b>Nothing here ever disconnects anybody.</b> A drain that cannot find a home for its players
 * does not push them off the cliff: it stops, writes {@code STALLED}, and lets the orchestrator abort
 * the roll. Every refusal, every failure and every timeout in this class ends in the players staying
 * exactly where they are.</p>
 */
public final class DrainCoordinator implements DrainControlPort {

  /** The four timers a drain runs on. All defaulted, all overridable under {@code drain:}. */
  public record Settings(
      long phaseTimeoutMillis, long matchGraceMillis, long leaseTtlMillis, long staleMillis) {

    public static final long DEFAULT_PHASE_TIMEOUT_SECONDS = 180L;
    /**
     * How long a live minigame is left alone before it is force-ended.
     *
     * <p>A match cannot migrate — there is no serialised match state and no disk anchor — so the only
     * choices are "wait" and "end it". Two minutes covers most rounds; past that the round is worth
     * less than the roll it is blocking, and the reconnect snapshot means nobody loses items.</p>
     */
    public static final long DEFAULT_MATCH_GRACE_SECONDS = 120L;
    /** Long enough that a slow drain never loses the lease; renewed on every heartbeat regardless. */
    public static final long DEFAULT_LEASE_SECONDS = 900L;
    /** How long a drain row of a DOWN node survives before any node may sweep it. */
    public static final long DEFAULT_STALE_SECONDS = 600L;

    public static Settings defaults() {
      return new Settings(DEFAULT_PHASE_TIMEOUT_SECONDS * 1000L,
          DEFAULT_MATCH_GRACE_SECONDS * 1000L, DEFAULT_LEASE_SECONDS * 1000L,
          DEFAULT_STALE_SECONDS * 1000L);
    }

    public static Settings from(ConfigurationAdapter configuration) {
      if (configuration == null) {
        return defaults();
      }
      return new Settings(
          configuration.getLong("drain.phase-timeout-seconds", DEFAULT_PHASE_TIMEOUT_SECONDS) * 1000L,
          configuration.getLong("drain.match-grace-seconds", DEFAULT_MATCH_GRACE_SECONDS) * 1000L,
          configuration.getLong("drain.lease-seconds", DEFAULT_LEASE_SECONDS) * 1000L,
          configuration.getLong("drain.stale-seconds", DEFAULT_STALE_SECONDS) * 1000L);
    }
  }

  private final NodeIdentity identity;
  /** Null when standalone: there are no peers to hand anything to, so there is nothing to drain. */
  private final NodeRegistry registry;
  private final DbDrainStore store;
  private final DbNetworkLeases leases;
  private final DbWorldLeaseAuthority placements;
  private final DrainWorkPort work;
  private final NetworkBus bus;
  private final LoggerAdapter logger;
  private final Settings settings;
  private final LongSupplier clock;

  /**
   * The row this process is driving, or null when this node is not draining.
   *
   * <p>An in-memory mirror of the database row, and the database is the authority: every advance
   * writes through a fenced UPDATE and a refused write drops this back to null (see
   * {@link #writeThrough}). Volatile because {@link #state()} is read from the API thread while
   * {@link #tick()} runs on the server thread.</p>
   */
  private volatile DrainRecord row;

  /** Which phase was last logged, so {@code SX-DRAIN} is one line per transition and not per tick. */
  private DrainPhase loggedPhase = DrainPhase.NONE;
  /** Whether the one-shot OFFERING work (menus, lobby repoint, match grace start) has run. */
  private boolean offeringStarted;
  private boolean matchesEnded;
  /** When the stale sweep last ran, so N idle nodes do not all scan the table every heartbeat. */
  private long lastSweep;

  public DrainCoordinator(NodeIdentity identity, NodeRegistry registry, DbDrainStore store,
      DbNetworkLeases leases, DbWorldLeaseAuthority placements, DrainWorkPort work, NetworkBus bus,
      LoggerAdapter logger, Settings settings, LongSupplier clock) {
    this.identity = identity;
    this.registry = registry;
    this.store = store;
    this.leases = leases;
    this.placements = placements;
    this.work = work == null ? DrainWorkPort.NOOP : work;
    this.bus = bus;
    this.logger = logger;
    this.settings = settings == null ? Settings.defaults() : settings;
    this.clock = clock == null ? System::currentTimeMillis : clock;
  }

  // ----- the control surface --------------------------------------------------------------------

  @Override
  public DrainState state() {
    DrainRecord current = row;
    return current == null ? DrainState.idle() : current.toState();
  }

  @Override
  public DrainResult drain(String reason, boolean force, String requestedBy) {
    if (registry == null || store == null || leases == null) {
      return DrainResult.refused(Refusal.STANDALONE,
          "This node is standalone: there are no peers to hand its worlds and players to.",
          DrainState.idle());
    }
    DrainRecord current = row;
    if (current != null && current.phase() != DrainPhase.NONE) {
      // Idempotent by design, and ACCEPTED rather than refused: an orchestrator retrying a request it
      // is not sure landed must get the same 200 and the same state, or it will treat its own retry
      // as a failure and abort a roll that is going fine.
      return new DrainResult(true, Refusal.ALREADY,
          "This node is already draining (phase " + current.phase() + ").", current.toState());
    }

    long now = clock.getAsLong();
    if (!force && lobbySingleton()) {
      return DrainResult.refused(Refusal.LOBBY_SINGLETON,
          "This node holds LOBBY and no other live, non-draining node does. Draining it would"
              + " disconnect every player on the network, because the proxy's fallback points at the"
              + " node that is dying. Start a second lobby. --force is accepted, but it cannot"
              + " finish: nothing here ever disconnects anybody, so the worlds and matches are"
              + " handed over and the drain then STALLS with the players still on this node.",
          DrainState.idle());
    }
    if (!leases.acquire(DbNetworkLeases.DRAIN, identity.nodeId(), settings.leaseTtlMillis())) {
      String holder = leases.holder(DbNetworkLeases.DRAIN).orElse("another node");
      return DrainResult.refused(Refusal.BUSY,
          "'" + holder + "' is draining right now. Roll one node at a time: two nodes draining at"
              + " once would each hand its worlds to the other.",
          DrainState.idle());
    }

    DrainRecord fresh = new DrainRecord(identity.nodeId(), registry.epoch(), mintFence(),
        DrainPhase.ANNOUNCED, blankTo(reason, "unspecified"), blankTo(requestedBy, "console"),
        now + settings.phaseTimeoutMillis(), homedWorldCount(), 0, 0, 0, null, now, now);
    boolean inserted;
    try {
      inserted = store.insert(fresh);
    } catch (RuntimeException unwritable) {
      // The table itself is broken. Refusing BUSY here would tell the operator to "try again" about
      // a database that cannot answer, which is advice that can never work.
      leases.release(DbNetworkLeases.DRAIN, identity.nodeId());
      return DrainResult.refused(Refusal.UNAVAILABLE,
          "This node cannot record a drain: " + unwritable.getMessage(), DrainState.idle());
    }
    if (!inserted) {
      // A row already exists. It belongs to a PREVIOUS epoch of this node (the current epoch cannot
      // have one, or the in-memory check above would have caught it) that boot-time reclaim did not
      // clear -- a crash between the drain and the restart. Take it over rather than refuse: the
      // predecessor is demonstrably gone, because we are it.
      Optional<DrainRecord> stale = store.byNode(identity.nodeId());
      if (stale.isEmpty()
          || !store.reclaim(identity.nodeId(), stale.get().nodeEpoch(), fresh.nodeEpoch(),
              fresh.fence(), now)
          || !store.update(fresh)) {
        leases.release(DbNetworkLeases.DRAIN, identity.nodeId());
        return DrainResult.refused(Refusal.BUSY,
            "This node already has a drain row that could not be taken over; try again.",
            stale.map(DrainRecord::toState).orElse(DrainState.idle()));
      }
    }
    // The one-shot flags FIRST, the volatile row last. Everything here runs on the server thread now
    // (the API hops onto it), so this is belt and braces -- but publishing the row first is what made
    // the resets invisible to a reader that had already seen it, and the order costs nothing.
    offeringStarted = false;
    matchesEnded = false;
    row = fresh;

    markState(NodeRegistry.STATE_DRAINING);
    publish(drainPayload(DrainPhase.ANNOUNCED, fresh.nodeEpoch(), registryProtocol()));
    safely("announce", work::announceDrain);
    logPhase(fresh, "epoch=" + fresh.nodeEpoch() + " reason=" + fresh.reason());
    return DrainResult.accepted(fresh.toState());
  }

  @Override
  public DrainResult undrain() {
    DrainRecord current = row;
    if (current == null || current.phase() == DrainPhase.NONE) {
      return DrainResult.refused(Refusal.NOT_DRAINING, "This node is not draining.",
          DrainState.idle());
    }
    store.delete(current.nodeId(), current.nodeEpoch(), current.fence());
    leases.release(DbNetworkLeases.DRAIN, identity.nodeId());
    row = null;
    offeringStarted = false;
    matchesEnded = false;
    loggedPhase = DrainPhase.NONE;
    // Back to UP now rather than on the next heartbeat: an operator who cancels a drain and is then
    // told by `net report` that the node is still DRAINING has no way to tell the cancel worked.
    markState(NodeRegistry.STATE_UP);
    publish(drainPayload(DrainPhase.NONE, current.nodeEpoch()));
    logger.info("SX-DRAIN phase=NONE node=" + identity.nodeId() + " -- undrained, back in service");
    return DrainResult.accepted(DrainState.idle());
  }

  // ----- the machine ----------------------------------------------------------------------------

  @Override
  public void tick() {
    DrainRecord current = row;
    if (current == null || current.phase() == DrainPhase.NONE) {
      sweepStaleRows();
      return;
    }
    // Re-asserted every tick, and NOT through NodeRegistry.draining(). That method writes
    // players = 0 and worlds = 0 alongside the state, and those two columns are exactly what the
    // orchestrator's own wait condition reads -- so publishing zeroes would tell it the node was
    // drained the instant the drain STARTED, with every player still standing on it. The heartbeat
    // immediately above this call has already written the true counts; this overwrites only `state`.
    markState(NodeRegistry.STATE_DRAINING);
    // Renewed only while there is still work to do, and the ANSWER IS READ.
    //
    // False from renew means one thing: this node is not the holder any more. The lease has a TTL,
    // and a heartbeat that stops for longer than it -- a long GC, a frozen main thread, a SIGSTOPped
    // container -- lets a peer acquire it and start its own drain. Carrying on from here would be two
    // nodes draining at once, each handing its worlds to the other, which is the single failure
    // `network_leases` exists to make impossible. So this node stands down instead, loudly, and the
    // orchestrator aborts the roll rather than being told everything is fine.
    if (!terminal(current.phase())
        && !leases.renew(DbNetworkLeases.DRAIN, identity.nodeId(), settings.leaseTtlMillis())) {
      advance(current.at(DrainPhase.STALLED, "lease-lost", clock.getAsLong()));
      return;
    }

    switch (current.phase()) {
      case ANNOUNCED -> {
        // Straight through, in the SAME tick. ANNOUNCED exists so that "the drain was accepted" is
        // an observable state with its own log line and its own row, not so that a heartbeat's worth
        // of handover work is skipped after it.
        if (advance(current.at(DrainPhase.OFFERING, null, clock.getAsLong()))) {
          offering(row);
        }
      }
      case OFFERING -> offering(current);
      case QUIESCENT -> ready(current);
      // READY, STALLED and RECLAIMING are terminal for this process: the orchestrator restarts the
      // node, or an operator undrains it. Continuing to poke a stalled drain would only churn.
      default -> {
      }
    }
  }

  /**
   * Hand everything over, in the order that keeps a player's items safe.
   *
   * <p>Menus first (an open GUI whose listener is about to disappear is a duplication bug), then
   * matches, then experiences — because an experience's stop path is what captures inventories, and
   * it must run before anybody is moved.</p>
   */
  private void offering(DrainRecord current) {
    long now = clock.getAsLong();
    if (!offeringStarted) {
      offeringStarted = true;
      safely("close menus", work::closeMenus);
      queueAuthorityTarget().ifPresent(target -> safely("repoint lobby state",
          () -> work.repointLobbyState(target)));
    }

    int matches = count("matches", work::matchesInProgress);
    if (matches > 0 && !matchesEnded && now - current.startedAt() >= settings.matchGraceMillis()) {
      matchesEnded = true;
      safely("end matches", work::endMatchesForUpdate);
      matches = count("matches", work::matchesInProgress);
    }

    for (String worldKey : openWorlds()) {
      safely("hand over " + worldKey, () -> work.handOverWorld(worldKey));
    }
    safely("evacuate", work::evacuateToLobby);

    int worlds = openWorlds().size();
    int players = count("players", work::onlinePlayers);
    DrainRecord progressed = current.withCounts(worlds, players, matches, now);
    logPhase(progressed,
        "worlds=" + worlds + " players=" + players + " matches=" + matches);

    if (progressed.idleOfWork()) {
      advance(progressed.at(DrainPhase.QUIESCENT, null, now));
      return;
    }
    if (now >= current.deadline()) {
      // The one failure that is genuinely different from "slow": there is nowhere for the players to
      // go. Named separately because it is the only stall an operator can fix in one action (start a
      // lobby), and because the orchestrator keys its abort message on it.
      String detail = players > 0 && !lobbyTargetAvailable()
          ? "no-lobby-target"
          : "worlds=" + worlds + " players=" + players + " matches=" + matches;
      advance(progressed.at(DrainPhase.STALLED, detail, now));
      return;
    }
    writeThrough(progressed);
  }

  /**
   * QUIESCENT → READY, one tick later and never in the same one.
   *
   * <p>The gap is deliberate. QUIESCENT means the work is done; READY additionally means every
   * asynchronous database writer queue has been drained, and that is a blocking wait an orchestrator
   * should be able to see happening rather than have folded invisibly into the tick that reported the
   * node empty.</p>
   */
  private void ready(DrainRecord current) {
    safely("flush writers", work::flushWriters);
    advance(current.at(DrainPhase.READY, null, clock.getAsLong()));
  }

  /** Write a phase change through the fence, mirror it locally, and say so exactly once. */
  private boolean advance(DrainRecord next) {
    if (!writeThrough(next)) {
      return false;
    }
    // A terminal phase gives the network-wide lease back, here and nowhere else.
    //
    // Nothing else could. The stale sweep skips this node's own row and requires the node to be DOWN,
    // and this process goes on running -- STALLED after a failed drain, READY waiting to be
    // restarted. Renewing it forever meant one aborted roll left `network_leases['drain']` held for
    // as long as the container lived, and every later drain request ANYWHERE in the fleet was refused
    // BUSY naming a node that had long since stopped doing anything about it. READY is safe to
    // release for the same reason STALLED is: the work is finished, and the `node_drains` row -- not
    // the lease -- is what fences the restart.
    if (leases != null && terminal(next.phase())) {
      leases.release(DbNetworkLeases.DRAIN, identity.nodeId());
    }
    switch (next.phase()) {
      // OFFERING is logged from offering() instead, once its counts are real. Logging it here would
      // print worlds=0 players=0 matches=0 on the way IN, which reads exactly like QUIESCENT.
      case OFFERING -> {
      }
      case QUIESCENT -> logPhase(next, "worlds=0 players=0 matches=0");
      case READY -> logPhase(next, "-- safe to restart");
      case STALLED -> logPhase(next, "detail=" + next.detail());
      default -> logPhase(next, "");
    }
    publish(drainPayload(next.phase(), next.nodeEpoch()));
    return true;
  }

  /**
   * Persist a row through the fence.
   *
   * <p>A refused write means this process no longer owns the row — its epoch changed under it, or a
   * sweeper removed it. It stops driving the drain then and there rather than writing into a row
   * somebody else now owns, which is the entire reason the fence exists.</p>
   */
  private boolean writeThrough(DrainRecord next) {
    if (store.update(next)) {
      row = next;
      return true;
    }
    logger.warning("SX-DRAIN phase=STALLED node=" + identity.nodeId()
        + " detail=fenced-write-rejected; this node no longer owns its own drain row"
        + " (epoch " + next.nodeEpoch() + ", fence " + next.fence() + "). Standing down.");
    row = null;
    loggedPhase = DrainPhase.NONE;
    return false;
  }

  // ----- reclaim on return ----------------------------------------------------------------------

  /**
   * "I was draining when I went down. What is still mine?"
   *
   * <p>Called once, at boot, after placement reconciliation. It is a <b>query</b> and never a bus
   * replay: {@code DbNetworkBus.start()} seeds its cursor to {@code MAX(id)}, so a node that was down
   * for the whole restart sees nothing that was published while it was gone. The one source that
   * survives a restart is the table.</p>
   *
   * <p><b>Ownership comes back lazily, and that is the correct policy.</b> A world a peer adopted and
   * is actively serving stays there. Dragging it back is exactly the world-bouncing churn documented
   * against a live observed bug in {@code PlacementPlanner.replannable}, and there is nothing to win:
   * an idle world has no load to spread. What returns automatically is everything still homed here,
   * plus this node's share of new placements once it rejoins {@code choose()}.</p>
   */
  public void reclaim() {
    if (registry == null || store == null) {
      return;
    }
    Optional<DrainRecord> found = store.byNode(identity.nodeId());
    if (found.isEmpty()) {
      return;
    }
    DrainRecord previous = found.get();
    long epoch = registry.epoch();
    if (previous.nodeEpoch() == epoch) {
      // Impossible: the epoch is minted fresh from the wall clock on every boot, so a row carrying
      // this boot's epoch cannot predate this boot. Something wrote a row it had no right to.
      logger.severe("SX-RECLAIM node=" + identity.nodeId() + " found a drain row at THIS boot's epoch"
          + " (" + epoch + "), which cannot happen. Deleting it and continuing.");
      store.deleteAny(identity.nodeId());
      return;
    }
    long fence = mintFence();
    if (!store.reclaim(identity.nodeId(), previous.nodeEpoch(), epoch, fence, clock.getAsLong())) {
      // A sweeper (or a concurrent boot) got there first. Nothing to reclaim, and nothing lost: the
      // claims were released at QUIESCENT and are adoptable by whoever asks.
      return;
    }
    int kept = placements == null ? 0 : placements.placementsOn(identity.nodeId()).size();
    // What this node was home to when the drain began, minus what is still homed here. A row a peer
    // adopted has had its node_id rewritten and simply does not appear in the count above.
    int adoptedByPeers = Math.max(0, previous.worldsTotal() - kept);
    leases.release(DbNetworkLeases.DRAIN, identity.nodeId());
    store.delete(identity.nodeId(), epoch, fence);
    row = null;
    logger.info("SX-RECLAIM node=" + identity.nodeId() + " kept=" + kept
        + " adoptedByPeers=" + adoptedByPeers);
  }

  /**
   * Delete the drain rows of nodes that are DOWN and have not moved for their whole stale window, and
   * give back the drain lease they were holding.
   *
   * <p>Hygiene, not recovery — the orchestrator's own timeout is the recovery. Nothing is stranded by
   * it: a node that reached QUIESCENT released every claim it held, and one that did not still holds
   * live leases that this sweep does not touch. Rate-limited to the stale window itself, because it
   * scans a table every live node can scan.</p>
   */
  private void sweepStaleRows() {
    long now = clock.getAsLong();
    if (registry == null || store == null || now - lastSweep < settings.staleMillis()) {
      return;
    }
    lastSweep = now;
    List<NodeRegistry.Node> nodes = registry.all();
    for (DrainRecord stale : store.all()) {
      if (stale.nodeId() == null || stale.nodeId().equals(identity.nodeId())
          || now - stale.updatedAt() < settings.staleMillis()) {
        continue;
      }
      // A node with NO registry row at all is not a node known to be dead -- allMatch answers true
      // for an empty stream, so one `DELETE FROM network_nodes` (or an admin "forget node") was
      // enough to have a LIVE node's drain swept and its lease handed away underneath it.
      List<NodeRegistry.Node> rows = nodes.stream()
          .filter(node -> stale.nodeId().equals(node.nodeId()))
          .toList();
      boolean down = !rows.isEmpty()
          && rows.stream().allMatch(node -> NodeRegistry.STATE_DOWN.equals(node.state()));
      if (!down) {
        continue;
      }
      if (store.deleteAny(stale.nodeId())) {
        leases.release(DbNetworkLeases.DRAIN, stale.nodeId());
        logger.warning("Swept the stale drain row of '" + stale.nodeId() + "' (phase "
            + stale.phase() + ", last touched " + (now - stale.updatedAt()) + "ms ago) and released"
            + " the drain lease it was holding.");
      }
    }
  }

  // ----- helpers --------------------------------------------------------------------------------

  /**
   * Whether this node is the network's only lobby.
   *
   * <p>{@code velocity.toml} carries {@code try = ["lobby"]} and the proxy's fallback resolves to the
   * server literally named {@code lobby}, so draining the only one has nowhere to send anybody: the
   * players are kicked and the fallback points at the node that is dying. This is the one precondition
   * that is a stop rather than a warning.</p>
   */
  private boolean lobbySingleton() {
    return identity.can(NodeCapability.LOBBY) && !lobbyTargetAvailable();
  }

  /** A live, non-draining LOBBY node that is not this one. */
  private boolean lobbyTargetAvailable() {
    if (registry == null) {
      return false;
    }
    return registry.with(NodeCapability.LOBBY).stream()
        .anyMatch(node -> !identity.nodeId().equals(node.nodeId())
            && NodeRegistry.STATE_UP.equals(node.state()));
  }

  /** Where this node's lobby groups and queue tickets go: the least loaded live queue authority. */
  private Optional<String> queueAuthorityTarget() {
    if (registry == null) {
      return Optional.empty();
    }
    return registry.with(NodeCapability.QUEUE_AUTHORITY).stream()
        .filter(node -> !identity.nodeId().equals(node.nodeId()))
        .filter(node -> NodeRegistry.STATE_UP.equals(node.state()))
        .min(java.util.Comparator.comparingInt(NodeRegistry.Node::players)
            .thenComparing(NodeRegistry.Node::nodeId))
        .map(NodeRegistry.Node::nodeId);
  }

  /**
   * How many worlds this node is home to, recorded once at ANNOUNCE.
   *
   * <p>Distinct from {@code worlds_left}, which counts the worlds still OPEN here and is what has to
   * reach zero. Open worlds are a subset of homed worlds, so {@code worlds_left <= worlds_total}
   * always holds, and the difference at reclaim time is exactly what peers took.</p>
   */
  private int homedWorldCount() {
    return placements == null ? 0 : placements.placementsOn(identity.nodeId()).size();
  }

  private List<String> openWorlds() {
    try {
      List<String> open = work.openExperienceWorlds();
      return open == null ? List.of() : open;
    } catch (RuntimeException unavailable) {
      logger.warning("Could not read this node's open experience worlds during a drain: "
          + unavailable.getMessage());
      return List.of();
    }
  }

  private int count(String what, java.util.function.IntSupplier probe) {
    try {
      return Math.max(0, probe.getAsInt());
    } catch (RuntimeException unavailable) {
      // Counted as "still busy", never as zero. A probe that cannot answer must not be able to
      // report a node as drained; the phase timeout is the backstop.
      logger.warning("Could not count " + what + " during a drain: " + unavailable.getMessage());
      return 1;
    }
  }

  private void safely(String what, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException failed) {
      logger.warning("Drain step '" + what + "' failed: " + failed.getMessage());
    }
  }

  /**
   * Write only {@code network_nodes.state}.
   *
   * <p>Not {@code NodeRegistry.draining()}: that publishes {@code players = 0, worlds = 0} with the
   * state, and those are the columns the orchestrator polls to decide the node is empty.</p>
   */
  private void markState(String state) {
    if (store != null) {
      store.markNodeState(identity.nodeId(), state);
    }
  }

  /**
   * The advisory {@code node.drain} payload.
   *
   * <p>Built in one place so its GRAMMAR is a thing a test can pin: it is wire surface, and
   * {@code ProtocolContractTest} digests the output of these two overloads. Nothing derives state
   * from the topic -- the table is the protocol -- but a reader that does parse it must not have the
   * separators changed under it without somebody deciding that is what they meant.</p>
   */
  static String drainPayload(DrainPhase phase, long nodeEpoch) {
    return "phase=" + phase + ";epoch=" + nodeEpoch;
  }

  /** {@link #drainPayload(DrainPhase, long)} plus this build's protocol tag, sent on ANNOUNCE. */
  static String drainPayload(DrainPhase phase, long nodeEpoch, long protocol) {
    return drainPayload(phase, nodeEpoch) + ";protocol=" + protocol;
  }

  private void publish(String payload) {
    if (bus == null) {
      return;
    }
    try {
      // Advisory ONLY. Nothing subscribes to this to make a decision; the table is the protocol.
      bus.publish(NetworkBus.Topics.NODE_DRAIN, identity.nodeId(), payload);
    } catch (RuntimeException ignored) {
      // A bus that cannot publish must not stop a drain that is otherwise going fine.
    }
  }

  private void logPhase(DrainRecord record, String tail) {
    if (loggedPhase == record.phase()) {
      return;
    }
    loggedPhase = record.phase();
    String line = "SX-DRAIN phase=" + record.phase() + " node=" + identity.nodeId()
        + (tail == null || tail.isEmpty() ? "" : " " + tail);
    if (record.phase() == DrainPhase.STALLED) {
      logger.warning(line);
    } else {
      logger.info(line);
    }
  }

  private long registryProtocol() {
    return registry == null ? Protocol.UNKNOWN : registry.protocolTag().version();
  }

  /**
   * Phases this process does no more work in: only a restart, or an operator's undrain, leaves them.
   *
   * <p>The same three the {@link #tick()} switch falls through on, named once so the lease lifecycle
   * and the state machine cannot disagree about which phases are over.</p>
   */
  private static boolean terminal(DrainPhase phase) {
    return phase == DrainPhase.READY || phase == DrainPhase.STALLED
        || phase == DrainPhase.RECLAIMING;
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  /**
   * A random, non-zero fencing token — the same shape {@code DbWorldLeaseAuthority.mintFence} uses.
   *
   * <p>Zero is reserved as "no fence", so it must never be minted: a row carrying fence 0 would be
   * writable by any epoch, which is the opposite of the guarantee.</p>
   */
  private static long mintFence() {
    long fence = java.util.concurrent.ThreadLocalRandom.current().nextLong();
    return fence == 0L ? 1L : fence;
  }
}
