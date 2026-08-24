package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The correctness stake here is data loss, not availability: if a placement is ever reassigned to a
 * node that does not have the folder, the world-acquisition path sees no world on disk and generates
 * a fresh one over the player's saved map.
 */
class WorldLeaseAuthorityTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private static final long LEASE = 20_000L;
  // The canonical key: one segment, no owner scope, no namespace. See WorldKey.
  private static final String WORLD = "diamond_hunt_ab12cd34";

  @TempDir
  Path tmp;

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "placements.db"));
  }

  private DbWorldLeaseAuthority on(String nodeId, long epoch) {
    return new DbWorldLeaseAuthority(
        database, SILENT,
        NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole("worker")),
        epoch, LEASE);
  }

  private void expireLease(String worldKey) throws Exception {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection()
          .prepareStatement("UPDATE world_placements SET lease_expires_at = 1 WHERE world_key = ?")) {
        ps.setString(1, worldKey);
        ps.executeUpdate();
      }
    }
  }

  @Test
  @DisplayName("an unplaced world is granted to the asking node")
  void firstClaim_isGranted() {
    var result = on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    var granted = assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.Granted.class, result);
    assertEquals("worker-1", granted.placement().nodeId());
    assertEquals(DbWorldLeaseAuthority.STATE_RESERVED, granted.placement().state());
  }

  @Test
  @DisplayName("a second node is refused while the owner's lease is live")
  void concurrentClaim_isRefused() {
    on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    var result = on("worker-2", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    var held = assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.HeldElsewhere.class, result);
    assertEquals("worker-1", held.nodeId());
  }

  @Test
  @DisplayName("the owning node may re-claim its own world and renew the lease")
  void ownerReclaim_isGranted() {
    DbWorldLeaseAuthority worker = on("worker-1", 1L);
    worker.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    var result = worker.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.Granted.class, result);
  }

  /**
   * RESTATED. This asserted that an expired lease never transfers ownership, "because the folder is
   * still on the other disk" — true with per-node disks, and false on the shared tree the network
   * actually runs on, where every worker reads the same bytes and an idle world belonging to whoever
   * needs it is the entire point. What survives, and is the rule that actually matters, is the half
   * about a LIVE lease.
   */
  @Test
  @DisplayName("A LIVE LEASE IS NEVER TAKEN — two writers in one set of region files is data loss")
  void aLiveLeaseIsNeverTaken() {
    on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    on("worker-1", 1L).confirmLoaded(WORLD, 1);

    var result = on("worker-2", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    // The single most important assertion in the network layer, and it is unchanged: granting here
    // puts two servers into one world.
    assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.HeldElsewhere.class, result);
    assertEquals("worker-1", on("worker-2", 1L).lookup(WORLD).orElseThrow().nodeId());
  }

  @Test
  @DisplayName("an IDLE world does change hands — that is what the shared tree is for")
  void anIdleWorldMayChangeHands() throws Exception {
    on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    expireLease(WORLD);

    var result = on("worker-2", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    // WHETHER worker-2 should be the one taking it is PlacementPlanner's decision, made before this
    // is called — see PlacementDeciderTest, where the answer is "no" while worker-1 is alive. This
    // is the mechanism, and the mechanism must be able to hand an idle world over at all.
    assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.Granted.class, result);
    assertEquals("worker-2", on("lobby", 1L).lookup(WORLD).orElseThrow().nodeId());
  }

  @Test
  @DisplayName("clearing a dead node's EXPIRED leases keeps the assignment")
  void clearingExpiredLeases_keepsAssignment() {
    on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    on("worker-1", 1L).confirmLoaded(WORLD, 3);
    // Its lease has to have ALREADY lapsed; see LeaseReaperSafetyTest for why that is the whole rule.
    on("worker-1", 1L).release(WORLD);

    int cleared = on("lobby", 1L).clearExpiredLeasesOf("worker-1");

    var placement = on("lobby", 1L).lookup(WORLD).orElseThrow();
    assertEquals(DbWorldLeaseAuthority.STATE_IDLE, placement.state());
    // Still homed on worker-1: the row is where a returning node re-adopts from.
    assertEquals("worker-1", placement.nodeId());
    assertFalse(placement.leaseHeld(System.currentTimeMillis()));
    assertTrue(cleared >= 0);
  }

  @Test
  @DisplayName("a returning node re-adopts its own orphaned worlds with a new epoch")
  void returningNode_readopts() {
    on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    on("worker-1", 1L).release(WORLD);
    on("lobby", 1L).clearExpiredLeasesOf("worker-1");

    var result = on("worker-1", 2L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    var granted = assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.Granted.class, result);
    assertEquals(2L, granted.placement().nodeEpoch(), "the new boot's epoch fences the old one");
  }

  @Test
  @DisplayName("releasing frees the lease but keeps the world homed here")
  void release_keepsHome() {
    DbWorldLeaseAuthority worker = on("worker-1", 1L);
    worker.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    worker.confirmLoaded(WORLD, 2);
    worker.release(WORLD);

    var placement = worker.lookup(WORLD).orElseThrow();
    assertEquals(DbWorldLeaseAuthority.STATE_IDLE, placement.state());
    assertEquals("worker-1", placement.nodeId());

    // The world stays HOMED here, which is what a returning player is routed by. Whether a peer may
    // take it over while it is idle is the planner's decision, not this row's.
    assertEquals("worker-1", worker.lookup(WORLD).orElseThrow().nodeId());
  }

  @Test
  @DisplayName("a node cannot rewrite another node's placement")
  void stateChange_isOwnershipGuarded() {
    on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    on("worker-1", 1L).confirmLoaded(WORLD, 5);

    on("worker-2", 1L).release(WORLD);

    var placement = on("lobby", 1L).lookup(WORLD).orElseThrow();
    assertEquals(DbWorldLeaseAuthority.STATE_LOADED, placement.state());
    assertEquals(5, placement.players());
  }

  @Test
  @DisplayName("rehome moves the assignment when the world is not loaded")
  void rehome_whenUnloaded() {
    DbWorldLeaseAuthority worker = on("worker-1", 1L);
    worker.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    worker.release(WORLD);

    assertTrue(on("lobby", 1L).rehome(WORLD, "worker-3"));

    assertEquals("worker-3", on("lobby", 1L).lookup(WORLD).orElseThrow().nodeId());
    // And now worker-3 may claim it.
    assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.Granted.class,
        on("worker-3", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1"));
  }

  @Test
  @DisplayName("rehome refuses while the world is open, so a live folder is never copied")
  void rehome_refusesWhileLoaded() {
    DbWorldLeaseAuthority worker = on("worker-1", 1L);
    worker.claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    worker.confirmLoaded(WORLD, 1);

    assertFalse(on("lobby", 1L).rehome(WORLD, "worker-3"));
    assertEquals("worker-1", on("lobby", 1L).lookup(WORLD).orElseThrow().nodeId());
  }

  @Test
  @DisplayName("a node can list the worlds it owns, to re-adopt them at boot")
  void placementsOn() {
    on("worker-1", 1L).claim("experiences:a/one_1", DbWorldLeaseAuthority.KIND_PERSISTENT, "u1");
    on("worker-1", 1L).claim("experiences:a/two_2", DbWorldLeaseAuthority.KIND_PERSISTENT, "u1");
    on("worker-2", 1L).claim("experiences:b/three_3", DbWorldLeaseAuthority.KIND_PERSISTENT, "u2");

    assertEquals(2, on("worker-1", 1L).placementsOn("worker-1").size());
    assertEquals(1, on("worker-1", 1L).placementsOn("worker-2").size());
  }

  @Test
  @DisplayName("a temp world behaves the same way; kind is recorded, not special-cased")
  void tempWorlds() {
    var result = on("worker-1", 1L)
        .claim("sexidium_temp:worker-1/abc_1", DbWorldLeaseAuthority.KIND_TEMP, null);

    var granted = assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.Granted.class, result);
    assertEquals(DbWorldLeaseAuthority.KIND_TEMP, granted.placement().kind());
  }

  // ===== planned placement: deciding the home BEFORE claiming it ==============================

  @Test
  @DisplayName("a world planned onto another node is recorded there and refused here")
  void planningRecordsTheDecision() {
    var result = on("lobby", 1L)
        .claimOn(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1", "worker-2");

    // The whole point: the node that ASKED does not become the home.
    var wrong = assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.WrongNode.class, result);
    assertEquals("worker-2", wrong.nodeId());

    var planned = on("lobby", 1L).lookup(WORLD).orElseThrow();
    assertEquals("worker-2", planned.nodeId());
    assertTrue(planned.provisional(), "nobody has opened it yet");
    assertFalse(planned.leaseHeld(System.currentTimeMillis()), "a plan holds no load lease");
  }

  @Test
  @DisplayName("the planned node claims its own plan and materialises it")
  void plannedNodeClaimsIt() {
    on("lobby", 1L).claimOn(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1", "worker-2");

    var result = on("worker-2", 7L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");

    assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.Granted.class, result);
    var placement = on("lobby", 1L).lookup(WORLD).orElseThrow();
    assertEquals(7L, placement.nodeEpoch());
    assertFalse(placement.provisional(), "opening it is what makes a plan real");
  }

  @Test
  @DisplayName("a plan nobody took up may be moved to a different node")
  void untakenPlanIsRePlannable() {
    on("lobby", 1L).claimOn(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1", "worker-2");

    // worker-2 never came back, so the caller plans worker-3 instead. Safe: no folder exists.
    var result = on("lobby", 1L)
        .claimOn(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1", "worker-3");

    assertEquals("worker-3",
        assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.WrongNode.class, result).nodeId());
    assertEquals("worker-3", on("lobby", 1L).lookup(WORLD).orElseThrow().nodeId());
  }

  /**
   * RESTATED, to where the rule now lives. "A materialised placement is never re-planned" was enforced
   * here, in the mechanism, by a storage-mode flag that defaulted FALSE in this test's constructor and
   * TRUE in production — so this test never exercised the branch the network runs. The rule itself is
   * real and is now {@code PlacementPlanner.replannable}: a materialised world STAYS PUT while the
   * node holding it is alive, and {@code PlacementDeciderTest.anIdleWorldStaysPutOnSharedStorage}
   * pins it under the deployed model.
   */
  @Test
  @DisplayName("a LOADED placement is never re-planned, whoever asks and however they ask")
  void aLoadedPlacementIsNeverRePlanned() {
    on("worker-2", 5L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    on("worker-2", 5L).confirmLoaded(WORLD, 1);

    var result = on("lobby", 1L)
        .claimOn(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1", "worker-3");

    assertEquals("worker-2",
        assertInstanceOf(DbWorldLeaseAuthority.ClaimResult.HeldElsewhere.class, result).nodeId());
    assertEquals("worker-2", on("lobby", 1L).lookup(WORLD).orElseThrow().nodeId());
  }

  @Test
  @DisplayName("adopting an on-disk world records it as materialised, not as a plan")
  void adoptRecordsAMaterialisedWorld() {
    assertTrue(on("worker-1", 3L).adopt(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1"));

    var placement = on("worker-1", 3L).lookup(WORLD).orElseThrow();
    assertEquals("worker-1", placement.nodeId());
    assertEquals(DbWorldLeaseAuthority.STATE_IDLE, placement.state());
    assertFalse(placement.provisional(),
        "a non-zero epoch is what tells 'the folder is here' apart from 'nobody took this plan up'");
  }

  @Test
  @DisplayName("a loaded placement is never forgotten, however sure the caller is")
  void forgetRefusesALoadedWorld() {
    on("worker-1", 1L).claim(WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "uuid-1");
    on("worker-1", 1L).confirmLoaded(WORLD, 2);

    assertFalse(on("worker-1", 1L).forget(WORLD));
    assertTrue(on("worker-1", 1L).lookup(WORLD).isPresent());

    on("worker-1", 1L).release(WORLD);
    assertTrue(on("worker-1", 1L).forget(WORLD));
    assertTrue(on("worker-1", 1L).lookup(WORLD).isEmpty());
  }

  @Test
  @DisplayName("the node that already holds a world is granted it again, with the SAME fence")
  void claimIsReentrantForTheHolder() {
    com.sexidium.core.world.WorldKey key = com.sexidium.core.world.WorldKey.parse(WORLD);
    var first = on("worker-1", 1L).claim(key, null);
    var granted = assertInstanceOf(com.sexidium.core.world.ClaimOutcome.Granted.class, first);

    // The holder renews its lease every heartbeat, so `lease_expires_at <= now` is false for the very
    // node that owns the world. The CAS therefore refused its own holder and the caller was told
    // Elsewhere(itself) -- so the world layer "routed" players to the node they were standing on, the
    // proxy completed that ticket as LANDED, and nothing opened the world. Enter did nothing, silently.
    var second = on("worker-1", 1L).claim(key, null);
    var again = assertInstanceOf(com.sexidium.core.world.ClaimOutcome.Granted.class, second);

    assertEquals(granted.claim().fence(), again.claim().fence(),
        "a fresh fence here would invalidate the claim this node already holds, and its next renew"
            + " would report eviction -- a re-ask must never become a self-inflicted evacuation");
    assertTrue(on("worker-1", 1L).renew(granted.claim(), 1),
        "the claim taken out the first time must still be valid after the second ask");
  }

  @Test
  @DisplayName("a DIFFERENT node is still refused while the lease is live")
  void claimIsNotReentrantForAPeer() {
    com.sexidium.core.world.WorldKey key = com.sexidium.core.world.WorldKey.parse(WORLD);
    assertInstanceOf(com.sexidium.core.world.ClaimOutcome.Granted.class, on("worker-1", 1L).claim(key, null));

    // The negative control for the re-entrancy above: it keys on node id AND epoch, so a peer, and a
    // restarted incarnation of the same node, both still lose.
    assertInstanceOf(com.sexidium.core.world.ClaimOutcome.Elsewhere.class, on("worker-2", 1L).claim(key, null));
    assertInstanceOf(com.sexidium.core.world.ClaimOutcome.Elsewhere.class, on("worker-1", 2L).claim(key, null));
  }
}
