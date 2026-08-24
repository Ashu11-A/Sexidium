package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code network_leases} had zero users. These are the properties the drain protocol needs from it,
 * and every one of them fails before {@link DbNetworkLeases} exists.
 */
class DbNetworkLeasesTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private Database database;
  private final AtomicLong now = new AtomicLong(1_000_000L);

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "leases.db"));
  }

  private DbNetworkLeases leases() {
    return new DbNetworkLeases(database, SILENT, now::get);
  }

  @Test
  @DisplayName("two nodes race for the drain lease and exactly one wins")
  void twoNodesRaceForDrainLease() {
    DbNetworkLeases first = leases();
    DbNetworkLeases second = leases();

    assertTrue(first.acquire(DbNetworkLeases.DRAIN, "worker-1", 60_000L));
    assertFalse(second.acquire(DbNetworkLeases.DRAIN, "worker-2", 60_000L),
        "a second node must NOT be able to drain while the first is: they would each hand their"
            + " worlds to the other and both restart with players inside");
    assertEquals("worker-1", first.holder(DbNetworkLeases.DRAIN).orElseThrow(),
        "the refusal has to be able to NAME the holder, or an operator has four containers to check");
  }

  @Test
  @DisplayName("re-acquiring your own lease succeeds, so a retried drain request is idempotent")
  void reacquiringOwnLeaseSucceeds() {
    DbNetworkLeases leases = leases();
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-1", 60_000L));
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-1", 60_000L));
  }

  @Test
  @DisplayName("an expired lease hands over to the next asker without anyone noticing the death")
  void expiredLeaseHandsOver() {
    DbNetworkLeases leases = leases();
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-1", 10_000L));

    // worker-1 dies here. Nothing observes it; expiry is a fact about the row.
    now.addAndGet(10_001L);

    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(), "an expired lease has no holder");
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-2", 60_000L));
    assertEquals("worker-2", leases.holder(DbNetworkLeases.DRAIN).orElseThrow());
  }

  @Test
  @DisplayName("renewing a lease you no longer hold fails, so a lost drain cannot re-arm itself")
  void renewByNonHolderFails() {
    DbNetworkLeases leases = leases();
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-1", 10_000L));
    now.addAndGet(10_001L);
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-2", 60_000L));

    assertFalse(leases.renew(DbNetworkLeases.DRAIN, "worker-1", 60_000L),
        "worker-1 lost the lease; renewing it would give the network two draining nodes");
    assertEquals("worker-2", leases.holder(DbNetworkLeases.DRAIN).orElseThrow());
  }

  @Test
  @DisplayName("a late renewal keeps a lease nobody else has taken")
  void lateRenewalKeepsAnUntakenLease() {
    DbNetworkLeases leases = leases();
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-1", 10_000L));
    now.addAndGet(11_000L);

    assertTrue(leases.renew(DbNetworkLeases.DRAIN, "worker-1", 60_000L),
        "dropping a drain on the floor because one heartbeat was slow is the same class of bug as"
            + " evicting a healthy world holder over one missed renewal");
    assertEquals("worker-1", leases.holder(DbNetworkLeases.DRAIN).orElseThrow());
  }

  @Test
  @DisplayName("release is guarded on the holder, so a successor cannot be released by its predecessor")
  void releaseIsGuardedOnHolder() {
    DbNetworkLeases leases = leases();
    assertTrue(leases.acquire(DbNetworkLeases.DRAIN, "worker-1", 60_000L));
    assertFalse(leases.release(DbNetworkLeases.DRAIN, "worker-2"));
    assertEquals("worker-1", leases.holder(DbNetworkLeases.DRAIN).orElseThrow());
    assertTrue(leases.release(DbNetworkLeases.DRAIN, "worker-1"));
    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty());
  }
}
