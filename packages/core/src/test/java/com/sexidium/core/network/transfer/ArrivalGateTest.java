package com.sexidium.core.network.transfer;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I8: a ticket is consumed by exactly the addressed {@code (node, epoch)} and by nobody else.
 *
 * <p>What this replaces answered "is anybody expecting this player anywhere?" — no {@code node_id}, no
 * {@code node_epoch}, no {@code ORDER BY}, no {@code LIMIT 1} — and took whichever row the database
 * returned first.</p>
 */
class ArrivalGateTest {

  @TempDir
  Path tmp;

  private Database database;
  private DbTransferService transfers;
  private final UUID player = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    database = TransferTestSupport.database(tmp, "arrival");
    transfers = new DbTransferService(database, new TransferTestSupport.RecordingLogger(),
        "lobby", 30_000L, 3, 60_000L, 3);
  }

  @Test
  @DisplayName("the addressed node claims the ticket, and learns why the player came")
  void theAddressedNodeClaimsIt() {
    transfers.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "death_resets_ab12");

    TransferTicket claimed = transfers.claimArrival(player, "worker-1", 42L).orElseThrow();

    assertEquals(TransferReason.EXPERIENCE, claimed.reason(),
        "a typed field, not a string prefix on a match id in a table shared with rosters");
    assertEquals("death_resets_ab12", claimed.worldKey());
    assertEquals(TransferState.LANDED, claimed.state());
  }

  @Test
  @DisplayName("the WRONG node claims nothing")
  void theWrongNodeClaimsNothing() {
    transfers.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "death_resets_ab12");

    // A player on worker-2 acting on a handoff addressed to worker-1 asked the gate, was told
    // worker-1 owned the world, and was routed back — a loop that crossed a node not even involved.
    assertTrue(transfers.claimArrival(player, "worker-2", 42L).isEmpty());
    assertTrue(transfers.claimArrival(player, "worker-1", 42L).isPresent(),
        "and the real destination can still claim it afterwards");
  }

  @Test
  @DisplayName("the WRONG epoch claims nothing — a restarted worker is not its predecessor")
  void theWrongEpochClaimsNothing() {
    transfers.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "death_resets_ab12");

    assertTrue(transfers.claimArrival(player, "worker-1", 99L).isEmpty(),
        "node_epoch is the column the schema documents as fencing a worker that died mid-handoff;"
            + " the experience path passed a hard-coded 0L, so it fenced nothing at all");
  }

  @Test
  @DisplayName("a zero epoch on either side matches, so standalone still works")
  void aZeroEpochMatches() {
    transfers.request(player, "worker-1", 0L, TransferReason.EXPERIENCE, "a_ab12");
    assertTrue(transfers.claimArrival(player, "worker-1", 77L).isPresent());

    UUID other = UUID.randomUUID();
    transfers.request(other, "worker-1", 42L, TransferReason.EXPERIENCE, "a_ab12");
    assertTrue(transfers.claimArrival(other, "worker-1", 0L).isPresent());
  }

  @Test
  @DisplayName("a ticket is consumed ONCE: a second join does not re-fire it")
  void aTicketIsConsumedOnce() {
    transfers.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "a_ab12");

    assertTrue(transfers.claimArrival(player, "worker-1", 42L).isPresent());
    assertTrue(transfers.claimArrival(player, "worker-1", 42L).isEmpty(),
        "a stale expectation re-firing on every later join drags a player into a world they left");
  }

  @Test
  @DisplayName("two concurrent claimers: exactly one wins")
  void twoConcurrentClaimersOneWinner() throws Exception {
    transfers.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "a_ab12");

    int threads = 8;
    CyclicBarrier barrier = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    AtomicInteger winners = new AtomicInteger();
    try {
      List<Callable<Void>> tasks = new java.util.ArrayList<>();
      for (int i = 0; i < threads; i++) {
        tasks.add(() -> {
          barrier.await(5, TimeUnit.SECONDS);
          if (transfers.claimArrival(player, "worker-1", 42L).isPresent()) {
            winners.incrementAndGet();
          }
          return null;
        });
      }
      for (Future<Void> future : pool.invokeAll(tasks)) {
        future.get(10, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    assertEquals(1, winners.get(), "the claim is a guarded UPDATE, so only one caller may win");
  }

  @Test
  @DisplayName("an expired ticket is not claimable")
  void anExpiredTicketIsNotClaimable() throws Exception {
    DbTransferService shortLived = new DbTransferService(database,
        new TransferTestSupport.RecordingLogger(), "lobby", 1L, 3, 60_000L, 3);
    shortLived.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "a_ab12");
    Thread.sleep(10L);

    assertTrue(shortLived.claimArrival(player, "worker-1", 42L).isEmpty(),
        "a player who disconnects mid-transfer must not be yanked somewhere on their next login");
  }

  @Test
  @DisplayName("a player nobody sent claims nothing")
  void anUnexpectedPlayerClaimsNothing() {
    assertTrue(transfers.claimArrival(UUID.randomUUID(), "worker-1", 42L).isEmpty());
    assertTrue(transfers.claimArrival(null, "worker-1", 42L).isEmpty());
    assertTrue(transfers.claimArrival(player, null, 42L).isEmpty());
  }
}
