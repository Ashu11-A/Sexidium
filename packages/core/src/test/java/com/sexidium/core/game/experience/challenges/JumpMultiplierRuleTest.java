package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.DuplicableKind;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Jump-Multiplies caps, which are the part of an exponential mode actually worth locking down: a cap
 * that is one-off in the wrong direction is the difference between a funny run and a dead server.
 */
class JumpMultiplierRuleTest {
  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID OTHER = UUID.randomUUID();

  private static JumpMultiplierRule ruleAt(AtomicLong clock) {
    return new JumpMultiplierRule(clock::get);
  }

  @Test
  void clonesFor_doublesEveryNearbyEntity_whenNothingIsBinding() {
    // 7 entities, one copy each, generous budgets: the rule wants exactly 7 clones.
    assertEquals(7, JumpMultiplierRule.clonesFor(7, 1, 1024, 7, 4000));
    // Two copies each is a straight multiplier on the same figure.
    assertEquals(14, JumpMultiplierRule.clonesFor(7, 2, 1024, 7, 4000));
  }

  @Test
  void clonesFor_isCappedByThePerJumpBudget_beforeTheCeiling() {
    // 500 entities × 2 copies = 1000 wanted, but one jump may only spend 64.
    assertEquals(64, JumpMultiplierRule.clonesFor(500, 2, 64, 500, 100_000));
  }

  @Test
  void clonesFor_isCappedByTheHeadroomLeftUnderTheLiveCeiling() {
    // 100 entities want 100 clones, but only 10 slots remain below the 4000 ceiling.
    assertEquals(10, JumpMultiplierRule.clonesFor(100, 1, 1024, 3990, 4000));
  }

  @Test
  void clonesFor_isZeroAtTheCeiling_soTheModeRefusesRatherThanCulling() {
    assertEquals(0, JumpMultiplierRule.clonesFor(100, 1, 1024, 4000, 4000));
    assertEquals(0, JumpMultiplierRule.clonesFor(100, 1, 1024, 9999, 4000));
    assertTrue(JumpMultiplierRule.saturated(4000, 4000));
    assertTrue(JumpMultiplierRule.saturated(4001, 4000));
    assertFalse(JumpMultiplierRule.saturated(3999, 4000));
  }

  @Test
  void clonesFor_treatsZeroAsUncapped_forBothTheJumpBudgetAndTheCeiling() {
    assertEquals(200, JumpMultiplierRule.clonesFor(100, 2, 0, 100, 0));
    assertFalse(JumpMultiplierRule.saturated(1_000_000, 0));
  }

  @Test
  void clonesFor_isZeroWithNothingNearby_orNoCopiesAsked() {
    assertEquals(0, JumpMultiplierRule.clonesFor(0, 1, 1024, 0, 4000));
    assertEquals(0, JumpMultiplierRule.clonesFor(-5, 1, 1024, 0, 4000));
    assertEquals(0, JumpMultiplierRule.clonesFor(10, 0, 1024, 10, 4000));
  }

  @Test
  void clonesFor_doesNotOverflow_whenTheProductExceedsAnInt() {
    // The exact shape an exponential mode reaches: 100k entities × 100k copies overflows an int product.
    // The answer must still be the budget, never a negative number.
    assertEquals(1024, JumpMultiplierRule.clonesFor(100_000, 100_000, 1024, 0, 0));
  }

  @Test
  void reach_takesTheSmallerOfTheConfiguredAndVisibleRadius() {
    assertEquals(12.0, JumpMultiplierRule.reach(12.0, 176.0), 1e-9);
    assertEquals(8.0, JumpMultiplierRule.reach(12.0, 8.0), 1e-9);
  }

  @Test
  void reach_keepsTheConfiguredRadius_whenThePlatformCannotReportAViewDistance() {
    assertEquals(12.0, JumpMultiplierRule.reach(12.0, 0.0), 1e-9);
    assertEquals(12.0, JumpMultiplierRule.reach(12.0, -1.0), 1e-9);
    // A negative configured radius is a config error, not a negative reach.
    assertEquals(0.0, JumpMultiplierRule.reach(-4.0, 0.0), 1e-9);
  }

  @Test
  void eligibleKinds_assemblesExactlyTheSwitchesThatAreOn() {
    assertEquals(Set.of(DuplicableKind.MOB, DuplicableKind.ITEM, DuplicableKind.PROJECTILE, DuplicableKind.TNT),
        JumpMultiplierRule.eligibleKinds(true, true, true, true, false));
    assertEquals(Set.of(DuplicableKind.BOSS), JumpMultiplierRule.eligibleKinds(false, false, false, false, true));
    assertTrue(JumpMultiplierRule.eligibleKinds(false, false, false, false, false).isEmpty());
  }

  @Test
  void acceptJump_takesTheFirstJump_andSwallowsARepeatInsideTheCooldown() {
    AtomicLong clock = new AtomicLong(1_000L);
    JumpMultiplierRule rule = ruleAt(clock);

    assertTrue(rule.acceptJump(PLAYER, 250L));
    clock.set(1_100L); // 100ms later — the same physical jump surfacing twice
    assertFalse(rule.acceptJump(PLAYER, 250L));
    clock.set(1_250L); // exactly the cooldown: the debounce is over
    assertTrue(rule.acceptJump(PLAYER, 250L));
  }

  @Test
  void acceptJump_debouncesPerPlayer_soOneJumperNeverBlocksAnother() {
    AtomicLong clock = new AtomicLong(0L);
    JumpMultiplierRule rule = ruleAt(clock);

    assertTrue(rule.acceptJump(PLAYER, 250L));
    assertTrue(rule.acceptJump(OTHER, 250L));
    assertFalse(rule.acceptJump(PLAYER, 250L));
    assertEquals(2, rule.tracked());
  }

  @Test
  void acceptJump_withNoCooldown_takesEveryJump_andIgnoresANullPlayer() {
    AtomicLong clock = new AtomicLong(0L);
    JumpMultiplierRule rule = ruleAt(clock);

    assertTrue(rule.acceptJump(PLAYER, 0L));
    assertTrue(rule.acceptJump(PLAYER, 0L));
    assertFalse(rule.acceptJump(null, 0L));
  }

  @Test
  void forget_dropsTheEntry_soAPlayerWhoLeftCannotLeakOrStayDebounced() {
    AtomicLong clock = new AtomicLong(0L);
    JumpMultiplierRule rule = ruleAt(clock);

    assertTrue(rule.acceptJump(PLAYER, 250L));
    rule.forget(PLAYER);
    assertEquals(0, rule.tracked());
    // Rejoining at the same instant is not still on cooldown.
    assertTrue(rule.acceptJump(PLAYER, 250L));
    rule.forget(null); // tolerated
  }
}
