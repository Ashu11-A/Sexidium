package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drain state machine, driven by hand.
 *
 * <p>Every transition here is {@code given(phase, counts, clock) -> expect(phase, log, row)} with
 * <b>no sleeping anywhere</b>. That is the whole reason {@link DrainCoordinator} owns no timer: the
 * production scheduler calls {@code tick()} from the network heartbeat, and a test calls it directly
 * with a clock it advances itself. A drain protocol that could only be tested by waiting would be a
 * drain protocol whose timeout paths were never tested at all.</p>
 *
 * <p>The database here is real (one temp SQLite file, several "nodes" against it, the same shape
 * {@code NodeRegistryTest} already uses), because the fence and the lease ARE the protocol — testing
 * them against a fake store would test the fake.</p>
 */
class DrainCoordinatorTest {

  private static final long TIMEOUT = 30_000L;

  /** Captures the SX- lines, which are a frozen contract an orchestrator greps. */
  private static final class CapturingLogger implements LoggerAdapter {
    final List<String> lines = new ArrayList<>();

    @Override public void info(String message) {
      lines.add(message);
    }

    @Override public void warning(String message) {
      lines.add(message);
    }

    @Override public void severe(String message) {
      lines.add(message);
    }

    @Override public void warning(String message, Throwable throwable) {
      lines.add(message);
    }

    @Override public void severe(String message, Throwable throwable) {
      lines.add(message);
    }

    boolean sawPhase(String phase) {
      return lines.stream().anyMatch(line -> line.startsWith("SX-DRAIN phase=" + phase + " "));
    }

    String line(String phase) {
      return lines.stream().filter(l -> l.startsWith("SX-DRAIN phase=" + phase + " "))
          .findFirst().orElse("");
    }
  }

  /** A hand-driven server: nothing here does anything, it just reports numbers the test sets. */
  private static final class FakeWork implements DrainWorkPort {
    int players;
    int matches;
    final List<String> worlds = new ArrayList<>();
    int announces;
    int menuCloses;
    int matchEnds;
    final List<String> handedOver = new ArrayList<>();
    int evacuations;
    int flushes;
    String repointedTo;

    @Override public int onlinePlayers() {
      return players;
    }

    @Override public void announceDrain() {
      announces++;
    }

    @Override public void closeMenus() {
      menuCloses++;
    }

    @Override public int matchesInProgress() {
      return matches;
    }

    @Override public void endMatchesForUpdate() {
      matchEnds++;
      matches = 0;
    }

    @Override public List<String> openExperienceWorlds() {
      return List.copyOf(worlds);
    }

    @Override public void handOverWorld(String worldKey) {
      handedOver.add(worldKey);
      worlds.remove(worldKey);
    }

    @Override public void evacuateToLobby() {
      evacuations++;
    }

    @Override public void repointLobbyState(String targetNodeId) {
      repointedTo = targetNodeId;
    }

    @Override public void flushWriters() {
      flushes++;
    }
  }

  @TempDir
  Path tmp;

  private Database database;
  private final AtomicLong now = new AtomicLong(5_000_000L);
  private CapturingLogger logger;
  private DbDrainStore store;
  private DbNetworkLeases leases;
  private FakeWork work;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "drain.db"));
    logger = new CapturingLogger();
    store = new DbDrainStore(database, logger);
    leases = new DbNetworkLeases(database, logger, now::get);
    work = new FakeWork();
  }

  private NodeIdentity identity(String nodeId, String role) {
    return NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole(role));
  }

  private NodeRegistry registry(String nodeId, String role) {
    return new NodeRegistry(database, logger, identity(nodeId, role), TIMEOUT);
  }

  /** A live peer, so the network is not a network of one. */
  private void peer(String nodeId, String role, int players) {
    registry(nodeId, role).heartbeat(players, 0);
  }

  private DrainCoordinator coordinator(String nodeId, String role, DrainWorkPort port) {
    NodeRegistry registry = registry(nodeId, role);
    registry.heartbeat(0, 0);
    return new DrainCoordinator(identity(nodeId, role), registry, store, leases, null, port, null,
        logger, DrainCoordinator.Settings.defaults(), now::get);
  }

  private DrainCoordinator worker() {
    peer("lobby", "lobby", 3);
    return coordinator("worker-1", "worker", work);
  }

  // ----- the happy path -------------------------------------------------------------------------

  @Test
  @DisplayName("an empty worker goes ANNOUNCED -> OFFERING -> QUIESCENT -> READY, one tick each")
  void happyPath() {
    DrainCoordinator drain = worker();

    assertTrue(drain.drain("rolling-update", false, "api").accepted());
    assertEquals(DrainPhase.ANNOUNCED, drain.state().phase());
    assertEquals(1, work.announces, "players are told BEFORE anything moves");
    assertTrue(logger.line("ANNOUNCED").contains("reason=rolling-update"));

    drain.tick();
    assertEquals(1, work.menuCloses, "menus close first: an orphaned GUI is a duplication bug");
    assertEquals(DrainPhase.QUIESCENT, drain.state().phase(),
        "ANNOUNCED runs OFFERING in the SAME tick, and an empty node has nothing to offer -- so it"
            + " quiesces immediately rather than burning a whole heartbeat announcing to nobody");

    drain.tick();
    assertEquals(DrainPhase.READY, drain.state().phase());
    assertEquals(1, work.flushes, "READY means the writer queues are drained, not just that the"
        + " players are gone");
    assertTrue(logger.line("READY").contains("safe to restart"));
  }

  @Test
  @DisplayName("worlds and players are handed over before the drain reports itself quiesced")
  void handsOverWorldsAndPlayersFirst() {
    DrainCoordinator drain = worker();
    work.worlds.add("experiences/alpha_ab12");
    work.worlds.add("experiences/beta_cd34");
    work.players = 4;

    drain.drain("rolling-update", false, "api");
    drain.tick();

    assertEquals(List.of("experiences/alpha_ab12", "experiences/beta_cd34"), work.handedOver);
    assertTrue(work.evacuations > 0);
    assertEquals(DrainPhase.OFFERING, drain.state().phase(),
        "players are still here, so this is NOT quiesced however empty the world list is");
    assertEquals(4, drain.state().playersLeft());

    work.players = 0;
    drain.tick();
    assertEquals(DrainPhase.QUIESCENT, drain.state().phase());
  }

  // ----- the refusals ---------------------------------------------------------------------------

  @Test
  @DisplayName("a standalone node refuses: there are no peers to hand anything to")
  void standaloneRefuses() {
    DrainCoordinator drain = new DrainCoordinator(NodeIdentity.standalone(), null, null, null, null,
        work, null, logger, DrainCoordinator.Settings.defaults(), now::get);

    DrainControlPort.DrainResult result = drain.drain("rolling-update", false, "console");
    assertFalse(result.accepted());
    assertEquals(DrainControlPort.Refusal.STANDALONE, result.refusal());
  }

  @Test
  @DisplayName("the ONLY lobby refuses: draining it disconnects the whole network")
  void lobbySingletonRefuses() {
    DrainCoordinator drain = coordinator("lobby", "lobby", work);

    DrainControlPort.DrainResult result = drain.drain("rolling-update", false, "console");
    assertFalse(result.accepted());
    assertEquals(DrainControlPort.Refusal.LOBBY_SINGLETON, result.refusal());
    assertTrue(result.detail().contains("disconnect"),
        "the refusal has to SAY what forcing it costs, or --force reads like a retry flag");
  }

  @Test
  @DisplayName("--force overrides LOBBY_SINGLETON")
  void lobbySingletonIsOverridable() {
    assertTrue(coordinator("lobby", "lobby", work).drain("x", true, "console").accepted());
  }

  @Test
  @DisplayName("a second live lobby removes the need to force at all")
  void aSecondLobbyRemovesTheSingletonRefusal() {
    peer("lobby-2", "lobby", 0);
    assertTrue(coordinator("lobby-1", "lobby", work).drain("x", false, "console").accepted(),
        "with another live lobby there is somewhere to send everyone");
  }

  @Test
  @DisplayName("a second node draining at once is refused BUSY and told who holds the lease")
  void twoNodesCannotDrainAtOnce() {
    peer("lobby", "lobby", 1);
    assertTrue(coordinator("worker-1", "worker", work).drain("x", false, "api").accepted());

    DrainControlPort.DrainResult second =
        coordinator("worker-2", "worker", new FakeWork()).drain("x", false, "api");
    assertFalse(second.accepted());
    assertEquals(DrainControlPort.Refusal.BUSY, second.refusal());
    assertTrue(second.detail().contains("worker-1"));
  }

  @Test
  @DisplayName("a repeated drain request is ACCEPTED and idempotent, not a 409")
  void repeatedDrainIsIdempotent() {
    DrainCoordinator drain = worker();
    work.players = 2;   // still busy, so there is a mid-drain phase to be idempotent about
    drain.drain("rolling-update", false, "api");
    drain.tick();

    DrainControlPort.DrainResult again = drain.drain("rolling-update", false, "api");
    assertTrue(again.accepted(), "an orchestrator retrying a request it is unsure landed must not"
        + " read its own retry as a failure and abort a roll that is going fine");
    assertEquals(DrainControlPort.Refusal.ALREADY, again.refusal());
    assertEquals(DrainPhase.OFFERING, again.state().phase());
    assertEquals(1, work.announces, "and it must not tell everybody twice");
  }

  @Test
  @DisplayName("undrain on a node that is not draining is refused NOT_DRAINING")
  void undrainWithoutDrainRefuses() {
    DrainControlPort.DrainResult result = worker().undrain();
    assertFalse(result.accepted());
    assertEquals(DrainControlPort.Refusal.NOT_DRAINING, result.refusal());
  }

  // ----- undrain, from every phase --------------------------------------------------------------

  @Test
  @DisplayName("undrain works from ANNOUNCED, OFFERING, QUIESCENT and READY, and frees the lease")
  void undrainFromEveryPhase() {
    for (int ticksBefore = 0; ticksBefore <= 3; ticksBefore++) {
      setUpFresh();
      DrainCoordinator drain = worker();
      drain.drain("rolling-update", false, "api");
      for (int tick = 0; tick < ticksBefore; tick++) {
        drain.tick();
      }
      DrainPhase reached = drain.state().phase();

      assertTrue(drain.undrain().accepted(), "undrain must work from " + reached);
      assertEquals(DrainPhase.NONE, drain.state().phase());
      assertTrue(store.byNode("worker-1").isEmpty(), "the row goes away with the drain");
      assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(),
          "an undrained node that kept the lease would block every other node's roll from " + reached);
      assertTrue(drain.drain("again", false, "api").accepted(),
          "and the node has to be drainable again afterwards");
    }
  }

  private void setUpFresh() {
    try {
      setUp();
    } catch (Exception failed) {
      throw new IllegalStateException(failed);
    }
  }

  // ----- the failures -----------------------------------------------------------------------------

  @Test
  @DisplayName("matches are left alone through their grace, then force-ended once")
  void matchesAreHeldThenForceEndedAtGrace() {
    DrainCoordinator drain = worker();
    work.matches = 2;

    drain.drain("rolling-update", false, "api");
    drain.tick();
    assertEquals(0, work.matchEnds, "a live round is not thrown away the instant a roll starts");
    assertEquals(2, drain.state().matchesLeft());

    now.addAndGet(DrainCoordinator.Settings.DEFAULT_MATCH_GRACE_SECONDS * 1000L);
    drain.tick();
    assertEquals(1, work.matchEnds);
    assertEquals(DrainPhase.QUIESCENT, drain.state().phase());
  }

  @Test
  @DisplayName("nowhere to send the players: the drain STALLS, and nobody is disconnected")
  void stallsWithNoLobbyTarget() {
    // A worker alone in the world: no lobby node, so there is nowhere for its players to go.
    DrainCoordinator drain = coordinator("worker-1", "worker", work);
    work.players = 6;

    drain.drain("rolling-update", false, "api");
    drain.tick();
    assertEquals(DrainPhase.OFFERING, drain.state().phase());

    now.addAndGet(DrainCoordinator.Settings.DEFAULT_PHASE_TIMEOUT_SECONDS * 1000L + 1L);
    drain.tick();

    assertEquals(DrainPhase.STALLED, drain.state().phase());
    assertEquals("no-lobby-target", drain.state().detail(),
        "the one stall an operator can fix in a single action has to be named as itself");
    assertEquals(6, work.players, "a drain that cannot find a home ABORTS; it never pushes anyone off");
    assertTrue(logger.sawPhase("STALLED"));
  }

  @Test
  @DisplayName("a slow drain that still has somewhere to go stalls with its counts, not no-lobby-target")
  void stallsWithCountsWhenALobbyExists() {
    DrainCoordinator drain = worker();
    work.worlds.add("experiences/stuck_ab12");
    // A world that refuses to close: handOverWorld does nothing, so it is still open next tick.
    DrainWorkPort stubborn = new DrainWorkPort() {
      @Override public List<String> openExperienceWorlds() {
        return List.of("experiences/stuck_ab12");
      }
    };
    drain = new DrainCoordinator(identity("worker-1", "worker"), registry("worker-1", "worker"),
        store, leases, null, stubborn, null, logger, DrainCoordinator.Settings.defaults(), now::get);

    drain.drain("rolling-update", false, "api");
    drain.tick();
    now.addAndGet(DrainCoordinator.Settings.DEFAULT_PHASE_TIMEOUT_SECONDS * 1000L + 1L);
    drain.tick();

    assertEquals(DrainPhase.STALLED, drain.state().phase());
    assertTrue(drain.state().detail().contains("worlds=1"));
    assertNotEquals("no-lobby-target", drain.state().detail());
  }

  @Test
  @DisplayName("a fenced write rejected after the row changed hands makes this node stand down")
  void fencedWriteRejectedAfterEpochChange() {
    DrainCoordinator drain = worker();
    work.players = 2;   // still busy, so the drain stays in OFFERING and keeps writing
    drain.drain("rolling-update", false, "api");
    drain.tick();
    assertEquals(DrainPhase.OFFERING, drain.state().phase());

    // A successor booted and took the row over: same node id, a new epoch and a new fence.
    DrainRecord mine = store.byNode("worker-1").orElseThrow();
    assertTrue(store.reclaim("worker-1", mine.nodeEpoch(), mine.nodeEpoch() + 1, 4242L, now.get()));

    drain.tick();

    assertEquals(DrainPhase.NONE, drain.state().phase(),
        "this process no longer owns its own drain row and must stop writing into it");
    assertEquals(DrainPhase.RECLAIMING, store.byNode("worker-1").orElseThrow().phase(),
        "and must not have clobbered what the successor wrote");
  }

  @Test
  @DisplayName("the drain row of a DOWN node is swept, and the drain lease it held is given back")
  void sweepsStaleRowsOfDownNodes() {
    // worker-2 drains, then dies without ever coming back.
    peer("lobby", "lobby", 1);
    DrainCoordinator dying = coordinator("worker-2", "worker", new FakeWork());
    assertTrue(dying.drain("rolling-update", false, "api").accepted());
    assertEquals("worker-2", leases.holder(DbNetworkLeases.DRAIN).orElseThrow());

    // The row goes stale on the injected clock; the registry runs on the wall clock, so worker-2 is
    // declared DOWN through a reaper with a zero timeout rather than by waiting thirty real seconds.
    now.addAndGet(DrainCoordinator.Settings.DEFAULT_STALE_SECONDS * 1000L + 1L);
    new NodeRegistry(database, logger, identity("worker-1", "worker"), -1L).reapStaleNodes();

    DrainCoordinator survivor = coordinator("worker-1", "worker", work);
    survivor.tick();

    assertTrue(store.byNode("worker-2").isEmpty());
    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(),
        "a dead node holding the drain lease forever would block every future roll");
  }

  @Test
  @DisplayName("a live node's drain row is never swept, however long it takes")
  void doesNotSweepALiveDrain() {
    peer("lobby", "lobby", 1);
    DrainCoordinator slow = coordinator("worker-2", "worker", new FakeWork());
    assertTrue(slow.drain("rolling-update", false, "api").accepted());

    now.addAndGet(DrainCoordinator.Settings.DEFAULT_STALE_SECONDS * 1000L + 1L);
    // worker-2 is slow, not dead: it is still checking in.
    registry("worker-2", "worker").heartbeat(3, 1);

    coordinator("worker-1", "worker", work).tick();
    assertTrue(store.byNode("worker-2").isPresent());
  }

  @Test
  @DisplayName("the node row stays DRAINING with its REAL counts, so an orchestrator is not lied to")
  void publishesDrainingStateWithoutZeroingTheCounts() {
    DrainCoordinator drain = worker();
    work.players = 7;
    drain.drain("rolling-update", false, "api");

    // The heartbeat writes the true counts; the drain overwrites only `state` on top of them.
    registry("worker-1", "worker").heartbeat(7, 2);
    drain.tick();

    NodeRegistry.Node row = registry("reader", "worker").all().stream()
        .filter(node -> "worker-1".equals(node.nodeId())).findFirst().orElseThrow();
    assertEquals(NodeRegistry.STATE_DRAINING, row.state());
    assertEquals(7, row.players(), "publishing players=0 at the START of a drain would tell the"
        + " orchestrator the node was empty with seven people standing on it");
    assertEquals(2, row.worlds());
  }

  @Test
  @DisplayName("lobby groups are repointed once, at the start of OFFERING")
  void repointsLobbyGroupsOnce() {
    peer("lobby", "lobby", 3);
    DrainCoordinator drain = coordinator("worker-1", "worker", work);
    // Players that never leave, so the drain SITS in OFFERING across every tick below. Without them
    // the node quiesced on the first tick and offering() ran exactly once whether or not the one-shot
    // guard existed -- the assertion below could not fail, which a mutation confirmed: removing the
    // guard from production left this test green. In production a drain holds OFFERING for up to
    // 180 s at a 5 s heartbeat, i.e. ~36 ticks, each of which would close every player's menu again.
    work.players = 3;
    drain.drain("rolling-update", false, "api");
    drain.tick();
    drain.tick();
    drain.tick();

    assertEquals(DrainPhase.OFFERING, drain.state().phase(), "the scenario has to STAY in OFFERING,"
        + " or it does not exercise the guard at all");
    assertEquals("lobby", work.repointedTo);
    assertEquals(1, work.menuCloses, "the one-shot OFFERING work must not repeat every tick");
  }

  // ----- the network-wide drain lease --------------------------------------------------------------

  @Test
  @DisplayName("losing the drain lease stands this node down instead of draining alongside its taker")
  void aLostLeaseStandsTheNodeDown() {
    DrainCoordinator drain = worker();
    work.players = 2;   // still busy, so the drain stays in OFFERING and keeps renewing
    assertTrue(drain.drain("rolling-update", false, "api").accepted());
    drain.tick();
    assertEquals(DrainPhase.OFFERING, drain.state().phase());

    // This node stopped checking in for longer than the lease TTL -- a long GC, a frozen main
    // thread -- and worker-2 acquired it and started its own drain.
    now.addAndGet(DrainCoordinator.Settings.DEFAULT_LEASE_SECONDS * 1000L + 1L);
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-2",
        DrainCoordinator.Settings.DEFAULT_LEASE_SECONDS * 1000L));

    drain.tick();

    assertEquals(DrainPhase.STALLED, drain.state().phase(),
        "two nodes draining at once each hand their worlds to the other; the renewal's answer is the"
            + " only place that can be found out");
    assertEquals("lease-lost", drain.state().detail());
    assertEquals("worker-2", leases.holder(DbNetworkLeases.DRAIN).orElseThrow(),
        "and standing down must not take the lease away from the node that now holds it");
  }

  @Test
  @DisplayName("a READY node gives the drain lease back rather than holding it until it restarts")
  void readyReleasesTheDrainLease() {
    DrainCoordinator drain = worker();
    drain.drain("rolling-update", false, "api");
    drain.tick();
    drain.tick();

    assertEquals(DrainPhase.READY, drain.state().phase());
    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(),
        "the work is done; the node_drains row is the fence from here, not the lease");

    // And it STAYS ready. Without the terminal-phase guard on the renew, the very lease this node
    // just released on purpose fails to renew on the next heartbeat, the node reads that as
    // "someone took the lease from me" and declares itself STALLED — aborting a roll that had
    // already succeeded. The assertion above cannot see it: the lease is free either way, which is
    // exactly why that mutation survived the whole suite.
    drain.tick();
    assertEquals(DrainPhase.READY, drain.state().phase(),
        "a terminal phase must not be re-STALLED by the failed renew of a lease it gave back");
  }

  @Test
  @DisplayName("a STALLED node gives the drain lease back, so one aborted roll does not block the fleet")
  void stalledReleasesTheDrainLease() {
    DrainCoordinator drain = coordinator("worker-1", "worker", work);
    work.players = 6;   // and no lobby node exists, so this drain can only ever stall

    drain.drain("rolling-update", false, "api");
    drain.tick();
    now.addAndGet(DrainCoordinator.Settings.DEFAULT_PHASE_TIMEOUT_SECONDS * 1000L + 1L);
    drain.tick();
    assertEquals(DrainPhase.STALLED, drain.state().phase());

    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(),
        "nothing else can release it: the sweep skips this node's own row and requires it to be DOWN,"
            + " and this node is very much alive -- so every later drain anywhere in the fleet was"
            + " refused BUSY until a human undrained a node that had already given up");
    // And the node is still STALLED: giving the lease back is not the same as forgetting the drain.
    assertEquals(DrainPhase.STALLED, store.byNode("worker-1").orElseThrow().phase());
  }

  @Test
  @DisplayName("a drain row whose node has NO registry row at all is not swept")
  void doesNotSweepANodeItKnowsNothingAbout() {
    peer("lobby", "lobby", 1);
    assertTrue(coordinator("worker-2", "worker", new FakeWork()).drain("x", false, "api").accepted());
    // Whatever removed worker-2's registry row -- a DELETE, an admin "forget node" -- did not make
    // worker-2 dead. allMatch over an empty stream answers true, so the sweep took a LIVE node's
    // drain row and gave its lease away underneath it.
    forgetNodeRow("worker-2");
    now.addAndGet(DrainCoordinator.Settings.DEFAULT_STALE_SECONDS * 1000L + 1L);

    coordinator("worker-1", "worker", work).tick();

    assertTrue(store.byNode("worker-2").isPresent(),
        "nothing here established that worker-2 is down, and only DOWN is grounds for a sweep");
    assertEquals("worker-2", leases.holder(DbNetworkLeases.DRAIN).orElseThrow());
  }

  private void forgetNodeRow(String nodeId) {
    synchronized (database.lock()) {
      try (java.sql.PreparedStatement ps = database.connection()
          .prepareStatement("DELETE FROM network_nodes WHERE node_id = ?")) {
        ps.setString(1, nodeId);
        ps.executeUpdate();
      } catch (java.sql.SQLException failed) {
        throw new IllegalStateException(failed);
      }
    }
  }

  @Test
  @DisplayName("a broken node_drains table refuses UNAVAILABLE, not 'try again'")
  void anUnwritableTableIsNotReportedAsBusy() {
    peer("lobby", "lobby", 1);
    DrainCoordinator drain = coordinator("worker-1", "worker", work);
    dropDrainTable();

    DrainControlPort.DrainResult result = drain.drain("rolling-update", false, "api");

    assertFalse(result.accepted());
    assertEquals(DrainControlPort.Refusal.UNAVAILABLE, result.refusal(),
        "BUSY told the operator this node 'already has a drain row that could not be taken over; try"
            + " again' -- advice that can never work against a table that is not there");
    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(),
        "and a refused drain must not keep the network-wide lease it took out on the way in");
  }

  @Test
  @DisplayName("a genuine duplicate row is still read as a duplicate, and taken over")
  void aDuplicateRowIsStillADuplicate() {
    // The other half of the same classifier: a PK collision has to keep answering false, or a node
    // that crashed mid-drain could never take its own stale row back at boot.
    peer("lobby", "lobby", 1);
    DrainCoordinator first = coordinator("worker-1", "worker", work);
    assertTrue(first.drain("rolling-update", false, "api").accepted());
    leases.release(DbNetworkLeases.DRAIN, "worker-1");

    // A successor of the SAME node id: its in-memory row is null, so it goes down the insert path
    // and collides with the row its predecessor left behind.
    assertTrue(coordinator("worker-1", "worker", new FakeWork())
        .drain("rolling-update", false, "api").accepted(),
        "the predecessor is demonstrably gone, because we are it");
  }

  private void dropDrainTable() {
    synchronized (database.lock()) {
      try (java.sql.Statement statement = database.connection().createStatement()) {
        statement.executeUpdate("DROP TABLE node_drains");
      } catch (java.sql.SQLException failed) {
        throw new IllegalStateException(failed);
      }
    }
  }

  // ----- the single-lobby force ------------------------------------------------------------------

  @Test
  @DisplayName("--force on the only lobby is accepted, and STALLS rather than disconnecting anybody")
  void forcingTheOnlyLobbyStallsInsteadOfCompleting() {
    DrainCoordinator drain = coordinator("lobby", "lobby", work);
    work.players = 5;

    assertTrue(drain.drain("rolling-update", true, "console").accepted());
    drain.tick();
    now.addAndGet(DrainCoordinator.Settings.DEFAULT_PHASE_TIMEOUT_SECONDS * 1000L + 1L);
    drain.tick();

    // The honest end state, and what the refusal message now promises --force will do. Nothing here
    // ever pushes a player off a node, so the only lobby cannot empty: an operator who forces it
    // gets its worlds and matches handed over and a node that says so, not a silent success and not
    // an outage.
    assertEquals(DrainPhase.STALLED, drain.state().phase());
    assertEquals("no-lobby-target", drain.state().detail());
    assertEquals(5, work.players, "and every one of them is still connected");
  }

  @Test
  @DisplayName("a terminal drain stops renewing, so a lease it no longer owns is not re-taken")
  void terminalPhasesStopRenewing() {
    DrainCoordinator drain = worker();
    drain.drain("rolling-update", false, "api");
    drain.tick();
    drain.tick();
    assertEquals(DrainPhase.READY, drain.state().phase());

    drain.tick();

    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(),
        "a renew on a released lease inserts nothing, and a terminal phase must not go looking");
  }
}
