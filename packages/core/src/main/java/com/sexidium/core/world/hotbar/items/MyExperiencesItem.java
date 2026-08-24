package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;

import java.util.List;

/** Slot 1 — the ender-chest "My Experiences": opens the player's own experience worlds list. */
public final class MyExperiencesItem extends HotbarItem {
  @Override
  public String id() {
    return "experiences";
  }

  @Override
  public int slot() {
    return 1;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    return !context.managingLobby();
  }

  @Override
  public UiItem build(HotbarContext context) {
    return new UiItem(ItemKey.minecraft("ender_chest"), 1, "<yellow><bold>My Experiences</bold></yellow>",
        List.of("<gray>Worlds you created</gray>"), null, MenuArt.model(MenuArt.ICON_NAV_EXPERIENCES));
  }

  @Override
  public void onClick(HotbarContext context) {
    context.menus().openExperiences(context.player());
  }
}
