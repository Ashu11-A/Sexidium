package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;

import org.junit.jupiter.api.Test;

class MenuButtonTest {
  private static final ItemKey ICON = ItemKey.minecraft("stone");

  @Test
  void factoriesLeaveModelUnset() {
    assertNull(MenuButton.of(ICON, "x", ctx -> {}).model());
    assertNull(MenuButton.label(ICON, "x", List.of()).model());
  }

  @Test
  void withModelSetsAndPreservesEverythingElse() {
    MenuButton base = MenuButton.of(ICON, "Lobby", List.of("line"), ctx -> {});
    MenuButton themed = base.withModel("sexidium:menu/lobby");
    assertEquals("sexidium:menu/lobby", themed.model());
    assertEquals(base.name(), themed.name());
    assertEquals(base.lore(), themed.lore());
    assertEquals(base.icon(), themed.icon());
    assertEquals(base.onClick(), themed.onClick());
  }

  @Test
  void blankModelNormalizesToNull() {
    assertNull(MenuButton.of(ICON, "x", ctx -> {}).withModel("   ").model());
    assertNull(new MenuButton(ICON, 1, "x", List.of(), null, null, "").model());
  }
}
