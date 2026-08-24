package com.sexidium.core.lib.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule behind {@code Database.connection()}'s validity cache.
 *
 * <p>{@code Connection.isValid(2)} is a round trip to the database server and it was being made on
 * EVERY {@code connection()} call, inside the one global monitor every subsystem queues behind —
 * six to ten of them per experience entry. Tested here as a pure decision rather than against a
 * networked database, because the interesting cases (a clock that steps backwards, an interval of
 * zero) cannot be provoked against a live MySQL.
 */
class DatabaseValidationCacheTest {

  @Test
  @DisplayName("a connection that has never been validated is validated now")
  void neverValidated_isDue() {
    assertTrue(Database.revalidationDue(0L, 1_000L, 5_000L));
  }

  @Test
  @DisplayName("inside the interval a recent 'yes' is trusted, so no round trip is made")
  void insideInterval_isNotDue() {
    assertFalse(Database.revalidationDue(10_000L, 12_000L, 5_000L));
    assertFalse(Database.revalidationDue(10_000L, 14_999L, 5_000L));
  }

  @Test
  @DisplayName("at exactly the interval the answer has expired")
  void atInterval_isDue() {
    assertTrue(Database.revalidationDue(10_000L, 15_000L, 5_000L));
  }

  @Test
  @DisplayName("past the interval the answer has expired")
  void pastInterval_isDue() {
    assertTrue(Database.revalidationDue(10_000L, 60_000L, 5_000L));
  }

  @Test
  @DisplayName("an interval of zero restores validate-every-time")
  void zeroInterval_alwaysValidates() {
    assertTrue(Database.revalidationDue(10_000L, 10_000L, 0L));
    assertTrue(Database.revalidationDue(10_000L, 10_001L, 0L));
  }

  @Test
  @DisplayName("a clock that steps backwards revalidates instead of trusting a future timestamp")
  void backwardsClock_isDue() {
    // Without this a single NTP correction (or a container clock jump) would leave lastValidatedAt
    // in the future and the cache would trust it for the rest of the process's life.
    assertTrue(Database.revalidationDue(60_000L, 10_000L, 5_000L));
  }
}
