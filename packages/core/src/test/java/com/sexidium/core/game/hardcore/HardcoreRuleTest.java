package com.sexidium.core.game.hardcore;

import com.sexidium.core.game.EntryPolicy;
import com.sexidium.core.platform.model.GameModeType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides what a hardcore death costs. The RESET_WORLD cases matter most: a mode whose
 * deaths regenerate the world must never write "dead" to the ledger, because everything downstream of
 * that flag refuses to touch the experience ever again.
 */
class HardcoreRuleTest {

  /** A ledger that just counts, so the rule can be tested with no database anywhere near it. */
  private static final class CountingLedger implements HardcoreRule.Ledger {
    private boolean lost;
    private int writes;

    @Override
    public boolean lost() {
      return lost;
    }

    @Override
    public void markLost() {
      lost = true;
      writes++;
    }
  }

  private static HardcoreRule enabled(HardcoreDeathOutcome outcome, HardcoreRule.Ledger ledger) {
    HardcoreRule rule = new HardcoreRule(ledger);
    rule.setEnabled(true);
    rule.outcome(outcome);
    return rule;
  }

  @Test
  void loseWorld_onlyTheFirstDeathTakesTheWorld() {
    CountingLedger ledger = new CountingLedger();
    HardcoreRule rule = enabled(HardcoreDeathOutcome.LOSE_WORLD, ledger);

    assertTrue(rule.recordDeath(), "the first death is the one that ends the world");
    assertFalse(rule.recordDeath(), "a second death must not re-announce the ending");
    assertTrue(rule.lost());
    assertTrue(ledger.lost);
  }

  @Test
  void resetWorld_neverEndsTheRunAndNeverWritesToTheLedger() {
    CountingLedger ledger = new CountingLedger();
    HardcoreRule rule = enabled(HardcoreDeathOutcome.RESET_WORLD, ledger);

    assertFalse(rule.recordDeath());
    assertFalse(rule.recordDeath());
    assertFalse(rule.lost(), "a run that continues has not lost its world");
    // The important one: a persisted "dead" flag would lock the owner out of their own experience.
    assertEquals(0, ledger.writes, "a reset-world experience must never be marked dead");
  }

  @Test
  void deathsDoNothingWhileHardcoreIsOff() {
    CountingLedger ledger = new CountingLedger();
    HardcoreRule rule = new HardcoreRule(ledger);
    rule.outcome(HardcoreDeathOutcome.LOSE_WORLD);

    assertFalse(rule.recordDeath());
    assertEquals(0, ledger.writes);
  }

  @Test
  void defaultOutcomeIsLoseWorld_andNullDoesNotOverrideIt() {
    HardcoreRule rule = new HardcoreRule(HardcoreRule.Ledger.MEMORY);
    assertEquals(HardcoreDeathOutcome.LOSE_WORLD, rule.outcome());

    rule.outcome(HardcoreDeathOutcome.RESET_WORLD);
    rule.outcome(null);
    assertEquals(HardcoreDeathOutcome.RESET_WORLD, rule.outcome(), "null means 'no opinion', not 'reset'");
  }

  @Test
  void refreshLost_picksUpAnotherServersLossButNeverClearsOurOwn() {
    CountingLedger ledger = new CountingLedger();
    HardcoreRule rule = enabled(HardcoreDeathOutcome.LOSE_WORLD, ledger);

    rule.refreshLost();
    assertFalse(rule.lost());

    ledger.lost = true;
    rule.refreshLost();
    assertTrue(rule.lost(), "the registry is the source of truth across servers");

    // A ledger that goes unavailable (or forgets the row) must not be able to revive a lost world.
    ledger.lost = false;
    rule.refreshLost();
    assertTrue(rule.lost(), "losing a hardcore world is one-way");
  }

  @Test
  void pollLost_readsTheLedgerOnlyEveryNthPass() {
    CountingLedger ledger = new CountingLedger();
    HardcoreRule rule = enabled(HardcoreDeathOutcome.LOSE_WORLD, ledger);

    assertFalse(rule.pollLost(3));
    assertFalse(rule.pollLost(3));
    assertTrue(rule.pollLost(3), "the third pass is the one that hits the ledger");
    assertFalse(rule.pollLost(3), "and the counter starts again");
  }

  @Test
  void pollLost_doesNothingForAWorldThatCannotBeLost() {
    HardcoreRule softWorld = new HardcoreRule(new CountingLedger());
    for (int pass = 0; pass < 10; pass++) {
      assertFalse(softWorld.pollLost(1), "a non-hardcore world has no lost flag to poll");
    }
  }

  @Test
  void entryPolicy_hardcoreHeartsWhileAlive_lockedSpectatorOnceLost() {
    EntryPolicy live = HardcoreRule.entryPolicyFor(true, false, null);
    assertEquals(GameModeType.SURVIVAL, live.gameMode());
    assertTrue(live.hardcoreView());
    assertTrue(live.enforced(),
        "a live hardcore world must stay PLAYABLE: vanilla respawns hardcore players into spectator, "
            + "after the event this code can react to, so one application is not enough");

    EntryPolicy lost = HardcoreRule.entryPolicyFor(true, true, null);
    assertEquals(GameModeType.SPECTATOR, lost.gameMode());
    assertTrue(lost.enforced(), "a lost world must stay unplayable, not just start that way");

    EntryPolicy soft = HardcoreRule.entryPolicyFor(false, false, null);
    assertEquals(GameModeType.SURVIVAL, soft.gameMode());
    assertFalse(soft.hardcoreView());
    assertFalse(soft.enforced(),
        "nothing fights over the mode in a non-hardcore world, so leave an operator's /gamemode alone");
  }
}
