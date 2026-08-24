package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.Branding;
import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;

import java.util.List;

/**
 * Slot 0 — the compass "Menu". Opens the player's own lobby/group screen when they already have an
 * active lobby, otherwise the main hub (so the compass is never a dead end for a solo player).
 */
public final class MenuNavItem extends HotbarItem {
  @Override
  public String id() {
    return "menu";
  }

  @Override
  public int slot() {
    return 0;
  }

  @Override
  public UiItem build(HotbarContext context) {
    return new UiItem(ItemKey.minecraft("compass"), 1, "<aqua><bold>Menu</bold></aqua>",
        List.of("<gray>Open the " + Branding.label(context.server().configuration()) + " menu</gray>"),
        null, MenuArt.model(MenuArt.ICON_NAV_MENU));
  }

  @Override
  public void onClick(HotbarContext context) {
    PlayerAdapter player = context.player();
    if (context.lobbies() != null && context.lobbies().hasActiveLobby(player.uniqueId())) {
      context.menus().openLobby(player);
    } else {
      context.menus().openMain(player);
    }
  }
}
