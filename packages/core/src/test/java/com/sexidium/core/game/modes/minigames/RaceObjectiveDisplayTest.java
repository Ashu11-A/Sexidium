package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.modes.minigames.race.RaceObjective;
import com.sexidium.core.platform.model.ItemKey;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the Race objective label respects each client's language: a default-named item renders as a
 * translatable {@code <lang:item.*>} component (which every client localizes itself), while an explicitly
 * named item keeps its literal server-side name. This is the translation path the in-game Race board uses.
 */
class RaceObjectiveDisplayTest {
  // Stand-in for ServerAdapter.itemTranslationKey: resolves a vanilla translation key for known items.
  private final RaceObjectiveDisplay display = new RaceObjectiveDisplay(itemKey ->
      "iron_ingot".equals(itemKey.value()) ? "item.minecraft.iron_ingot" : "");

  @Test
  void defaultNamedItem_rendersAsClientLocalizedLangComponent() {
    RaceObjective objective = RaceObjective.item("common", ItemKey.minecraft("iron_ingot"), 1, 1, null, false);

    assertEquals("<lang:item.minecraft.iron_ingot>", display.objectiveDisplayMini(objective));
  }

  @Test
  void explicitlyNamedItem_keepsItsLiteralName() {
    RaceObjective objective = RaceObjective.item("common", ItemKey.minecraft("iron_ingot"), 1, 1, "Shiny Bar", true);

    String mini = display.objectiveDisplayMini(objective);
    assertEquals("Shiny Bar", mini);
    assertTrue(!mini.contains("<lang:"), "an explicit name is not turned into a translatable");
  }

  @Test
  void unresolvableItem_fallsBackToItemKeyValue() {
    RaceObjective objective = RaceObjective.item("common", ItemKey.minecraft("mystery_block"), 1, 1, null, false);

    // No translation key resolved -> falls back to the plain item id, never a broken <lang:> tag.
    assertEquals("mystery_block", display.objectiveDisplayMini(objective));
  }
}
