package com.sexidium.core.game.experience.compose;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The buffered seconds have to be readable without being taken.
 *
 * <p>This is the fix for a counter that ticked five seconds at a time. Played time accrues every
 * second but is committed to the experience's state on a slow cadence, because its home is a file —
 * so a readout built on the committed figure alone jumps by the whole commit window however often it
 * repaints. The display adds the buffer; the write cadence is untouched.</p>
 */
class OccupancyLedgerPeekTest {
  private static final UUID ANA = UUID.randomUUID();
  private static final UUID BEN = UUID.randomUUID();

  @Test
  void peekingReportsEverySecondAsItAccrues() {
    OccupancyLedger ledger = new OccupancyLedger();

    for (int second = 1; second <= 5; second++) {
      ledger.tick(List.of(ANA));
      assertEquals(second, ledger.peekSeconds(),
          "the buffer has to move every second, or the readout built on it cannot");
    }
  }

  /** A peek must not consume: the commit that follows still has to write all five seconds. */
  @Test
  void peekingDoesNotDrain() {
    OccupancyLedger ledger = new OccupancyLedger();
    for (int second = 0; second < 5; second++) {
      ledger.tick(List.of(ANA));
    }

    assertEquals(5L, ledger.peekSeconds());
    assertEquals(5L, ledger.peekSeconds(), "a second read must see the same thing");
    assertTrue(ledger.pending());

    assertEquals(5L, ledger.drainSeconds(), "the commit must still get every second the peek showed");
    assertEquals(0L, ledger.peekSeconds(), "and the buffer is empty afterwards");
  }

  /**
   * The total is occupied wall-clock, the per-player figures are player-time, and peeking must not
   * blur the two — three players for one second is one second of run and three of player time.
   */
  @Test
  void peekingKeepsRunTimeAndPlayerTimeApart() {
    OccupancyLedger ledger = new OccupancyLedger();
    ledger.tick(List.of(ANA, BEN));
    ledger.tick(List.of(ANA, BEN));

    assertEquals(2L, ledger.peekSeconds(), "two seconds occupied, not four");
    assertEquals(2L, ledger.peekSeconds(ANA));
    assertEquals(2L, ledger.peekSeconds(BEN));
    assertEquals(0L, ledger.peekSeconds(UUID.randomUUID()), "somebody who was never there has no time");
    assertEquals(0L, ledger.peekSeconds(null));
  }

  /** An empty experience is not being played, and a peek must not invent a second for it. */
  @Test
  void anEmptyExperienceAccruesNothingToPeekAt() {
    OccupancyLedger ledger = new OccupancyLedger();
    ledger.tick(List.of());
    ledger.tick(null);

    assertEquals(0L, ledger.peekSeconds());
    assertFalse(ledger.pending());
  }
}
