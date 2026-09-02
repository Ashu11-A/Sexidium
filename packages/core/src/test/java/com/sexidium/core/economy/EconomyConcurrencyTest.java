package com.sexidium.core.economy;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
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
 * The proof that money cannot be created by two withdrawals racing.
 *
 * <p>Eight threads each attempt 200 withdrawals of one cent against a balance of 1000 cents. Exactly
 * 1000 must succeed, exactly 600 must be refused, and the account must settle at exactly zero — never
 * at a negative number, and never at anything that would mean a debit was applied twice or lost.</p>
 *
 * <p>This is the whole reason {@code EconomyService} never reads a balance, checks it and then writes
 * it. That sequence has a window between the read and the write; two withdrawals inside it both see
 * enough money and both succeed, and the account ends up below zero with more money handed out than
 * existed. The single conditional UPDATE makes the check and the write the same statement, decided by
 * the database against the row it is locking.</p>
 *
 * <p>If this test fails, the design is wrong and the test is right. Precedent:
 * {@code WorldLeaseFenceConcurrencyTest}.</p>
 */
class EconomyConcurrencyTest {

  private static final int THREADS = 8;
  private static final int ITERATIONS = 200;
  private static final long STARTING_CENTS = 1_000L;

  @TempDir
  Path tmp;

  @Test
  @DisplayName("8 threads x 200 withdrawals of 1 cent against 1000 cents: 1000 succeed, 600 refused, 0 left")
  void concurrentWithdrawals_cannotOverdraw() throws Exception {
    PropertiesConfigurationAdapter configuration = EconomyTestSupport.config();
    // No starting balance in the way: the account is set to exactly the amount under test.
    configuration.set("economy.starting-balance", "0.00");
    // The ledger would queue 1000 inserts against the one shared connection behind the withdrawals
    // themselves. It has its own test; here it is only noise on the thing being measured.
    configuration.set("economy.ledger.enabled", "false");

    try (Database database = EconomyTestSupport.database(tmp)) {
      EconomyService economy = EconomyTestSupport.service(database, configuration);
      try {
        UUID playerId = UUID.randomUUID();
        economy.ensureAccount(playerId, "Racer", true);
        assertTrue(economy.set(playerId, Money.ofMinor(STARTING_CENTS), "seed", "test").ok());

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        CyclicBarrier startTogether = new CyclicBarrier(THREADS);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
          List<Callable<Void>> workers = new ArrayList<>();
          for (int worker = 0; worker < THREADS; worker++) {
            workers.add(() -> {
              // A barrier and not a plain start: the point is that the threads are inside the
              // withdrawal at the same time, not merely that they all ran.
              startTogether.await(30, TimeUnit.SECONDS);
              for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                EconomyResult result = economy.withdraw(playerId, Money.ofMinor(1L), "race");
                if (result.ok()) {
                  succeeded.incrementAndGet();
                } else if (result.status() == EconomyResult.Status.INSUFFICIENT_FUNDS) {
                  insufficient.incrementAndGet();
                } else {
                  other.incrementAndGet();
                }
              }
              return null;
            });
          }
          for (Future<Void> future : pool.invokeAll(workers, 120, TimeUnit.SECONDS)) {
            future.get();
          }
        } finally {
          pool.shutdownNow();
        }

        assertEquals(0, other.get(), "a withdrawal failed for a reason that is neither success nor funds");
        assertEquals((int) STARTING_CENTS, succeeded.get(), "exactly one success per cent that existed");
        assertEquals(THREADS * ITERATIONS - (int) STARTING_CENTS, insufficient.get(),
            "every attempt past the last cent must be refused");
        economy.invalidate(playerId);
        assertEquals(Money.ZERO, economy.balance(playerId),
            "the account must settle at exactly zero -- never below it");
      } finally {
        economy.shutdown();
      }
    }
  }

  @Test
  @DisplayName("concurrent transfers in both directions conserve the total")
  void concurrentTransfers_conserveTheTotal() throws Exception {
    PropertiesConfigurationAdapter configuration = EconomyTestSupport.config();
    configuration.set("economy.starting-balance", "10.00");
    configuration.set("economy.ledger.enabled", "false");

    try (Database database = EconomyTestSupport.database(tmp)) {
      EconomyService economy = EconomyTestSupport.service(database, configuration);
      try {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        economy.ensureAccount(alice, "Alice", true);
        economy.ensureAccount(bob, "Bob", true);
        long total = economy.balance(alice).plus(economy.balance(bob)).minorUnits();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
          List<Callable<Void>> workers = new ArrayList<>();
          for (int worker = 0; worker < THREADS; worker++) {
            boolean forwards = worker % 2 == 0;
            workers.add(() -> {
              for (int iteration = 0; iteration < ITERATIONS; iteration++) {
                if (forwards) {
                  economy.transfer(alice, bob, Money.ofMinor(1L), "race");
                } else {
                  economy.transfer(bob, alice, Money.ofMinor(1L), "race");
                }
              }
              return null;
            });
          }
          for (Future<Void> future : pool.invokeAll(workers, 120, TimeUnit.SECONDS)) {
            future.get();
          }
        } finally {
          pool.shutdownNow();
        }

        economy.invalidate(alice);
        economy.invalidate(bob);
        // A debit that committed without its credit destroys money; a credit without its debit
        // creates it. Either shows up here as a total that moved.
        assertEquals(total, economy.balance(alice).plus(economy.balance(bob)).minorUnits());
      } finally {
        economy.shutdown();
      }
    }
  }
}
