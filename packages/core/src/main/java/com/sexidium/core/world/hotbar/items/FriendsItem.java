package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;

import java.util.List;

/**
 * Slot 4 — the "Friends" head: opens a roster of the player's friends showing where each one is right
 * now (their experience / minigame / lobby). Tapping a friend who is inside an experience joins it (via
 * the existing friend-join system); tapping a friend standing in a lobby teleports the viewer to them,
 * even across different lobby worlds. The icon wears the viewer's own skin so it reads as a "people"
 * button on Bedrock and Java alike. The roster is an ordinary chest {@code MenuView}, so it inherits the
 * whole unified chest system for free.
 */
public final class FriendsItem extends HotbarItem {
  @Override
  public String id() {
    return "friends";
  }

  @Override
  public int slot() {
    return 4;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    return !context.managingLobby();
  }

  @Override
  public UiItem build(HotbarContext context) {
    return new UiItem(ItemKey.minecraft("player_head"), 1, "<green><bold>Friends</bold></green>",
        List.of("<gray>Warp to a friend, or join their world</gray>", "<gray>Works across lobby worlds</gray>"),
        context.player().uniqueId(), null);
  }

  @Override
  public void onClick(HotbarContext context) {
    context.menus().openFriendsWarp(context.player());
  }
}
