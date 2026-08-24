package com.sexidium.core.decor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.menu.MenuArt;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Guards the decor styling tables against drift, mirroring {@code MenuArtCoverageTest}: every registered
 * minigame mode must resolve a bespoke podium {@code item_model} ({@link MenuArt#modeModel}) AND a curated
 * base item + glow color ({@link DecorPalette}). A new mode therefore cannot silently fall back to the
 * generic decor look — it fails the build until the palette is filled in.
 */
class DecorPaletteCoverageTest {

  private static Set<String> registeredMinigameIds() {
    GameRegistry registry = CoreGameRegistryInitializer.create();
    return registry.descriptors().stream()
        .filter(descriptor -> CoreGameRegistryInitializer.CATEGORY_MINIGAMES.equals(descriptor.category()))
        .map(GameModeDescriptor::modeId)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  @Test
  void everyMinigameHasACuratedPodiumStyle() {
    for (String modeId : registeredMinigameIds()) {
      assertNotNull(MenuArt.modeModel(modeId),
          "minigame '" + modeId + "' is missing a podium item_model in MenuArt");
      assertTrue(DecorPalette.hasCurated(modeId),
          "minigame '" + modeId + "' is missing a base item / glow in DecorPalette");
    }
  }

  @Test
  void unknownModeStillResolvesNonNullDefaults() {
    assertNotNull(DecorPalette.baseItem("does-not-exist"));
    // glowArgb returns a primitive int (opaque white default) — non-null by construction.
    assertTrue((DecorPalette.glowArgb("does-not-exist") & 0xFF000000) != 0);
  }
}
