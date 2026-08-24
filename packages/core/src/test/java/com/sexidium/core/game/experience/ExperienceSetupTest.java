package com.sexidium.core.game.experience;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the per-experience options that are not challenges — the map type and the keep-inventory rule —
 * and the mode-arg transport that carries them into a running match (and therefore across a restart).
 */
class ExperienceSetupTest {

  @Test
  void keepInventoryIsOnByDefault() {
    assertTrue(ExperienceSetup.DEFAULT.keepInventory());
    assertEquals(ExperienceWorldType.NORMAL, ExperienceSetup.DEFAULT.worldType());
    // …and an experience whose args predate the toggle reads as ON, never OFF.
    assertTrue(ExperienceSetup.fromArgs(List.of("doubledrops")).keepInventory());
    assertTrue(ExperienceSetup.fromArgs(List.of("doubledrops", "world:nether")).keepInventory());
    assertTrue(ExperienceSetup.fromArgs(null).keepInventory());
  }

  @Test
  void bothOptionsSurviveTheModeArgsRoundTrip() {
    ExperienceSetup setup = new ExperienceSetup(ExperienceWorldType.END, false);
    List<String> args = setup.toModeArgs(List.of("doubledrops", "cleave"));

    assertEquals(List.of("doubledrops", "cleave", "world:end", "keepinv:false", "hardcore:false"), args);
    assertEquals(setup, ExperienceSetup.fromArgs(args));
    // Only challenge ids come back out — an option token must never be mistaken for one.
    assertEquals(List.of("doubledrops", "cleave"), ExperienceSetup.stripArgs(args));
  }

  @Test
  void optionTokensAreNeverTreatedAsChallenges() {
    List<String> args = ExperienceSetup.DEFAULT.toModeArgs(List.of("doubledrops"));
    assertTrue(ExperienceSetup.isOption("world:nether"));
    assertTrue(ExperienceSetup.isOption("keepinv:false"));
    assertTrue(ExperienceSetup.isOption("hardcore:true"));
    assertFalse(ExperienceSetup.isOption("doubledrops"));
    // The catalog resolves the args to exactly one challenge, ignoring both tokens.
    assertEquals(1, ChallengeCatalog.create(args).size());
  }

  @Test
  void hardcoreSurvivesTheRoundTripAndDefaultsOff() {
    // Hardcore must never be something a player ends up in by accident, so absence means OFF.
    assertFalse(ExperienceSetup.DEFAULT.hardcore());
    assertFalse(ExperienceSetup.fromArgs(List.of("doubledrops", "world:normal", "keepinv:true")).hardcore());

    ExperienceSetup hardcore = ExperienceSetup.DEFAULT.withHardcore(true);
    List<String> args = hardcore.toModeArgs(List.of("cleave"));
    assertTrue(args.contains("hardcore:true"));
    assertEquals(hardcore, ExperienceSetup.fromArgs(args));
    assertEquals(List.of("cleave"), ExperienceSetup.stripArgs(args));
    // …and flipping one option never disturbs the others.
    assertTrue(hardcore.withKeepInventory(false).hardcore());
    assertEquals(ExperienceWorldType.NORMAL, hardcore.withKeepInventory(false).worldType());
  }

  @Test
  void reEncodingIsStable() {
    // A setup read back out of its own args must re-encode identically (no token duplication).
    ExperienceSetup setup = new ExperienceSetup(ExperienceWorldType.NETHER, false);
    List<String> once = setup.toModeArgs(List.of("cleave"));
    List<String> twice = ExperienceSetup.fromArgs(once).toModeArgs(once);
    assertEquals(once, twice);
  }

  @Test
  void aGeneratedMapStillContributesItsChallenge() {
    List<String> args = new ExperienceSetup(ExperienceWorldType.CLASSIC_SKYBLOCK, false).toModeArgs(List.of());
    assertTrue(args.contains("classicskyblock"));
    assertFalse(ExperienceSetup.fromArgs(args).keepInventory());
  }

  @Test
  void withersKeepTheOtherOption() {
    ExperienceSetup setup = ExperienceSetup.DEFAULT.withWorldType(ExperienceWorldType.NETHER);
    assertTrue(setup.keepInventory());
    assertFalse(setup.withKeepInventory(false).keepInventory());
    assertEquals(ExperienceWorldType.NETHER, setup.withKeepInventory(false).worldType());
  }
}
