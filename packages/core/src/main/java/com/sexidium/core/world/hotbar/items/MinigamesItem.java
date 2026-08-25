package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;

import java.util.List;

/** Slot 0 (Hotbar 1) — the diamond-sword "Minigames": jumps straight to the competitive mode grid. */
public final class MinigamesItem extends HotbarItem {
  private static final String TITLE = "<aqua><bold>Minigames</bold></aqua>";

  @Override
  public String id() {
    return "minigames";
  }

  @Override
  public int slot() {
    return 0;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    return !context.managingLobby();
  }

  @Override
  public UiItem build(HotbarContext context) {
    return new UiItem(ItemKey.minecraft("diamond_sword"), 1, TITLE,
        List.of("<gray>Browse competitive game modes</gray>"), null, MenuArt.model(MenuArt.ICON_MINIGAMES));
  }

  @Override
  public void onClick(HotbarContext context) {
    context.menus().openCategory(context.player(), "minigames", TITLE);
  }
}
