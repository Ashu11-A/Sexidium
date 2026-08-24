package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;

import java.util.List;

/**
 * Slot 2 (lobby owner) — the invite tool. Right- or left-clicking another <i>player</i> while holding it
 * invites them to the lobby (the platform routes the entity click); clicking air/a block instead opens
 * the click-to-invite roster. Owner-only, so an invited member never has it.
 */
public final class LobbyInviteItem extends HotbarItem {
  /** The routing id the platform tags onto the item, also matched by the entity-click invite handler. */
  public static final String ID = "lobby-invite";

  @Override
  public String id() {
    return ID;
  }

  @Override
  public int slot() {
    return 2;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    return context.managingLobby() && context.leadsLobby();
  }

  @Override
  public UiItem build(HotbarContext context) {
    return UiItem.of(ItemKey.minecraft("name_tag"), "<green><bold>Invite</bold></green>",
        List.of("<gray>Click a player to invite them</gray>", "<gray>Or click to open the invite list</gray>"));
  }

  @Override
  public void onClick(HotbarContext context) {
    // Air/block click (no player targeted): fall back to the click-to-invite roster.
    context.menus().openInviteFromFriends(context.player());
  }
}
