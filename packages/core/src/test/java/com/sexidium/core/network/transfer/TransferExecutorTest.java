package com.sexidium.core.network.transfer;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several proxies, one ticket, exactly one dispatch. (D4.)
 *
 * <p>Nearly every "safe because there is only one process" argument in the old
 * {@code PlayerRouteService} collapses at two: its SELECT and its DELETE were separate autocommit
 * statements held together by a JVM-LOCAL {@code synchronized} block, which is no mutual exclusion at
 * all across two proxies — both would read the same route and both would fire the same connect. This
 * is written claim-based from day one rather than after HA proxies turn up.</p>
 */
class TransferExecutorTest {

  @TempDir
  Path tmp;

  private Database database;
  private DbTransferService transfers;

  @BeforeEach
  void setUp() throws Exception {
    database = TransferTestSupport.database(tmp, "executor");
    transfers = new DbTransferService(database, new TransferTestSupport.RecordingLogger(),
        "lobby", 30_000L, 3, 60_000L, 3);
  }

  @Test
  @DisplayName("eight concurrent executors claim one ticket exactly once")
  void oneTicketIsDispatchedOnce() throws Exception {
    UUID player = UUID.randomUUID();
    transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12");

    int proxies = 8;
    CyclicBarrier barrier = new CyclicBarrier(proxies);
    ExecutorService pool = Executors.newFixedThreadPool(proxies);
    ConcurrentLinkedQueue<String> dispatchers = new ConcurrentLinkedQueue<>();
    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int i = 0; i < proxies; i++) {
        String executorId = "proxy-" + i;
        tasks.add(() -> {
          barrier.await(5, TimeUnit.SECONDS);
          for (TransferTicket claimed : transfers.claim(executorId, 10, 60_000L)) {
            dispatchers.add(executorId + ":" + claimed.token());
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

    assertEquals(1, dispatchers.size(),
        "two proxies both firing the same connect is a double transfer, seen by the player as being"
            + " yanked twice: " + dispatchers);
  }

  @Test
  @DisplayName("a batch is shared out, never duplicated, across concurrent executors")
  void aBatchIsSharedNotDuplicated() throws Exception {
    int tickets = 24;
    for (int i = 0; i < tickets; i++) {
      transfers.request(UUID.randomUUID(), "worker-1", 1L, TransferReason.EXPERIENCE, "w_ab12");
    }

    int proxies = 4;
    CyclicBarrier barrier = new CyclicBarrier(proxies);
    ExecutorService pool = Executors.newFixedThreadPool(proxies);
    ConcurrentLinkedQueue<String> tokens = new ConcurrentLinkedQueue<>();
    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int i = 0; i < proxies; i++) {
        String executorId = "proxy-" + i;
        tasks.add(() -> {
          barrier.await(5, TimeUnit.SECONDS);
          for (int round = 0; round < 10; round++) {
            for (TransferTicket claimed : transfers.claim(executorId, 8, 60_000L)) {
              tokens.add(claimed.token());
            }
          }
          return null;
        });
      }
      for (Future<Void> future : pool.invokeAll(tasks)) {
        future.get(20, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    Set<String> unique = new HashSet<>(tokens);
    assertEquals(tickets, unique.size(), "every ticket must be claimed");
    assertEquals(tickets, tokens.size(), "and none of them twice: " + tokens.size() + " dispatches");
  }

  @Test
  @DisplayName("a ticket a proxy completed is not re-dispatched by another")
  void aCompletedTicketIsNotRedispatched() {
    UUID player = UUID.randomUUID();
    String token = transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12")
        .orElseThrow().token();

    assertEquals(1, transfers.claim("proxy-1", 10, 1L).size());
    transfers.complete(token, TransferState.LANDED, "arrived");

    assertTrue(transfers.claim("proxy-2", 10, 60_000L).isEmpty());
  }
}
