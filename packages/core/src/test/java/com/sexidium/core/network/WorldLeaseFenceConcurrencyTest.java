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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I1, under contention, on a real database: at most one node holds a valid claim on a
 * {@link WorldKey} at any instant — and a node that loses one FINDS OUT.
 *
 * <p>That second half is the one that did not exist. {@code renew} was
 * {@code WHERE world_key = ? AND node_id = ?}, so a dispossessed holder's renewal matched zero rows
 * and returned as if nothing had happened. There is no {@code session.lock} on a keyed dimension
 * folder — that file belongs to {@code world/}, which is node-local — so the lease is the ONLY thing
 * between two JVMs and one set of region files, and a lease nobody can be evicted from is not a lease.</p>
 */
class WorldLeaseFenceConcurrencyTest {

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
    database = new Database(new File(tmp.toFile(), "fence.db"));
  }

  private DbWorldLeaseAuthority on(String nodeId, long epoch, long leaseMillis) {
    NodeIdentity identity = NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole("worker"));
    return new DbWorldLeaseAuthority(database, SILENT, identity, epoch, leaseMillis);
  }

  private DbWorldLeaseAuthority on(String nodeId) {
    return on(nodeId, 100L, 60_000L);
  }

  @Test
  @DisplayName("a grant mints a unique, non-zero fence")
  void everyGrantHasItsOwnFence() {
    Set<Long> fences = new HashSet<>();
    for (int round = 0; round < 200; round++) {
      WorldKey key = WorldKey.parse("world_" + String.format("%08x", round));
      ClaimOutcome outcome = on("worker-1").claim(key, null);
      WorldClaim claim = assertInstanceOf(ClaimOutcome.Granted.class, outcome).claim();
      assertNotEquals(0L, claim.fence(), "zero is the 'never granted' value in the column");
      assertTrue(fences.add(claim.fence()), "a fence repeated across grants fences nothing");
    }
  }

  @Test
  @DisplayName("8 threads x 200 rounds: never two live claims on one key, and every fence unique")
  void neverTwoHoldersUnderContention() throws Exception {
    int threads = 8;
    int rounds = 200;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    // The live claim per key, as the winners see it. Two entries for one key at one instant is the
    // failure this test exists to catch.
    Map<String, WorldClaim> live = new ConcurrentHashMap<>();
    Set<Long> allFences = ConcurrentHashMap.newKeySet();
    AtomicInteger doubleHolders = new AtomicInteger();
    AtomicInteger duplicateFences = new AtomicInteger();
    AtomicInteger grants = new AtomicInteger();
    CyclicBarrier barrier = new CyclicBarrier(threads);

    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int thread = 0; thread < threads; thread++) {
        String nodeId = "worker-" + thread;
        // Distinct epochs as well as distinct ids: the guard is (node, epoch, fence), and a test that
        // varied only the id would not exercise the epoch half at all.
        DbWorldLeaseAuthority authority = on(nodeId, 1000L + thread, 60_000L);
        tasks.add(() -> {
          barrier.await(10, TimeUnit.SECONDS);
          for (int round = 0; round < rounds; round++) {
            WorldKey key = WorldKey.parse("world_" + String.format("%08x", round % 25));
            ClaimOutcome outcome = authority.claim(key, null);
            if (!(outcome instanceof ClaimOutcome.Granted granted)) {
              continue;
            }
            grants.incrementAndGet();
            WorldClaim claim = granted.claim();
            if (!allFences.add(claim.fence())) {
              duplicateFences.incrementAndGet();
            }
            WorldClaim previous = live.put(key.key(), claim);
            if (previous != null && previous.valid(System.currentTimeMillis())
                && !previous.nodeId().equals(claim.nodeId())) {
              // The previous holder's claim was still valid by the clock when a second one was
              // granted. On the real network that is two servers in one world.
              doubleHolders.incrementAndGet();
            }
            // Release it again so the next round has something to contend for.
            authority.release(claim);
            live.remove(key.key());
          }
          return null;
        });
      }
      for (Future<Void> future : pool.invokeAll(tasks)) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertTrue(grants.get() > 0, "the contention has to actually happen");
    assertEquals(0, duplicateFences.get(), "every grant must mint its own fence");
    assertEquals(0, doubleHolders.get(),
        "two nodes holding one world at one instant is corrupted region files, not a race to resolve");
  }

  @Test
  @DisplayName("a holder that has been dispossessed LEARNS it, from its own renew")
  void aDispossessedHolderIsTold() throws Exception {
    DbWorldLeaseAuthority one = on("worker-1", 1L, 1L); // a lease that lapses immediately
    WorldClaim claim = assertInstanceOf(ClaimOutcome.Granted.class, one.claim(KEY, null)).claim();
    assertTrue(one.renew(claim, 1), "while it is still ours, renewing works");
    Thread.sleep(10L);

    // A peer takes the idle world over. Its claim carries a NEW fence.
    DbWorldLeaseAuthority two = on("worker-2", 2L, 60_000L);
    assertInstanceOf(ClaimOutcome.Granted.class, two.claim(KEY, null));

    // Before the fence this returned nothing and said nothing: the UPDATE was guarded on node_id
    // alone, matched zero rows, and worker-1 carried on writing to the same region files.
    assertFalse(one.renew(claim, 1),
        "FALSE is the contract: freeze writes now and unload within one lease period");
    assertFalse(one.confirmOpen(claim), "and every other guarded write refuses too");
    assertFalse(one.release(claim));
    assertFalse(one.unclaim(claim));
  }

  @Test
  @DisplayName("a RESTARTED node's old claim is refused, even under the same node id")
  void anOldEpochIsFenced() {
    DbWorldLeaseAuthority before = on("worker-1", 1L, 60_000L);
    WorldClaim stale = assertInstanceOf(ClaimOutcome.Granted.class, before.claim(KEY, null)).claim();
    before.release(stale);

    // Same machine, same node id, new boot. Its claim must not be interchangeable with the old one's.
    DbWorldLeaseAuthority after = on("worker-1", 2L, 60_000L);
    WorldClaim fresh = assertInstanceOf(ClaimOutcome.Granted.class, after.claim(KEY, null)).claim();

    assertNotEquals(stale.fence(), fresh.fence());
    assertFalse(before.renew(stale, 1), "a claim from a previous boot is somebody else's claim");
    assertTrue(after.renew(fresh, 1));
  }

  @Test
  @DisplayName("unclaim is the rollback: the row is immediately claimable by anyone")
  void unclaimRollsBack() {
    DbWorldLeaseAuthority one = on("worker-1");
    WorldClaim claim = assertInstanceOf(ClaimOutcome.Granted.class, one.claim(KEY, null)).claim();
    // A peer cannot have it while the claim stands.
    assertInstanceOf(ClaimOutcome.Elsewhere.class, on("worker-2").claim(KEY, null));

    assertTrue(one.unclaim(claim), "the world was never opened; nothing was created");

    // The rollback that did not exist. Without it the row stayed materialised-looking for a world
    // that did not exist, and every later entry was routed to a node with no folder — which then
    // generated a fresh one there.
    assertInstanceOf(ClaimOutcome.Granted.class, on("worker-2").claim(KEY, null));
  }

  @Test
  @DisplayName("the state machine: RESERVED -> LOADED -> IDLE, each step fence-guarded")
  void theStateMachineIsFenced() {
    DbWorldLeaseAuthority one = on("worker-1");
    WorldClaim claim = assertInstanceOf(ClaimOutcome.Granted.class, one.claim(KEY, null)).claim();
    assertEquals(DbWorldLeaseAuthority.STATE_RESERVED,
        one.lookup(KEY.key()).orElseThrow().state());

    assertTrue(one.confirmOpen(claim));
    assertEquals(DbWorldLeaseAuthority.STATE_LOADED, one.lookup(KEY.key()).orElseThrow().state());

    assertTrue(one.release(claim));
    assertEquals(DbWorldLeaseAuthority.STATE_IDLE, one.lookup(KEY.key()).orElseThrow().state());
    assertFalse(one.lookup(KEY.key()).orElseThrow().leaseHeld(System.currentTimeMillis()));
  }

  @Test
  @DisplayName("generation allocation is one round trip and touches no filesystem")
  void generationAllocationIsInTheRow() {
    DbWorldLeaseAuthority authority = on("worker-1");
    AtomicReference<WorldKey> current = new AtomicReference<>(KEY);

    for (int expected = 1; expected <= 14; expected++) {
      WorldKey next = authority.allocateNextGeneration("exp-1", current.get());
      assertEquals(expected, next.generation());
      assertTrue(KEY.sameRun(next));
      current.set(next);
    }
    // What this replaces probed up to 1000 candidate names, each doing a Files.exists plus a disk
    // test per linked dimension — up to 3000 stats against a network filesystem, on the main thread,
    // on the tick a player died.
    assertEquals("death_resets_ab12cd34_r14", current.get().key());
  }

  @Test
  @DisplayName("concurrent allocators never hand out the same generation twice")
  void concurrentAllocatorsNeverCollide() throws Exception {
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    Set<String> allocated = ConcurrentHashMap.newKeySet();
    AtomicInteger collisions = new AtomicInteger();
    CyclicBarrier barrier = new CyclicBarrier(threads);
    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int thread = 0; thread < threads; thread++) {
        DbWorldLeaseAuthority authority = on("worker-" + thread, 1000L + thread, 60_000L);
        tasks.add(() -> {
          barrier.await(10, TimeUnit.SECONDS);
          for (int round = 0; round < 20; round++) {
            WorldKey next = authority.allocateNextGeneration("exp-1", KEY);
            if (next != null && !allocated.add(next.key())) {
              collisions.incrementAndGet();
            }
          }
          return null;
        });
      }
      for (Future<Void> future : pool.invokeAll(tasks)) {
        future.get(60, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(0, collisions.get(),
        "two resets allocating the same successor name would move one pooled world onto the other's"
            + " folder; the primary key on world_key is what settles it");
  }
}
