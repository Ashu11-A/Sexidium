package com.sexidium.core.game.experience.challenges;

/**
 * How many in-game days a world has been alive, counted from a baseline rather than from zero.
 *
 * <h2>Why a baseline</h2>
 * The obvious answer is {@code fullTime / 24000}, and it is wrong here. A world served from the warm
 * pool has been ticking since the server booted, so the clock it arrives with is an arbitrary number
 * that has nothing to do with when this run started — the counter would open on "day 37". Persistent
 * experiences never pin their time, so nothing else corrects it. Recording what the clock read at the
 * moment the world became the current one, and counting from there, is what makes day 0 mean day 0.
 *
 * <p>Pure arithmetic with no host, so the awkward cases are cheap to test.</p>
 */
public final class DeathResetsClock {
  /** A vanilla Minecraft day: 24000 ticks, which at 20 ticks a second is the familiar 20 minutes. */
  public static final long VANILLA_DAY_TICKS = 24000L;

  private DeathResetsClock() {
  }

  /**
   * Whole in-game days between {@code baselineTicks} and {@code nowTicks}.
   *
   * <p>A clock reading BEFORE the baseline yields 0 rather than a negative day count. That is not
   * paranoia: an operator can wind a world's time backwards with {@code /time set}, and a counter that
   * answered "-3 days" would be worse than one that briefly under-reports.</p>
   *
   * @param dayLengthTicks ticks per day; values below 1 are treated as 1 so a misconfigured value cannot
   *                       divide by zero
   */
  public static long days(long nowTicks, long baselineTicks, long dayLengthTicks) {
    long dayLength = Math.max(1L, dayLengthTicks);
    long elapsed = nowTicks - baselineTicks;
    return elapsed <= 0L ? 0L : Math.floorDiv(elapsed, dayLength);
  }

  /**
   * Whether a baseline needs re-seating because the world clock has moved behind it — the world was
   * replaced, or somebody set the time backwards. The caller re-seats to {@code nowTicks} and starts
   * counting again from there.
   */
  public static boolean baselineStale(long nowTicks, long baselineTicks) {
    return nowTicks < baselineTicks;
  }
}
