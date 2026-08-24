package com.sexidium.core.game.experience.compose;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How an experience's played time is counted: wall clock while occupied, and nothing while empty. */
class OccupancyLedgerTest {
  private static final UUID ASHU = UUID.randomUUID();
  private static final UUID BOB = UUID.randomUUID();
  private static final UUID CID = UUID.randomUUID();

  @Test
  void anEmptyExperienceAccruesNothing() {
    // The pause rule. An experience nobody is in is not being played, however long its world sits on
    // disk between visits.
    OccupancyLedger ledger = new OccupancyLedger();
    for (int second = 0; second < 60; second++) {
      ledger.tick(List.of());
    }
    assertFalse(ledger.pending());
    assertEquals(0L, ledger.drainSeconds());
    assertTrue(ledger.drainPlayerSeconds().isEmpty());
  }

  @Test
  void aNullRosterPausesTooRatherThanThrowing() {
    OccupancyLedger ledger = new OccupancyLedger();
    ledger.tick(null);
    assertFalse(ledger.pending());
  }

  @Test
  void oneSecondPerTickForOnePlayer() {
    OccupancyLedger ledger = new OccupancyLedger();
    for (int second = 0; second < 10; second++) {
      ledger.tick(List.of(ASHU));
    }
    assertEquals(10L, ledger.drainSeconds());
  }

  @Test
  void theTotalIsWallClockNotTheSumOfPlayerTime() {
    // Three players together for ten seconds is TEN seconds of run and thirty of player time. Both are
    // kept because neither can be derived from the other.
    OccupancyLedger ledger = new OccupancyLedger();
    for (int second = 0; second < 10; second++) {
      ledger.tick(List.of(ASHU, BOB, CID));
    }
    assertEquals(10L, ledger.drainSeconds());
    Map<UUID, Long> perPlayer = ledger.drainPlayerSeconds();
    assertEquals(10L, perPlayer.get(ASHU));
    assertEquals(10L, perPlayer.get(BOB));
    assertEquals(10L, perPlayer.get(CID));
  }

  @Test
  void playersAccrueOnlyTheSecondsTheyWerePresentFor() {
    OccupancyLedger ledger = new OccupancyLedger();
    for (int second = 0; second < 10; second++) {
      ledger.tick(List.of(ASHU));
    }
    for (int second = 0; second < 6; second++) {
      ledger.tick(List.of(ASHU, BOB));
    }
    assertEquals(16L, ledger.drainSeconds());
    Map<UUID, Long> perPlayer = ledger.drainPlayerSeconds();
    assertEquals(16L, perPlayer.get(ASHU));
    assertEquals(6L, perPlayer.get(BOB));
    assertFalse(perPlayer.containsKey(CID));
  }

  @Test
  void aRosterOfOnlyNullsIsAnEmptyRoster() {
    OccupancyLedger ledger = new OccupancyLedger();
    List<UUID> roster = new ArrayList<>();
    roster.add(null);
    ledger.tick(roster);
    assertFalse(ledger.pending());
    assertEquals(0L, ledger.drainSeconds());
  }

  @Test
  void drainingEmptiesTheBufferSoSecondsAreNeverCommittedTwice() {
    OccupancyLedger ledger = new OccupancyLedger();
    ledger.tick(List.of(ASHU));
    ledger.tick(List.of(ASHU));
    assertTrue(ledger.pending());
    assertEquals(2L, ledger.drainSeconds());
    assertEquals(2L, ledger.drainPlayerSeconds().get(ASHU));
    assertFalse(ledger.pending());
    assertEquals(0L, ledger.drainSeconds());
    assertTrue(ledger.drainPlayerSeconds().isEmpty());
  }

  @Test
  void accrualContinuesAcrossADrain() {
    // The buffer is drained while a run keeps going — and, during a world regeneration, is deliberately
    // NOT drained for a few seconds. Either way the next tick keeps counting from where it was.
    OccupancyLedger ledger = new OccupancyLedger();
    ledger.tick(List.of(ASHU));
    ledger.drainSeconds();
    ledger.drainPlayerSeconds();
    ledger.tick(List.of(ASHU));
    assertEquals(1L, ledger.drainSeconds());
    assertEquals(1L, ledger.drainPlayerSeconds().get(ASHU));
  }
}
