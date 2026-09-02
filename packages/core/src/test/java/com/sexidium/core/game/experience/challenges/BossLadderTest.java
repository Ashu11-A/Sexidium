package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.hud.HudElement;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boss checklist Death Resets draws, held to the two things that are silent when they break: the
 * order the rungs are asked for in, and the exactness of the match that ticks one off.
 */
class BossLadderTest {

  @Test
  void theLadderIsTheRouteTheModeAsksFor() {
    assertEquals(
        List.of(BossLadder.ELDER_GUARDIAN, BossLadder.WARDEN, BossLadder.WITHER, BossLadder.ENDER_DRAGON),
        BossLadder.ORDER,
        "declaration order is the route and the draw order; reordering it reorders the checklist");
    assertEquals(4, BossLadder.total());
  }

  /**
   * The platform decides how it spells an entity type. Paper sends {@code EntityType#name()}, which is
   * SCREAMING_SNAKE and unnamespaced; a namespaced id is what most other things in the ecosystem use.
   * Both have to land on the same rung, or the checklist ticks on one server and not another.
   */
  @Test
  void aRungIsFoundWhateverTheDeathIsSpelledAs() {
    for (String spelling : List.of("ENDER_DRAGON", "ender_dragon", "minecraft:ender_dragon",
        "minecraft:ENDER_DRAGON", "  ender_dragon  ")) {
      assertEquals(BossLadder.ENDER_DRAGON, BossLadder.match(spelling).orElse(null),
          "'" + spelling + "' names the Ender Dragon");
    }
  }

  /**
   * The match is exact, and this is the test that says why it has to be. Every one of these shares a
   * word with a boss on the list and is not that boss — a prefix or contains match would tick the
   * Wither off the first time somebody killed a wither skeleton in a fortress, which in a mode where
   * the whole list is lost on a death is a lie about the run's only progress.
   */
  @Test
  void aLookalikeIsNotABoss() {
    for (String impostor : List.of("WITHER_SKELETON", "WITHER_SKULL", "GUARDIAN", "ENDERMAN",
        "ENDERMITE", "ZOMBIE", "minecraft:wither_skeleton", "", "   ")) {
      assertTrue(BossLadder.match(impostor).isEmpty(), "'" + impostor + "' is not a boss on the list");
    }
    assertTrue(BossLadder.match(null).isEmpty());
  }

  /**
   * State keys and row keys are deliberately spelled differently and must stay that way: one names a
   * column in a saved file, the other is embedded in a BetterHud placeholder where a dot is a
   * character to be nowhere near another plugin's parser.
   */
  @Test
  void aRungNamesItsStateColumnAndItsRowSeparately() {
    assertEquals("boss.ender_dragon", BossLadder.ENDER_DRAGON.stateKey());
    assertEquals("boss_ender_dragon", BossLadder.ENDER_DRAGON.rowKey());
    for (BossLadder boss : BossLadder.ORDER) {
      assertNotNull(boss.displayName(), boss + " needs a name a player can read");
      assertTrue(boss.rowKey().chars().noneMatch(c -> c == '.' || Character.isWhitespace(c)),
          boss + "'s row key becomes a placeholder argument");
    }
  }

  /**
   * The whole ladder reaches the screen, in order, beneath the counters — the declaration the driver
   * stack compiles for the corner overlay AND renders on the sidebar for everyone it cannot reach. A
   * rung added to the enum and forgotten here would be tracked and never drawn.
   */
  @Test
  void everyRungGetsARowOnTheReadout() {
    List<String> keys = new ArrayList<>();
    for (HudElement element : DeathResetsChallenge.readoutSpec().elements()) {
      if (element.key() != null) {
        keys.add(element.key());
      }
    }

    assertEquals(List.of("duration", "days", "resets",
            "boss_elder_guardian", "boss_warden", "boss_wither", "boss_ender_dragon"),
        keys,
        "the counters first, then the ladder in its own order, and no tally row between them");
  }

  /**
   * No tally row on the readout, deliberately. Four lines that already show which rungs are ticked do
   * not also need a number saying how many — on a corner readout that is a fifth line of reading for a
   * fact the reader can already see. The count lives where it is worth words: the announcement when a
   * boss falls, and the chat listing.
   */
  @Test
  void theReadoutDoesNotRepeatTheTallyItAlreadyShows() {
    for (HudElement element : DeathResetsChallenge.readoutSpec().elements()) {
      assertTrue(element.key() == null || !"bosses".equals(element.key()),
          "the checklist speaks for itself; a tally row is one more line to read for nothing");
    }
  }

  /**
   * A spacer between the counters and the checklist, and it has to be a spacer rather than a blank row:
   * the two halves answer different questions and need to read as two blocks, but a blank row would
   * cost a line on the sidebar fallback for a gap only the pixel-addressed overlay can use.
   */
  @Test
  void theCountersAndTheChecklistAreTwoBlocks() {
    HudSurfaceSpec spec = DeathResetsChallenge.readoutSpec();
    int spacerIndex = -1;
    for (int i = 0; i < spec.elements().size(); i++) {
      if (spec.elements().get(i) instanceof HudElement.Spacer) {
        spacerIndex = i;
      }
    }

    assertEquals(3, spacerIndex, "the gap sits after the three counters and before the checklist");
  }

  /**
   * A command names a rung the way a human types it. The enum spells ids with underscores; nobody
   * types that reliably, so the hyphen and the run-together forms have to land on the same rung.
   */
  @Test
  void aCommandCanNameARungTheWayAHumanTypesIt() {
    for (String typed : List.of("ender_dragon", "ender-dragon", "enderdragon", "ENDER_DRAGON",
        "Ender-Dragon")) {
      assertEquals(BossLadder.ENDER_DRAGON, BossLadder.byId(typed).orElse(null),
          "'" + typed + "' should name the Ender Dragon");
    }
    assertTrue(BossLadder.byId("dragon").isEmpty(), "a partial name is not a rung");
    assertTrue(BossLadder.byId(null).isEmpty());
  }

  /**
   * The three facts about a rung live under three keys, and the two detail keys hang off the flag's
   * name. The flag is what the per-second publish reads; burying it in a parsed record would put a
   * string split on the render path for every rung, every second.
   */
  @Test
  void aRungRecordsWhetherWhenAndHowFarIn() {
    assertEquals("boss.warden", BossLadder.WARDEN.stateKey());
    assertEquals("boss.warden.at", BossLadder.WARDEN.stateKeyAt());
    assertEquals("boss.warden.played", BossLadder.WARDEN.stateKeyPlayed());
  }

  /**
   * Nothing on this surface animates, and that is a decision rather than an oversight. An animated
   * surface is republished EVERY TICK for as long as it is worn — see {@code HudSurfaceSpec#animated}
   * and the driver's second pass — and this one is worn by every player for an entire match, to pop a
   * tally that changes at most four times per world. The chat announcement carries that moment for
   * free.
   */
  @Test
  void theReadoutDoesNotPayForAnAnimationItWouldUseFourTimes() {
    assertTrue(!DeathResetsChallenge.readoutSpec().animated(),
        "a worn readout must not put the overlay driver on the per-tick pass");
  }
}
