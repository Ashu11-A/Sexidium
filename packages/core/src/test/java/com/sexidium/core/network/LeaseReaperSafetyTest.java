package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.ClaimOutcome;
import com.sexidium.core.world.WorldClaim;
import com.sexidium.core.world.WorldKey;
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
 * A reaper may never shorten a lease that is still live. (F-A3, F9, F15.)
 *
 * <p>{@code orphanPlacementsOf} was the single most dangerous statement in the tree. Any node could
 * declare a peer DOWN once its heartbeat aged past {@code node-timeout-seconds}, and the reaper then
 * zeroed {@code lease_expires_at} on EVERY row of that node — including rows in LOADED whose lease was
 * still perfectly valid. That made node liveness a fencing token, which it is not:</p>
 *
 * <ol>
 *   <li>worker-7 stalls for 31 seconds — a long GC, a chunk-save storm, or one slow query on the
 *       single connection its heartbeat shares with all gameplay — while a world is open mid-write.</li>
 *   <li>The lobby reaps it and clears the lease.</li>
 *   <li>worker-2 sees an idle world with a readable folder and opens the same region files.</li>
 *   <li>worker-7 unstalls and carries on writing. No {@code session.lock} covers a keyed dimension
 *       folder, and its own renew was guarded on {@code node_id} alone, so it never found out.</li>
 * </ol>
 *
 * <p>Two things close it: this predicate, and the fence (see
 * {@link WorldLeaseFenceConcurrencyTest#aDispossessedHolderIsTold}).</p>
 */
class LeaseReaperSafetyTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private static final WorldKey KEY = WorldKey.parse("death_resets_ab12cd34");

  @TempDir
  Path tmp;

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "reaper.db"));
  }

  private DbWorldLeaseAuthority on(String nodeId, long epoch) {
    NodeIdentity identity = NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole("worker"));
    return new DbWorldLeaseAuthority(database, SILENT, identity, epoch, 60_000L);
  }

  /** Backdate a lease, the way the clock does while a node is genuinely gone. */
  private void expireLease(String worldKey) throws Exception {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET lease_expires_at = 1 WHERE world_key = ?")) {
        ps.setString(1, worldKey);
        ps.executeUpdate();
      }
    }
  }

  @Test
  @DisplayName("THE BUG: a reaper does NOT touch a world whose lease is still live")
  void aLiveLeaseIsUntouchable() {
    DbWorldLeaseAuthority stalled = on("worker-7", 7L);
    WorldClaim claim = assertInstanceOf(ClaimOutcome.Granted.class, stalled.claim(KEY, null)).claim();
    assertTrue(stalled.confirmOpen(claim), "worker-7 has the world open, with players in it");

    // The lobby declares worker-7 dead because it has not heartbeated for 31 seconds. It has NOT
    // stopped writing to that folder — it is stalled, not gone.
    int cleared = on("lobby", 1L).clearExpiredLeasesOf("worker-7");

    assertEquals(0, cleared, "a live lease is not a reaper's business, whatever the registry thinks");
    var placement = on("lobby", 1L).lookup(KEY.key()).orElseThrow();
    assertEquals(DbWorldLeaseAuthority.STATE_LOADED, placement.state());
    assertTrue(placement.leaseHeld(System.currentTimeMillis()), "the lease survives untouched");

    // ...and therefore no peer can take the world while worker-7 is still in it.
    assertInstanceOf(ClaimOutcome.Elsewhere.class, on("worker-2", 2L).claim(KEY, null));
    // The stalled node, having never actually lost anything, is still the holder.
    assertTrue(stalled.renew(claim, 1));
  }

  @Test
  @DisplayName("a lease that has ALREADY expired is tidied, which is the reaper's whole job")
  void anExpiredLeaseIsTidied() throws Exception {
    DbWorldLeaseAuthority dead = on("worker-7", 7L);
    WorldClaim claim = assertInstanceOf(ClaimOutcome.Granted.class, dead.claim(KEY, null)).claim();
    dead.confirmOpen(claim);
    expireLease(KEY.key());

    int cleared = on("lobby", 1L).clearExpiredLeasesOf("worker-7");

    assertEquals(1, cleared);
    var placement = on("lobby", 1L).lookup(KEY.key()).orElseThrow();
    assertEquals(DbWorldLeaseAuthority.STATE_IDLE, placement.state());
    assertEquals("worker-7", placement.nodeId(), "it stays HOMED there; only the lease is cleared");
    assertEquals(0, placement.players());
  }

  @Test
  @DisplayName("a genuinely dead node's lease expires on its own inside the node timeout")
  void aDeadNodesLeaseExpiresBeforeAnybodyReaps() {
    // Stage 0's timing invariant is what makes this true, and it is why the reaper does not need to
    // force anything: world-lease-seconds is asserted at boot to be strictly LESS than
    // node-timeout-seconds, so by the time a node can be declared dead its leases have already gone.
    NetworkSettings.Timings timings = NetworkSettings.timings(null);
    assertTrue(timings.worldLeaseSeconds() < timings.nodeTimeoutSeconds(),
        "otherwise a node is reaped while its own claim is still valid, and the reaper has to choose"
            + " between leaving worlds stuck forever and shortening a live lease");
  }

  @Test
  @DisplayName("clearing drops the fence too, so a returning node cannot resume with its old claim")
  void clearingDropsTheFence() throws Exception {
    DbWorldLeaseAuthority dead = on("worker-7", 7L);
    WorldClaim claim = assertInstanceOf(ClaimOutcome.Granted.class, dead.claim(KEY, null)).claim();
    dead.confirmOpen(claim);
    expireLease(KEY.key());

    on("lobby", 1L).clearExpiredLeasesOf("worker-7");

    assertFalse(dead.renew(claim, 1),
        "a node that comes back must re-claim, not carry on with a claim the network has released");
  }

  @Test
  @DisplayName("reaping one node never touches another's rows")
  void reapingIsScopedToOneNode() throws Exception {
    DbWorldLeaseAuthority seven = on("worker-7", 7L);
    DbWorldLeaseAuthority eight = on("worker-8", 8L);
    WorldKey other = WorldKey.parse("other_map_ff00ff00");
    WorldClaim mine = assertInstanceOf(ClaimOutcome.Granted.class, seven.claim(KEY, null)).claim();
    WorldClaim theirs = assertInstanceOf(ClaimOutcome.Granted.class, eight.claim(other, null)).claim();
    seven.confirmOpen(mine);
    eight.confirmOpen(theirs);
    expireLease(KEY.key());
    expireLease(other.key());

    on("lobby", 1L).clearExpiredLeasesOf("worker-7");

    assertEquals(DbWorldLeaseAuthority.STATE_IDLE,
        on("lobby", 1L).lookup(KEY.key()).orElseThrow().state());
    assertEquals(DbWorldLeaseAuthority.STATE_LOADED,
        on("lobby", 1L).lookup(other.key()).orElseThrow().state(),
        "worker-8 was not being reaped and must be left entirely alone");
  }
}
