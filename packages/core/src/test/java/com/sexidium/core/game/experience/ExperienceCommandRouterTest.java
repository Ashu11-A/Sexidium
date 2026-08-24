package com.sexidium.core.game.experience;

import com.sexidium.core.network.ExperienceCommandStore;
import com.sexidium.core.network.NetworkBus;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.ExperienceLocator;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An owner action runs where the WORLD is, not where the owner is. (F-A2, F-A13.)
 *
 * <p>Six call sites each asked {@code games.matchByWorldKey(...)} and acted only if the match was on
 * this node — and the node an owner reaches the manage menu from is the lobby, which never holds an
 * experience world. Four of them silently did nothing. The fifth, {@code delete}, fell through to
 * removing the shared folder out from under a world a worker was actively running.</p>
 */
class ExperienceCommandRouterTest {

  private static final WorldKey KEY = WorldKey.parse("death_resets_ab12cd34");

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** Records what was applied locally. */
  private static final class Applied {
    final List<String> ops = new ArrayList<>();
    final List<Map<String, String>> args = new ArrayList<>();

    boolean record(String experienceId, ExperienceCommandRouter.Op op, Map<String, String> arguments) {
      ops.add(experienceId + ":" + op);
      args.add(arguments);
      return true;
    }
  }

  /** Records what was published. */
  private static final class Published implements NetworkBus {
    final List<String> messages = new ArrayList<>();

    @Override public void start() { }
    @Override public void publish(String topic, String key, String payload) {
      messages.add(topic + "|" + key + "|" + payload);
    }
    @Override public AutoCloseable subscribe(String topic, BusListener listener) { return () -> { }; }
    @Override public void close() { }
  }

  /** An in-memory experience_commands table, conditional UPDATEs and all. */
  private static final class Table implements ExperienceCommandStore {
    final Map<String, Command> rows = new LinkedHashMap<>();

    @Override public boolean insert(Command command) {
      return rows.putIfAbsent(command.id(), command) == null;
    }

    @Override public Optional<Command> byId(String id) {
      return Optional.ofNullable(rows.get(id));
    }

    @Override public Optional<Command> claim(String id, String nodeId) {
      Command row = rows.get(id);
      if (row == null || !nodeId.equals(row.targetNode()) || !takeable(row)) {
        return Optional.empty();
      }
      Command running = withState(row, State.RUNNING, row.detail());
      rows.put(id, running);
      return Optional.of(running);
    }

    @Override public boolean retarget(String id, String nodeId) {
      Command row = rows.get(id);
      if (row == null || row.state() != State.PENDING) {
        return false;
      }
      rows.put(id, new Command(row.id(), row.experienceId(), row.worldKey(), row.op(), row.args(),
          row.requestedBy(), nodeId, row.state(), row.detail(), row.deadline(), row.createdAt(),
          System.currentTimeMillis()));
      return true;
    }

    /** PENDING, or RUNNING and abandoned — the same window the real conditional UPDATE applies. */
    private static boolean takeable(Command row) {
      return row.state() == State.PENDING
          || (row.state() == State.RUNNING
              && row.updatedAt() < System.currentTimeMillis() - RECLAIM_AFTER_MILLIS);
    }

    @Override public boolean complete(String id, boolean ok, String detail) {
      Command row = rows.get(id);
      if (row == null || row.state() != State.RUNNING) {
        return false;
      }
      rows.put(id, withState(row, ok ? State.DONE : State.FAILED, detail));
      return true;
    }

    @Override public List<Command> pendingFor(String nodeId, long now) {
      List<Command> pending = new ArrayList<>();
      for (Command row : rows.values()) {
        if (nodeId.equals(row.targetNode()) && takeable(row)) {
          pending.add(row);
        }
      }
      return pending;
    }

    @Override public boolean touch(String id, String nodeId, long now) {
      Command row = rows.get(id);
      if (row == null || row.state() != State.RUNNING || !nodeId.equals(row.targetNode())) {
        return false;
      }
      rows.put(id, stamped(row, now));
      return true;
    }

    @Override public int expire(long now) {
      return 0;
    }

    /** Wind a row's {@code updated_at} back, which is how this protocol spells "that attempt is quiet". */
    void age(String id, long millis) {
      Command row = rows.get(id);
      rows.put(id, stamped(row, row.updatedAt() - millis));
    }

    private static Command stamped(Command row, long updatedAt) {
      return new Command(row.id(), row.experienceId(), row.worldKey(), row.op(), row.args(),
          row.requestedBy(), row.targetNode(), row.state(), row.detail(), row.deadline(),
          row.createdAt(), updatedAt);
    }

    private static Command withState(Command row, State state, String detail) {
      return new Command(row.id(), row.experienceId(), row.worldKey(), row.op(), row.args(),
          row.requestedBy(), row.targetNode(), state, detail, row.deadline(), row.createdAt(),
          System.currentTimeMillis());
    }
  }

  /**
   * A local handler that DEFERS — the shape every copy verb has: it hands the folder to a staging
   * executor, answers "started" in a boolean, and completes the request minutes later.
   */
  private static final class Defers {
    ExperienceCommandRouter router;
    ExperienceCommandRouter.Completion pending;
    int applied;

    boolean apply(String experienceId, ExperienceCommandRouter.Op op, Map<String, String> args) {
      applied++;
      pending = router.deferCurrentRequest();
      return true;
    }
  }

  private static ExperienceCommandStore.Command pending(String id, String target, String requester) {
    long now = System.currentTimeMillis();
    return new ExperienceCommandStore.Command(id, "exp-1", KEY.key(), "DELETE", "",
        requester, target, ExperienceCommandStore.State.PENDING, null, now + 60_000L, now, now);
  }

  /** A locator that has never heard of the world: nothing has ever placed it anywhere. */
  private static ExperienceLocator nowhere() {
    return new ExperienceLocator() {
      @Override public Optional<Placement> locate(WorldKey key) { return Optional.empty(); }
      @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }
      @Override public List<Placement> heldBy(String node) { return List.of(); }
    };
  }

  /** A locator that says one node holds the world, with a live or lapsed lease. */
  private static ExperienceLocator locator(String nodeId, boolean leaseHeld) {
    long expires = leaseHeld ? System.currentTimeMillis() + 60_000L : 1L;
    return new ExperienceLocator() {
      @Override public Optional<Placement> locate(WorldKey key) {
        return Optional.of(new Placement(key, "exp-1", nodeId, 7L, 42L, "LOADED", 1, expires));
      }
      @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }
      @Override public List<Placement> heldBy(String node) { return List.of(); }
    };
  }

  @Test
  @DisplayName("THE BUG: a delete from the lobby is SENT to the worker running the world")
  void aDeleteFromTheLobbyIsRouted() {
    Applied applied = new Applied();
    Published bus = new Published();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "lobby");
    router.attach(locator("worker-2", true), bus, "lobby");

    ExperienceCommandRouter.Result result =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of());

    assertTrue(result.routed(), "the lobby must not do this itself");
    assertTrue(applied.ops.isEmpty(),
        "deleting the folder here removes it out from under a world worker-2 is running — the only"
            + " guard was a LOCAL loaded-world lookup, which on the lobby answers empty");
    assertEquals(1, bus.messages.size());
    assertTrue(bus.messages.get(0).startsWith(NetworkBus.Topics.EXPERIENCE_COMMAND + "|exp-1|worker-2|DELETE"),
        bus.messages.get(0));
  }

  @Test
  @DisplayName("the holder applies what it was sent")
  void theHolderAppliesIt() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");

    router.onMessage("exp-1", "worker-2|DELETE|");

    assertEquals(List.of("exp-1:DELETE"), applied.ops);
  }

  @Test
  @DisplayName("a command addressed to ANOTHER node is ignored")
  void aCommandForAnotherNodeIsIgnored() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-3");

    router.onMessage("exp-1", "worker-2|DELETE|");

    assertTrue(applied.ops.isEmpty(),
        "every node sees every bus message; acting on one meant for a peer deletes a live world");
  }

  @Test
  @DisplayName("the node that HOLDS the world applies it directly, with no hop")
  void theHolderActsLocally() {
    Applied applied = new Applied();
    Published bus = new Published();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    router.attach(locator("worker-2", true), bus, "worker-2");

    assertTrue(router.execute("exp-1", KEY, ExperienceCommandRouter.Op.END_MATCH, Map.of()).applied());
    assertEquals(List.of("exp-1:END_MATCH"), applied.ops);
    assertTrue(bus.messages.isEmpty());
  }

  @Test
  @DisplayName("nobody holding it OPEN still means somebody holds the FOLDER, and a delete goes there")
  void anIdleWorldIsDeletedWhereItLives() {
    Applied applied = new Applied();
    Published bus = new Published();
    Table table = new Table();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "lobby");
    // A lapsed lease: the row still names worker-2, but nobody has the world open. THE BUG: this used
    // to be read as "nothing to disturb, do it here" -- and here is the lobby, which is then refused
    // by invariant I3's door and told the owner their map was gone.
    router.attach(locator("worker-2", false), bus, "lobby", table, () -> false);

    ExperienceCommandRouter.Result result =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of());

    assertTrue(result.routed(), "world_placements.node_id is a durable assignment, lease or no lease");
    assertTrue(applied.ops.isEmpty(), "the lobby may not delete an experience world");
    assertEquals(1, table.rows.size(), "the request is written down BEFORE it is announced");
    assertEquals(1, bus.messages.size());
  }

  @Test
  @DisplayName("a live edit still only chases a world somebody has OPEN")
  void aLiveEditStillNeedsALiveHolder() {
    Applied applied = new Applied();
    Published bus = new Published();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "lobby");
    router.attach(locator("worker-2", false), bus, "lobby");

    assertTrue(router.execute("exp-1", KEY, ExperienceCommandRouter.Op.SET_HARDCORE,
        Map.of("value", "true")).applied());
    assertTrue(bus.messages.isEmpty(),
        "there is no in-memory state to change, so there is nothing to send anywhere");
  }

  @Test
  @DisplayName("a world nothing has ever placed is UNROUTED, not silently deleted here")
  void aWorldWithNoHomeIsUnrouted() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "lobby");
    router.attach(nowhere(), new Published(), "lobby", new Table(), () -> false);

    ExperienceCommandRouter.Result result =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of());

    assertTrue(result.unrouted());
    assertFalse(result.ok());
    assertTrue(applied.ops.isEmpty());
  }

  @Test
  @DisplayName("the world being on the lobby itself still works: it deletes it")
  void aWorldOnACapableNodeIsDeletedThere() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    router.attach(locator("worker-2", false), new Published(), "worker-2", new Table(), () -> true);

    assertTrue(router.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of()).applied());
    assertEquals(List.of("exp-1:DELETE"), applied.ops);
  }

  @Test
  @DisplayName("a replayed delete runs ONCE: the second delivery loses the claim")
  void aReplayedDeleteRunsOnce() {
    Applied applied = new Applied();
    Table table = new Table();
    Published bus = new Published();
    ExperienceCommandRouter worker =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    worker.attach(locator("worker-2", true), bus, "worker-2", table, () -> true);
    table.rows.put("req-1", pending("req-1", "worker-2", "lobby"));

    worker.onMessage("exp-1", "worker-2|DELETE|req=req-1;from=lobby");
    worker.onMessage("exp-1", "worker-2|DELETE|req=req-1;from=lobby");

    assertEquals(List.of("exp-1:DELETE"), applied.ops,
        "every node polls the same bus and a message can arrive twice; deleting twice is data loss");
    assertEquals(ExperienceCommandStore.State.DONE, table.rows.get("req-1").state());
  }

  @Test
  @DisplayName("the answer reaches the owner: applied on the worker, reported on the lobby")
  void theAnswerComesBack() {
    Table table = new Table();
    Published lobbyBus = new Published();
    Published workerBus = new Published();
    ExperienceCommandRouter lobby = new ExperienceCommandRouter(SILENT, (a, b, c) -> true, "lobby");
    lobby.attach(locator("worker-2", true), lobbyBus, "lobby", table, () -> false);
    ExperienceCommandRouter worker = new ExperienceCommandRouter(SILENT, (a, b, c) -> true, "worker-2");
    worker.attach(locator("worker-2", true), workerBus, "worker-2", table, () -> true);

    AtomicReference<ExperienceCommandRouter.Result> answer = new AtomicReference<>();
    lobby.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of(), answer::set);
    assertEquals(null, answer.get(), "nothing has happened yet, and saying otherwise is the bug");

    worker.onMessage("exp-1", lobbyBus.messages.get(0).split("\\|", 3)[2]);
    lobby.onResult("exp-1", workerBus.messages.get(0).split("\\|", 3)[2]);

    assertTrue(answer.get().applied());
  }

  @Test
  @DisplayName("a worker that never answers leaves the request standing, and says so")
  void anUnansweredDeleteIsReportedAsQueued() {
    Table table = new Table();
    ExperienceCommandRouter lobby = new ExperienceCommandRouter(SILENT, (a, b, c) -> true, "lobby");
    lobby.attach(locator("worker-2", true), new Published(), "lobby", table, () -> false);

    AtomicReference<ExperienceCommandRouter.Result> answer = new AtomicReference<>();
    lobby.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of(), answer::set);
    lobby.tick(System.currentTimeMillis() + 60_000L);

    assertFalse(answer.get().applied(), "nothing has been confirmed");
    assertTrue(answer.get().routed(), "and nothing has been refused either -- the request stands");
    assertTrue(lobby.pendingRequestIds().isEmpty(), "the owner is told once, not on every tick");
    assertEquals(ExperienceCommandStore.State.PENDING,
        table.rows.values().iterator().next().state(),
        "the row outlives the owner's patience: the worker still runs it when it comes back");
  }

  @Test
  @DisplayName("a request published while the worker was down is found in the table at boot")
  void aLostMessageIsRecoveredFromTheTable() {
    Applied applied = new Applied();
    Table table = new Table();
    table.rows.put("req-1", pending("req-1", "worker-2", "lobby"));
    ExperienceCommandRouter worker =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    // Nothing was ever delivered to this node: DbNetworkBus seeds its cursor to MAX(id) on start, so
    // what was published while it was restarting is gone forever.
    worker.attach(locator("worker-2", true), new Published(), "worker-2", table, () -> true);

    worker.tick();

    assertEquals(List.of("exp-1:DELETE"), applied.ops);
    assertEquals(ExperienceCommandStore.State.DONE, table.rows.get("req-1").state());
  }

  @Test
  @DisplayName("standalone has no locator, so everything runs locally exactly as before")
  void standaloneRunsEverythingLocally() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "standalone");

    for (ExperienceCommandRouter.Op op : ExperienceCommandRouter.Op.values()) {
      assertTrue(router.execute("exp-1", KEY, op, Map.of()).applied());
    }
    assertEquals(ExperienceCommandRouter.Op.values().length, applied.ops.size());
  }

  @Test
  @DisplayName("arguments survive the round trip, separators and all")
  void argumentsRoundTrip() {
    Applied applied = new Applied();
    Published bus = new Published();
    ExperienceCommandRouter sender = new ExperienceCommandRouter(SILENT, (a, b, c) -> true, "lobby");
    sender.attach(locator("worker-2", true), bus, "lobby");
    // A challenge list is comma-separated and a value could contain any of the separators, so this is
    // not a hypothetical: an unescaped '=' or ';' would silently truncate the command.
    sender.execute("exp-1", KEY, ExperienceCommandRouter.Op.SET_CHALLENGES,
        Map.of("challenges", "doubledrops,break=all;odd|name"));

    ExperienceCommandRouter receiver =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    String payload = bus.messages.get(0).split("\\|", 3)[2];
    receiver.onMessage("exp-1", payload);

    assertEquals(List.of("exp-1:SET_CHALLENGES"), applied.ops);
    assertEquals("doubledrops,break=all;odd|name", applied.args.get(0).get("challenges"));
  }

  @Test
  @DisplayName("an unknown op or a malformed payload is ignored, not guessed at")
  void rubbishIsIgnored() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");

    router.onMessage("exp-1", "worker-2|EXPLODE|");
    router.onMessage("exp-1", "worker-2");
    router.onMessage("exp-1", "");
    router.onMessage(null, "worker-2|DELETE|");
    router.onMessage("exp-1", null);

    assertTrue(applied.ops.isEmpty());
  }

  @Test
  @DisplayName("a routed command reports OK, so the owner is not told their click failed")
  void aRoutedCommandReportsOk() {
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, (a, b, c) -> true, "lobby");
    router.attach(locator("worker-2", true), new Published(), "lobby");

    ExperienceCommandRouter.Result result =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.SET_HARDCORE, Map.of("value", "true"));

    assertTrue(result.ok());
    assertFalse(result.applied(), "...but it has not happened YET, and the two are different facts");
  }

  // ----- "I could not tell" is not "there is nothing there" ---------------------------------------

  @Test
  @DisplayName("a placement table that cannot be read REFUSES the delete instead of unrouting it")
  void anUnreadablePlacementTableRefuses() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "lobby");
    router.attach(unreadable(), new Published(), "lobby", new Table(), () -> false);

    ExperienceCommandRouter.Result result =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of());

    // UNROUTED is a licence: it tells the caller no folder exists anywhere, so it may drop every row
    // naming the world. A lock-wait timeout on the shared MariaDB would cash that in over a world
    // sitting intact on a worker's disk -- owner told "deleted", folder orphaned forever.
    assertFalse(result.unrouted(), "a failed read must never be mistaken for an empty table");
    assertFalse(result.ok());
    assertTrue(applied.ops.isEmpty());
  }

  @Test
  @DisplayName("a DRAINING worker that holds the world refuses, and is not read as 'nobody holds it'")
  void aDrainingHolderRefusesRatherThanUnrouting() {
    Applied applied = new Applied();
    ExperienceCommandRouter router =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    // The recorded home IS this node, and it may not open experience worlds right now (draining).
    router.attach(locator("worker-2", false), new Published(), "worker-2", new Table(), () -> false);

    ExperienceCommandRouter.Result result =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.DELETE, Map.of());

    assertFalse(result.unrouted(),
        "a node IS recorded as holding it -- saying otherwise makes the caller forget the rows for a"
            + " folder that very much exists");
    assertFalse(result.ok());
    assertTrue(applied.ops.isEmpty());
  }

  // ----- the target is resolved when the owner clicks; the world can move afterwards ---------------

  @Test
  @DisplayName("THE BUG: a world adopted by another node while the request waited is NOT deleted here")
  void aWorldThatMovedIsReaddressedRatherThanDeleted() {
    Applied applied = new Applied();
    Table table = new Table();
    ExperienceCommandRouter worker =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    // The row was addressed to worker-2 when the owner clicked. worker-2 was then down for a rolling
    // update long enough for worker-3 to adopt the idle world and open it -- `claim()` rewrites
    // node_id. worker-2 boots and drains its own table.
    worker.attach(locator("worker-3", true), new Published(), "worker-2", table, () -> true);
    table.rows.put("req-1", pending("req-1", "worker-2", "lobby"));

    worker.tick(System.currentTimeMillis());

    assertTrue(applied.ops.isEmpty(),
        "deleting on the strength of a frozen address removes the shared folder out from under the"
            + " live world worker-3 is running -- F-A2 again, moved from the lobby to a stale worker");
    assertEquals("worker-3", table.rows.get("req-1").targetNode(),
        "and the request is not lost either: it is re-addressed to whoever holds the world now");
    assertEquals(ExperienceCommandStore.State.PENDING, table.rows.get("req-1").state());
  }

  @Test
  @DisplayName("a node that may not open experience worlds leaves the request on the table")
  void anIncapableTargetLeavesTheRequestStanding() {
    Applied applied = new Applied();
    Table table = new Table();
    ExperienceCommandRouter worker =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    worker.attach(locator("worker-2", true), new Published(), "worker-2", table, () -> false);
    table.rows.put("req-1", pending("req-1", "worker-2", "lobby"));

    // Delivered by the bus, which does not consult the drain's capability check.
    worker.onMessage("exp-1", "worker-2|DELETE|req=req-1;from=lobby");

    assertTrue(applied.ops.isEmpty());
    assertEquals(ExperienceCommandStore.State.PENDING, table.rows.get("req-1").state(),
        "claiming it here ends the live match and is then refused by invariant I3's door, leaving a"
            + " terminal FAILED nothing retries -- while the owner was told the request stands");
  }

  @Test
  @DisplayName("a request abandoned mid-run by a node that died is picked up again, not lost")
  void anAbandonedRunningRequestIsReclaimed() {
    Applied applied = new Applied();
    Table table = new Table();
    ExperienceCommandRouter worker =
        new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    worker.attach(locator("worker-2", true), new Published(), "worker-2", table, () -> true);
    // Claimed, and then the JVM was killed mid-delete. RUNNING is true forever with nobody running it.
    ExperienceCommandStore.Command row = pending("req-1", "worker-2", "lobby");
    long longAgo = System.currentTimeMillis() - ExperienceCommandStore.RECLAIM_AFTER_MILLIS - 1_000L;
    table.rows.put("req-1", new ExperienceCommandStore.Command(row.id(), row.experienceId(),
        row.worldKey(), row.op(), row.args(), row.requestedBy(), row.targetNode(),
        ExperienceCommandStore.State.RUNNING, null, row.deadline(), row.createdAt(), longAgo));

    worker.tick(System.currentTimeMillis());

    assertEquals(List.of("exp-1:DELETE"), applied.ops,
        "RUNNING was invisible to both the drain and the expiry sweep, so the delete was lost while"
            + " the owner had already been told it would happen");
    assertEquals(ExperienceCommandStore.State.DONE, table.rows.get("req-1").state());
  }

  @Test
  @DisplayName("THE BACKUP THAT SUCCEEDED AND WAS RECORDED FAILED: a deferred request is held open")
  void aDeferredRequestIsHeldOpenWhileTheWorkRuns() {
    // The sequence this pins, which the deferral itself created: the row now stays RUNNING for the
    // whole of a copy instead of being marked DONE the moment it started. `updated_at` was stamped
    // once, by the claim, and `pendingFor` hands back RUNNING rows older than RECLAIM_AFTER_MILLIS --
    // including this node's OWN. A 290 MB tree on shared storage outruns five minutes, so the same
    // node re-ran its own request, was refused BUSY by the claim the copy still holds, and marked the
    // row terminally FAILED -- while the copy went on to succeed and could no longer complete it.
    Defers handler = new Defers();
    Table table = new Table();
    Published bus = new Published();
    ExperienceCommandRouter worker = new ExperienceCommandRouter(SILENT, handler::apply, "worker-2");
    handler.router = worker;
    worker.attach(locator("worker-2", true), bus, "worker-2", table, () -> true);
    table.rows.put("req-1", pending("req-1", "worker-2", "lobby"));
    long start = System.currentTimeMillis();

    worker.tick(start);
    assertEquals(1, handler.applied, "the drain takes it and the handler defers");
    assertEquals(ExperienceCommandStore.State.RUNNING, table.rows.get("req-1").state(),
        "RUNNING is what it IS: the bytes are still moving");

    // Five minutes of copying, with nothing renewing the row.
    table.age("req-1", ExperienceCommandStore.RECLAIM_AFTER_MILLIS + 1_000L);
    assertFalse(table.pendingFor("worker-2", start).isEmpty(),
        "the fixture, and the regression: the row is reclaimable, by the very node still running it");

    worker.tick(start + 6_000L);

    assertEquals(1, handler.applied,
        "the heartbeat re-stamped it, so this node did not hand its own in-flight copy back to"
            + " itself -- the second run is what marked the row FAILED under the first one");
    assertEquals(ExperienceCommandStore.State.RUNNING, table.rows.get("req-1").state());

    // And the copy lands, minutes later, into a row that is still its own to answer for.
    handler.pending.complete(true, "CREATED");
    assertEquals(ExperienceCommandStore.State.DONE, table.rows.get("req-1").state(),
        "the durable record and the disk agree, which is the whole point of the row");
    assertEquals(1, bus.messages.size(), "and the owner's node is told");
  }

  @Test
  @DisplayName("an answer for a row we no longer own is dropped on the BUS too, not just in the table")
  void aLostClaimIsNotAnnounced() {
    // Two nodes sharing an SX_NODE id during a rolling update drain the same rows, so an attempt CAN
    // lose its row to another one that is now running it. `complete` refuses -- it matches on RUNNING
    // -- and the publish used to fire regardless. onResult hands the FIRST answer that arrives to the
    // waiting caller and forgets the request, so a loser's stale "declined" could be the sentence the
    // owner reads about an operation the winner finished successfully.
    Defers handler = new Defers();
    Table table = new Table();
    Published bus = new Published();
    ExperienceCommandRouter worker = new ExperienceCommandRouter(SILENT, handler::apply, "worker-2");
    handler.router = worker;
    worker.attach(locator("worker-2", true), bus, "worker-2", table, () -> true);
    table.rows.put("req-1", pending("req-1", "worker-2", "lobby"));

    worker.tick(System.currentTimeMillis());
    // The other attempt took it and answered it while this one was still copying.
    ExperienceCommandStore.Command row = table.rows.get("req-1");
    table.rows.put("req-1", new ExperienceCommandStore.Command(row.id(), row.experienceId(),
        row.worldKey(), row.op(), row.args(), row.requestedBy(), row.targetNode(),
        ExperienceCommandStore.State.DONE, "applied on 'worker-2'", row.deadline(), row.createdAt(),
        System.currentTimeMillis()));
    bus.messages.clear();

    handler.pending.complete(false, "BUSY");

    assertTrue(bus.messages.isEmpty(),
        "losing the claim is losing the right to answer, on both channels or on neither");
    assertEquals(ExperienceCommandStore.State.DONE, table.rows.get("req-1").state(),
        "and the answer that DID win stands");
  }

  /** A locator whose table cannot be read at all — not one that answers "no such world". */
  private static ExperienceLocator unreadable() {
    return new ExperienceLocator() {
      @Override public Optional<Placement> locate(WorldKey key) { return Optional.empty(); }
      @Override public Home home(WorldKey key) { return Home.unknown(); }
      @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }
      @Override public List<Placement> heldBy(String node) { return List.of(); }
    };
  }

  // ===== heldElsewhere: the only question a node cannot answer about itself =====================
  //
  // The backup verbs route on the SOURCE world's key, so every local "is it busy" answer is about that
  // world and about this node. The world on the other end of a restore or a refresh -- the COPY -- is
  // addressed by nobody, and the three things that used to speak for it are all node-local: the loaded
  // set, the closing set and the match map. The fourth, "the folder is there", is a symlink into shared
  // storage on the live deployment and is therefore true on every node for everything.

  @Test
  @DisplayName("another node holding the world with a live lease: yes, it is elsewhere")
  void aLiveLeaseOnAnotherNodeIsHeldElsewhere() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, new Applied()::record, "worker-1");
    router.attach(locator("worker-2", true), new Published(), "worker-1");

    assertTrue(router.heldElsewhere(KEY),
        "worker-2 has it open or is still flushing an unload; deleting or re-pointing it from here is"
            + " the data loss this whole seam exists to stop");
  }

  @Test
  @DisplayName("OUR OWN placement is not 'elsewhere' — the local questions answer for this node")
  void ourOwnPlacementIsNotElsewhere() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, new Applied()::record, "worker-2");
    router.attach(locator("worker-2", true), new Published(), "worker-2");

    assertFalse(router.heldElsewhere(KEY),
        "this node knows more about its own worlds than the placement table does, and folding its own"
            + " row in here would refuse every verb on every world it holds");
  }

  @Test
  @DisplayName("a holder whose lease has LAPSED cannot freeze the world forever")
  void aDeadHolderDoesNotDeadlockTheVerb() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, new Applied()::record, "worker-1");
    router.attach(locator("worker-2", false), new Published(), "worker-1");

    assertFalse(router.heldElsewhere(KEY),
        "the lease is renewed every few seconds for as long as the world is open AND for the whole of"
            + " an unload-with-save, so a lapsed one means the holder is gone -- and a node that is"
            + " gone is not writing to that folder. The other fold would let one crashed worker make a"
            + " backup untouchable by anybody, forever.");
  }

  @Test
  @DisplayName("nothing has ever placed it: nobody holds it")
  void anUnplacedWorldIsHeldByNobody() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, new Applied()::record, "worker-1");
    router.attach(nowhere(), new Published(), "worker-1");

    assertFalse(router.heldElsewhere(KEY),
        "a world only ever gets a placement row by being opened, so no row is no holder");
  }

  @Test
  @DisplayName("a placement that cannot be READ refuses: 'I do not know' is not 'nobody'")
  void anUnreadablePlacementRefuses() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, new Applied()::record, "worker-1");
    router.attach(unreadable(), new Published(), "worker-1");

    assertTrue(router.heldElsewhere(KEY),
        "a lock-wait timeout on the shared MariaDB must not read as an empty table; the caller only"
            + " ever asks this in order to refuse, and a refusal is a click the owner repeats");
  }

  @Test
  @DisplayName("a locator that THROWS refuses too, and does not take the caller down with it")
  void aThrowingLocatorRefuses() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, new Applied()::record, "worker-1");
    router.attach(new ExperienceLocator() {
      @Override public Optional<Placement> locate(WorldKey key) { throw new IllegalStateException("down"); }
      @Override public Home home(WorldKey key) { throw new IllegalStateException("down"); }
      @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }
      @Override public List<Placement> heldBy(String node) { return List.of(); }
    }, new Published(), "worker-1");

    assertTrue(router.heldElsewhere(KEY));
  }

  @Test
  @DisplayName("no locator at all is standalone: there is no elsewhere to be held in")
  void standaloneHasNoElsewhere() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, new Applied()::record, "standalone");

    assertFalse(router.heldElsewhere(KEY),
        "one server holds every world it has, and its local checks are the complete truth; refusing"
            + " here would turn restore and refresh off on every single-node install");
  }
}
