package com.sexidium.core.game.experience.challenges;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Drop-Multiplier payout duration: the configured cap pours for the configured seconds and
 * every smaller multiplier takes its proportional share, so a huge multiplier never lands as one
 * server-freezing burst of item entities.
 */
class DoubleDropsStreamTest {
  private static final int CAP = 65536;

  @Test
  void theConfiguredCapPoursForTheFullDuration() {
    assertEquals(200, DoubleDropsChallenge.streamTicks(CAP, CAP, 10.0)); // 10s = 200 ticks
  }

  @Test
  void smallerMultipliersTakeTheirProportionalShare() {
    // 65536 : 10s :: 512 : 0.078125s — 1.5625 ticks, which rounds to 2: still instant to a player.
    assertEquals(2, DoubleDropsChallenge.streamTicks(512, CAP, 10.0));
    // Anything below ~1/256th of the cap resolves to a single tick, i.e. the old instant behaviour.
    assertEquals(1, DoubleDropsChallenge.streamTicks(256, CAP, 10.0));
    assertEquals(1, DoubleDropsChallenge.streamTicks(2, CAP, 10.0));
    // Half the cap is half the duration, a quarter is a quarter.
    assertEquals(100, DoubleDropsChallenge.streamTicks(CAP / 2, CAP, 10.0));
    assertEquals(50, DoubleDropsChallenge.streamTicks(CAP / 4, CAP, 10.0));
    assertEquals(25, DoubleDropsChallenge.streamTicks(CAP / 8, CAP, 10.0));
  }

  @Test
  void theRuleFollowsTheCONFIGUREDCapNotAConstant() {
    // A server that lowers max-drops-per-break gets its OWN maximum as the full-duration payout.
    assertEquals(200, DoubleDropsChallenge.streamTicks(4096, 4096, 10.0));
    assertEquals(100, DoubleDropsChallenge.streamTicks(2048, 4096, 10.0));
    // …and a different stream-seconds rescales the whole curve.
    assertEquals(100, DoubleDropsChallenge.streamTicks(4096, 4096, 5.0));
  }

  @Test
  void everyPayoutLastsAtLeastOneTickAndNeverExceedsTheCap() {
    for (int multiplier = 1; multiplier <= CAP; multiplier *= 2) {
      int ticks = DoubleDropsChallenge.streamTicks(multiplier, CAP, 10.0);
      assertTrue(ticks >= 1 && ticks <= 200, "multiplier " + multiplier + " -> " + ticks);
    }
    // Out-of-range inputs are clamped rather than trusted.
    assertEquals(200, DoubleDropsChallenge.streamTicks(CAP * 4, CAP, 10.0));
    assertEquals(1, DoubleDropsChallenge.streamTicks(0, CAP, 10.0));
    // A nonsensical cap collapses to 1, where the multiplier IS the cap and so pours for the full time.
    assertEquals(200, DoubleDropsChallenge.streamTicks(CAP, 0, 10.0));
  }

  @Test
  void streamingCanBeTurnedOff() {
    // stream-seconds: 0 restores the old behaviour — everything in one tick.
    assertEquals(1, DoubleDropsChallenge.streamTicks(CAP, CAP, 0.0));
  }
}
