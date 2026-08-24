package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.game.experience.ChallengeCatalog;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Guards the menu-art catalog against drift: every registered minigame and every selectable experience
 * challenge must ship a bespoke icon, and {@link MenuArt} must not declare art for a mode/challenge that
 * no longer exists. {@code SexidiumResourcePackTest} already proves every declared id ships a PNG, so
 * together they make a missing in-game texture a red build, never a broken {@code item_model} at runtime.
 */
class MenuArtCoverageTest {

  private static Set<String> registeredMinigameIds() {
    GameRegistry registry = CoreGameRegistryInitializer.create();
    return registry.descriptors().stream()
        .filter(d -> CoreGameRegistryInitializer.CATEGORY_MINIGAMES.equals(d.category()))
        .map(GameModeDescriptor::modeId)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  private static Set<String> challengeIds() {
    return ChallengeCatalog.available().stream()
        .map(ChallengeCatalog.Entry::id)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  @Test
  void everyMinigameHasABespokeIcon() {
    for (String modeId : registeredMinigameIds()) {
      assertNotNull(MenuArt.modeModel(modeId), "minigame '" + modeId + "' is missing a mode_ icon in MenuArt");
    }
  }

  @Test
  void everyChallengeHasABespokeIcon() {
    for (String challengeId : challengeIds()) {
      assertNotNull(MenuArt.challengeModel(challengeId),
          "challenge '" + challengeId + "' is missing a challenge_ icon in MenuArt");
    }
  }

  @Test
  void modeIconTableMatchesTheRegistryExactly() {
    Set<String> declared = new TreeSet<>(List.of(MenuArt.MODE_ICON_IDS));
    assertEquals(registeredMinigameIds(), declared,
        "MenuArt.MODE_ICON_IDS must mirror the registered minigames (add/remove the icon + texture too)");
  }

  @Test
  void challengeIconTableMatchesTheCatalogExactly() {
    Set<String> declared = new TreeSet<>(List.of(MenuArt.CHALLENGE_ICON_IDS));
    assertEquals(challengeIds(), declared,
        "MenuArt.CHALLENGE_ICON_IDS must mirror ChallengeCatalog (add/remove the icon + texture too)");
  }

  @Test
  void modelHelpersResolveToTheNamespacedItemModel() {
    // Each minigame/challenge maps onto a <section>/<name> icon from the assets/icons set (MenuArt tables).
    assertEquals("sexidium:" + "minigames/race", MenuArt.modeModel("race"));
    assertEquals("sexidium:" + "experiences/chained", MenuArt.challengeModel("chained"));
    // Greyscale "disabled" twin for an experience twist; shrinkingachievements has no bespoke sprite → null.
    assertEquals("sexidium:" + "experiences/chained_disabled", MenuArt.challengeModelDisabled("chained"));
    assertNull(MenuArt.challengeModelDisabled("shrinkingachievements"));
    // Unknown ids return null so a caller's withModel(...) cleanly clears rather than pointing at art
    // that the pack never ships.
    assertNull(MenuArt.modeModel("not-a-mode"));
    assertNull(MenuArt.challengeModel("not-a-challenge"));
    assertTrue(MenuArt.hasIcon(MenuArt.ICON_NAV_MENU));
  }
}
