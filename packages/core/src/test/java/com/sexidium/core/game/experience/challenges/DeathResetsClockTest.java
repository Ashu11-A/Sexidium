package com.sexidium.core.game.experience.challenges;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathResetsClockTest {
  private static final long DAY = DeathResetsClock.VANILLA_DAY_TICKS;

  @Test
  void aWorldStartsOnDayZeroWhateverItsClockSays() {
    // The case a raw fullTime/24000 gets wrong: a world served from the warm pool has been ticking since
    // the server booted, so its clock is an arbitrary large number that has nothing to do with this run.
    long pooledClock = 37 * DAY + 1234;

    assertEquals(0, DeathResetsClock.days(pooledClock, pooledClock, DAY));
  }

  @Test
  void countsWholeDaysFromTheBaseline() {
    long baseline = 5 * DAY;

    assertEquals(0, DeathResetsClock.days(baseline + DAY - 1, baseline, DAY));
    assertEquals(1, DeathResetsClock.days(baseline + DAY, baseline, DAY));
    assertEquals(1, DeathResetsClock.days(baseline + 2 * DAY - 1, baseline, DAY));
    assertEquals(12, DeathResetsClock.days(baseline + 12 * DAY, baseline, DAY));
  }

  /** An operator can wind a world's clock back; "-3 days" would be worse than briefly under-reporting. */
  @Test
  void aClockBehindItsBaselineReadsZeroRatherThanNegative() {
    assertEquals(0, DeathResetsClock.days(100, 5 * DAY, DAY));
  }

  @Test
  void honoursANonVanillaDayLength() {
    assertEquals(4, DeathResetsClock.days(4000, 0, 1000));
    assertEquals(0, DeathResetsClock.days(999, 0, 1000));
  }

  /** A misconfigured day length must not divide by zero — the counter just ticks once per tick. */
  @Test
  void aZeroOrNegativeDayLengthIsClampedToOne() {
    assertEquals(500, DeathResetsClock.days(500, 0, 0));
    assertEquals(500, DeathResetsClock.days(500, 0, -20));
  }

  @Test
  void baselineStale_onlyWhenTheClockHasMovedBehindIt() {
    assertTrue(DeathResetsClock.baselineStale(10, 100), "a new world's clock starts behind the old one");
    assertFalse(DeathResetsClock.baselineStale(100, 100));
    assertFalse(DeathResetsClock.baselineStale(1000, 100));
  }
}
