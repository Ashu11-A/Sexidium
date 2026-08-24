package com.sexidium.core.game.hardcore;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The single resolution of "what does this selection demand of hardcore". Everything that used to answer
 * that question separately — the builder tile, the manage tile, the setup, the death handler — now reads
 * this, so these cases are the contract they all share.
 */
class HardcoreDemandTest {

  private record Fake(boolean requiresHardcore, HardcoreDeathOutcome deathOutcome, String displayName)
      implements HardcoreDemand.Source {
  }

  @Test
  void nothingSelected_leavesTheChoiceEntirelyToTheOwner() {
    assertSame(HardcoreDemand.NONE, HardcoreDemand.of(null));
    assertSame(HardcoreDemand.NONE, HardcoreDemand.of(List.of()));

    HardcoreDemand none = HardcoreDemand.NONE;
    assertFalse(none.required());
    assertTrue(none.ownerMayChoose());
    assertFalse(none.appliesTo(false), "an owner who wants it off gets it off");
    assertTrue(none.appliesTo(true), "…and an owner who wants it on still gets it on");
  }

  @Test
  void ordinaryChallengesNeverForceTheStakesOrClaimTheDeath() {
    HardcoreDemand demand = HardcoreDemand.of(List.of(
        new Fake(false, null, "Double Drops"),
        new Fake(false, null, "Cleave")));

    assertSame(HardcoreDemand.NONE, demand, "no opinion anywhere must resolve to the shared NONE");
  }

  /**
   * The case behind the reported bug: the tile said "Hardcore: OFF — click to turn it on" for an
   * experience that was already running hardcore and whose service refused to change it.
   */
  @Test
  void aRequiringChallengeTakesTheChoiceAwayAndSaysWhy() {
    HardcoreDemand demand = HardcoreDemand.of(List.of(
        new Fake(false, null, "Double Drops"),
        new Fake(true, HardcoreDeathOutcome.RESET_WORLD, "Death Resets")));

    assertTrue(demand.required());
    assertFalse(demand.ownerMayChoose(), "the tile must render locked, not as an off switch");
    assertEquals("Death Resets", demand.reason(), "the player is told which twist did this");
    assertTrue(demand.appliesTo(false),
        "an owner who never ticked hardcore still gets it — that is what 'required' means");
    assertTrue(demand.appliesTo(true));
  }

  @Test
  void theFirstOpinionOnADeathWins_soTwoChallengesCannotFightOverOne() {
    HardcoreDemand demand = HardcoreDemand.of(List.of(
        new Fake(true, HardcoreDeathOutcome.RESET_WORLD, "Death Resets"),
        new Fake(true, HardcoreDeathOutcome.LOSE_WORLD, "Something Else")));

    assertEquals(HardcoreDeathOutcome.RESET_WORLD, demand.outcome());
    assertEquals("Death Resets", demand.reason(), "and the first requirer is the one named");
  }

  @Test
  void anOutcomeWithoutARequirement_isCarriedButLeavesTheChoiceAlone() {
    // A mode may say what a death costs IF hardcore is on, without insisting that it is on.
    HardcoreDemand demand = HardcoreDemand.of(List.of(
        new Fake(false, HardcoreDeathOutcome.LOSE_WORLD, "Optional Stakes")));

    assertFalse(demand.required());
    assertTrue(demand.ownerMayChoose());
    assertEquals(HardcoreDeathOutcome.LOSE_WORLD, demand.outcome());
    assertNull(demand.reason(), "nothing forced anything, so there is nobody to blame");
  }

  @Test
  void outcomeOr_leavesTheCallersOwnDefaultToTheCaller() {
    assertEquals(HardcoreDeathOutcome.LOSE_WORLD,
        HardcoreDemand.NONE.outcomeOr(HardcoreDeathOutcome.LOSE_WORLD));
    assertEquals(HardcoreDeathOutcome.RESET_WORLD,
        HardcoreDemand.of(List.of(new Fake(true, HardcoreDeathOutcome.RESET_WORLD, "Death Resets")))
            .outcomeOr(HardcoreDeathOutcome.LOSE_WORLD));
  }
}
