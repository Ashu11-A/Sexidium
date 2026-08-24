package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.WorldPlacementGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end over the decision itself: who ends up hosting a world, from the point of view of the
 * node the player happens to be standing on.
 *
 * <p><b>Every case here runs under SHARED STORAGE, which is the only model the network is deployed
 * in.</b> Half of these used to construct the decider through a helper that set
 * {@code sharedStorage = false} — a mode production has never run in — so the branches that actually
 * execute on the live network had no coverage at all. That is not a gap in the abstract: it is
 * exactly how F10 shipped, where {@code lineageHome} ended with an unguarded
 * {@code localDisk.test(sibling)} and, because the lobby sees every folder in the network through the
 * shared symlink, planned regenerated experiences onto the lobby — the precise bug the class exists
 * to prevent.</p>
 */
class PlacementDeciderTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  // The canonical key: one segment, no owner scope, no namespace. See WorldKey.
  private static final String WORLD = "diamond_hunt_ab12cd34";
  private static final long TIMEOUT = 30_000L;

  @TempDir
  Path tmp;

  private Database database;
  private final Set<String> onDisk = new LinkedHashSet<>();

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "decider.db"));
  }

  private NodeIdentity identity(String nodeId, String role) {
    return NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole(role));
  }

  private void registerAlive(String nodeId, String role, int players) {
    new NodeRegistry(database, SILENT, identity(nodeId, role), TIMEOUT).heartbeat(players, 0);
  }

  private DbWorldLeaseAuthority placements(String nodeId, String role, long epoch) {
    return new DbWorldLeaseAuthority(database, SILENT, identity(nodeId, role), epoch, 20_000L);
  }

  /**
   * The one gate. There used to be two helpers — {@code gateOn} and {@code sharedGateOn} — and the
   * difference between them was a storage mode the deployed network does not have.
   */
  private WorldPlacementGate gateOn(String nodeId, String role) {
    NodeIdentity identity = identity(nodeId, role);
    NodePlacementPlanner planner =
        new NodePlacementPlanner(new NodeRegistry(database, SILENT, identity, TIMEOUT), identity);
    return new PlacementDecider(
        placements(nodeId, role, 100L), planner, identity, onDisk::contains);
  }

  private DbWorldLeaseAuthority placements(String nodeId, String role, long epoch, boolean ignored) {
    return placements(nodeId, role, epoch);
  }

  private WorldPlacementGate sharedGateOn(String nodeId, String role) {
    return gateOn(nodeId, role);
  }

  @Test
  @DisplayName("THE BUG: the lobby no longer keeps the world just because it was asked")
  void lobbyPlansOntoAWorker() {
    registerAlive("lobby", "lobby", 4);
    registerAlive("worker-1", "worker", 0);

    WorldPlacementGate.Decision decision = gateOn("lobby", "lobby").check(WORLD);

    assertFalse(decision.allowed(), "the lobby must not open a world it has no folder for");
    assertEquals("worker-1", decision.ownerNodeId());
  }

  @Test
  @DisplayName("the chosen worker is then allowed to open it")
  void chosenWorkerIsAllowed() {
    registerAlive("lobby", "lobby", 4);
    registerAlive("worker-1", "worker", 0);
    gateOn("lobby", "lobby").check(WORLD);

    assertTrue(gateOn("worker-1", "worker").check(WORLD).allowed());
  }

  @Test
  @DisplayName("THE CHURN BUG: an idle world does NOT move while its node is alive")
  void anIdleWorldStaysPutOnSharedStorage() {
    // worker-1 is the quietest, so the planner puts the new world there.
    registerAlive("lobby", "lobby", 4);
    registerAlive("worker-1", "worker", 0);
    registerAlive("worker-2", "worker", 5);
    assertTrue(sharedGateOn("worker-1", "worker").check(WORLD).allowed(), "worker-1 opens it first");

    // The player leaves: the world unloads and the lease is dropped within seconds. Meanwhile worker-2
    // has become the quietest node. For a while this re-planned the world onto worker-2 on the next
    // entry -- observed live as one world announced on two different workers seconds apart. A transfer
    // per entry, buying nothing: there is no load to spread in a world nobody is in.
    placements("worker-1", "worker", 100L, true).release(WORLD);
    registerAlive("worker-1", "worker", 5);
    registerAlive("worker-2", "worker", 0);

    WorldPlacementGate.Decision decision = sharedGateOn("lobby", "lobby").check(WORLD);

    assertFalse(decision.allowed());
    assertEquals("worker-1", decision.ownerNodeId(), "it stays where it is while worker-1 is alive");
  }

  @Test
  @DisplayName("…but a world whose node is DOWN is taken over — the point of shared storage")
  void anIdleWorldMovesWhenItsNodeIsGone() {
    // worker-1 holds the world and then goes away for good: it never heartbeats, so the planner sees
    // no live node by that name. On a per-node disk the world would be unreachable until that machine
    // came back; on shared storage the folder is right here, readable by anyone.
    DbWorldLeaseAuthority holder = placements("worker-1", "worker", 100L, true);
    holder.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    holder.release(WORLD);
    registerAlive("worker-2", "worker", 0);

    assertTrue(sharedGateOn("worker-2", "worker").check(WORLD).allowed(),
        "with the holder gone and the folder readable from here, worker-2 must be able to serve it");
  }

  @Test
  @DisplayName("the worker being asked resumes an idle world itself, wherever it was last opened")
  void theAskingWorkerResumesAnIdleWorld() {
    registerAlive("worker-1", "worker", 0);
    registerAlive("worker-2", "worker", 0);
    // worker-1 opened it last and is still perfectly alive; the player has since reconnected and the
    // proxy put them on worker-2. The folder is on the shared tree, so worker-2 can read every byte.
    DbWorldLeaseAuthority holder = placements("worker-1", "worker", 100L, true);
    holder.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    holder.release(WORLD);
    onDisk.add(WORLD);

    WorldPlacementGate.Decision decision = sharedGateOn("worker-2", "worker").check(WORLD);

    assertTrue(decision.allowed(),
        "any worker must be able to resume a world nobody has open — that is the point of the shared tree");
    assertEquals("worker-2",
        placements("worker-2", "worker", 100L, true).lookup(WORLD).orElseThrow().nodeId(),
        "and the row follows the player, so nobody is transferred anywhere");
  }

  @Test
  @DisplayName("…but the lobby still plans onto a worker instead of taking it")
  void theLobbyNeverAdoptsAnIdleWorld() {
    registerAlive("lobby", "lobby", 4);
    registerAlive("worker-1", "worker", 0);
    DbWorldLeaseAuthority holder = placements("worker-1", "worker", 100L, true);
    holder.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    holder.release(WORLD);
    onDisk.add(WORLD); // shared tree: the lobby can see the folder too, and must still not host it

    WorldPlacementGate.Decision decision = sharedGateOn("lobby", "lobby").check(WORLD);

    assertFalse(decision.allowed(), "the lobby has no EXPERIENCES capability; whoever-asks-serves is not for it");
    assertEquals("worker-1", decision.ownerNodeId());
  }

  @Test
  @DisplayName("a world its owner has OPEN is never taken — one open world, one writer")
  void aLiveOwnerIsNeverContested() {
    registerAlive("worker-1", "worker", 1);
    registerAlive("worker-2", "worker", 0);
    DbWorldLeaseAuthority holder = placements("worker-1", "worker", 100L, true);
    holder.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    holder.confirmLoaded(WORLD, 1);
    onDisk.add(WORLD);

    // worker-2 is emptier and can read the same bytes, and neither fact matters: a second writer in
    // one set of region files is corruption, not a scheduling decision.
    WorldPlacementGate.Decision decision = sharedGateOn("worker-2", "worker").check(WORLD);

    assertFalse(decision.allowed());
    assertTrue(decision.busy());
    assertEquals("worker-1", decision.ownerNodeId(), "the player is routed to the node serving it");
    assertEquals("worker-1",
        placements("worker-2", "worker", 100L, true).lookup(WORLD).orElseThrow().nodeId());
  }

  @Test
  @DisplayName("a world with no folder is never adopted, however idle it looks")
  void aMissingFolderIsNeverAdopted() {
    registerAlive("worker-1", "worker", 0);
    registerAlive("worker-2", "worker", 0);
    DbWorldLeaseAuthority holder = placements("worker-1", "worker", 100L, true);
    holder.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    holder.release(WORLD);
    // onDisk stays empty: the row says the world exists, the tree says otherwise. Adopting it here
    // would load nothing and generate an empty world under the player's name.

    assertFalse(sharedGateOn("worker-2", "worker").check(WORLD).allowed());
  }

  @Test
  @DisplayName("two workers asking for the same idle world at once: exactly one is granted")
  void concurrentAskersProduceOneWinner() throws Exception {
    registerAlive("worker-1", "worker", 0);
    registerAlive("worker-2", "worker", 0);
    registerAlive("worker-3", "worker", 0);
    DbWorldLeaseAuthority holder = placements("worker-1", "worker", 100L, true);
    holder.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    holder.release(WORLD);
    onDisk.add(WORLD);

    // Both are entitled to it and both ask in the same instant. There is one folder, so there can be
    // one writer: the exchange is a single conditional UPDATE, and the loser must be told, not granted.
    CyclicBarrier together = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<WorldPlacementGate.Decision>> answers = new ArrayList<>();
      for (String node : List.of("worker-2", "worker-3")) {
        WorldPlacementGate gate = sharedGateOn(node, "worker");
        answers.add(pool.submit(() -> {
          together.await(5, TimeUnit.SECONDS);
          return gate.check(WORLD);
        }));
      }
      long granted = 0;
      for (Future<WorldPlacementGate.Decision> answer : answers) {
        if (answer.get(10, TimeUnit.SECONDS).allowed()) {
          granted++;
        }
      }
      assertEquals(1L, granted, "two nodes opening one world is corrupted region files, not a conflict");
    } finally {
      pool.shutdownNow();
    }

    String winner = placements("worker-1", "worker", 100L, true).lookup(WORLD).orElseThrow().nodeId();
    assertTrue(List.of("worker-2", "worker-3").contains(winner), "the row names the node that won");
  }

  @Test
  @DisplayName("THE RESET BUG: a regenerated world stays on the node holding the run")
  void regeneratedWorldInheritsTheLineageHome() {
    registerAlive("lobby", "lobby", 4);
    registerAlive("worker-1", "worker", 1);
    registerAlive("worker-2", "worker", 0);
    // worker-1 is hosting the run: it holds the world and therefore has the player on it.
    onDisk.add(WORLD);
    assertTrue(gateOn("worker-1", "worker").check(WORLD).allowed());

    // Death Resets builds the replacement ALONGSIDE the old world and then swaps the folders, so the
    // successor has to land on the same disk. Planned by load it would go to worker-2 (fewer players),
    // worker-1's gate would refuse to open it, and the reset would abort with "the new world could not
    // be prepared" — which is exactly what happened in production.
    WorldPlacementGate.Decision decision = gateOn("worker-1", "worker").check(WORLD + "_r1");

    assertTrue(decision.allowed(), "the node that holds generation 0 must be allowed to build _r1");
    assertEquals("worker-1",
        placements("worker-1", "worker", 100L).lookup(WORLD + "_r1").orElseThrow().nodeId());
  }

  @Test
  @DisplayName("the lineage is followed past generations that have already been cleaned up")
  void regeneratedWorldSkipsDeletedGenerations() {
    registerAlive("worker-1", "worker", 1);
    registerAlive("worker-2", "worker", 0);
    onDisk.add(WORLD + "_r1");
    assertTrue(gateOn("worker-1", "worker").check(WORLD + "_r1").allowed());

    // _r2 is born while _r1 is still the live world; generation 0 is long gone.
    assertTrue(gateOn("worker-1", "worker").check(WORLD + "_r2").allowed());
  }

  /**
   * F10, pinned. The lobby sees every folder in the network through the shared symlink, so
   * {@code lineageHome}'s old {@code localDisk.test(sibling)} fallback answered "me" on the lobby for
   * every regenerated experience in the park — with no shared-storage check and no capability check.
   */
  @Test
  @DisplayName("F10: the lobby never inherits a lineage just because it can SEE the predecessor")
  void theLobbyNeverInheritsALineage() {
    registerAlive("lobby", "lobby", 0);
    registerAlive("worker-1", "worker", 5);
    // worker-1 holds _r14 and then goes away; the lobby can still read every byte of the folder.
    placements("worker-1", "worker", 100L)
        .claim(WORLD + "_r14", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-1", "worker", 100L).release(WORLD + "_r14");
    onDisk.add(WORLD + "_r14");

    WorldPlacementGate.Decision decision = gateOn("lobby", "lobby").check(WORLD + "_r15");

    assertFalse(decision.allowed(),
        "the lobby planning a regenerated experience onto itself is the exact bug PlacementDecider"
            + " was written to eliminate");
    assertEquals("worker-1", decision.ownerNodeId(), "it follows the run's own home");
  }

  /**
   * The branch this replaces was {@code !sharedStorage && localDisk.test(worldKey)}, and under the
   * deployed model it is actively wrong: EVERY node passes {@code localDisk} on a shared tree,
   * including the lobby, so keeping it would hand the world to whoever asked — the exact behaviour
   * the planner was written to replace, and the reason the lobby used to host everything.
   */
  @Test
  @DisplayName("seeing the folder is NOT a claim: on a shared tree every node sees every folder")
  void aVisibleFolderDoesNotMakeTheLobbyItsHome() {
    registerAlive("lobby", "lobby", 4);
    registerAlive("worker-1", "worker", 0);
    onDisk.add(WORLD); // visible from the lobby too, through the shared symlink

    WorldPlacementGate.Decision decision = gateOn("lobby", "lobby").check(WORLD);

    assertFalse(decision.allowed(), "the lobby has no EXPERIENCES capability, folder or no folder");
    assertEquals("worker-1", decision.ownerNodeId());
  }

  @Test
  @DisplayName("...but a WORKER that can see the folder does claim it, rather than planning it away")
  void aWorkerWithTheFolderClaimsIt() {
    registerAlive("worker-1", "worker", 0);
    registerAlive("worker-2", "worker", 9);
    onDisk.add(WORLD);
    placements("worker-2", "worker", 5L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-2", "worker", 5L).release(WORLD);

    assertTrue(gateOn("worker-1", "worker").check(WORLD).allowed(),
        "whoever asks, serves: the player is already here and nobody has it open");
  }

  @Test
  @DisplayName("an existing home is honoured even when a better candidate exists")
  void existingHomeWins() {
    registerAlive("worker-1", "worker", 0);
    // worker-2 must be ALIVE for this to mean "the home is honoured". A home whose node is gone is
    // taken over, which is the separate guarantee anIdleWorldMovesWhenItsNodeIsGone pins.
    registerAlive("worker-2", "worker", 9);
    placements("worker-2", "worker", 5L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-2", "worker", 5L).release(WORLD);

    WorldPlacementGate.Decision decision = gateOn("lobby", "lobby").check(WORLD);

    assertEquals("worker-2", decision.ownerNodeId(),
        "worker-2 has the folder; load has nothing to do with it");
  }

  @Test
  @DisplayName("a plan onto a node that never showed up is re-planned onto one that is alive")
  void deadPlanIsRePlanned() {
    registerAlive("worker-1", "worker", 0);
    // worker-9 is planned but never heartbeats: it is not in the registry at all.
    placements("lobby", "lobby", 100L)
        .claimOn(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null, "worker-9");

    assertEquals("worker-1", gateOn("lobby", "lobby").check(WORLD).ownerNodeId());
  }

  @Test
  @DisplayName("a world open on its owner is refused as busy, not re-planned")
  void loadedElsewhereIsBusy() {
    registerAlive("worker-1", "worker", 0);
    placements("worker-2", "worker", 5L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-2", "worker", 5L).confirmLoaded(WORLD, 2);

    WorldPlacementGate.Decision decision = gateOn("lobby", "lobby").check(WORLD);

    assertTrue(decision.busy());
    assertEquals("worker-2", decision.ownerNodeId());
  }

  @Test
  @DisplayName("a single node that CAN host experiences is still the candidate of last resort")
  void standaloneIsStillACandidateOfLastResort() {
    registerAlive("standalone", "standalone", 4);

    assertTrue(gateOn("standalone", "standalone").check(WORLD).allowed(),
        "the last resort exists for the one-node deployment, and a standalone node holds EXPERIENCES");
  }

  @Test
  @DisplayName("a lobby that CANNOT host experiences refuses instead of claiming a world it will not open")
  void lobbyWithoutExperiencesRefusesRatherThanClaiming() {
    // This replaces a test that asserted the opposite, and the opposite was the bug. The `lobby`
    // role does not carry EXPERIENCES (NodeIdentity), so the planner handed the world to the lobby
    // as "last resort", the door guard then refused to OPEN it for exactly that reason, and
    // `unclaim` keeps `node_id` on purpose — so `replannable` never became true again, because the
    // lobby is always alive. The world was unopenable network-wide until someone deleted the row by
    // hand. Refusing now is recoverable: the moment a capable worker appears, the world is planned
    // onto it. Writing the wrong owner is not.
    registerAlive("lobby", "lobby", 4);

    WorldPlacementGate.Decision decision = gateOn("lobby", "lobby").check(WORLD);

    assertFalse(decision.allowed(), "a node without EXPERIENCES must not claim an experience world");
    assertNull(decision.ownerNodeId(), "and it must not record an owner it cannot honour");
  }
}
