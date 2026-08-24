package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;

import java.util.List;

/**
 * Slot 8 (any member of a managed lobby) — leave the lobby, or disband it when the viewer is the host.
 * Present for both the owner and invited members, so anyone can back out; leaving reverts their hotbar to
 * the default lobby items on the next refresh.
 */
public final class LobbyLeaveItem extends HotbarItem {
  @Override
  public String id() {
    return "lobby-leave";
  }

  @Override
  public int slot() {
    return 8;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    return context.managingLobby();
  }

  @Override
  public UiItem build(HotbarContext context) {
    boolean leader = context.leadsLobby();
    return UiItem.of(ItemKey.minecraft("barrier"),
        leader ? "<red><bold>Disband lobby</bold></red>" : "<red><bold>Leave lobby</bold></red>",
        List.of(leader ? "<gray>Close this lobby for everyone</gray>" : "<gray>Leave this lobby</gray>"));
  }

  @Override
  public void onClick(HotbarContext context) {
    if (context.lobbies() == null) {
      return;
    }
    if (context.leadsLobby()) {
      context.lobbies().disband(context.player().uniqueId());
      context.player().sendActionBar("<yellow>Lobby disbanded.</yellow>");
    } else {
      context.lobbies().leave(context.player());
      context.player().sendActionBar("<yellow>You left the lobby.</yellow>");
    }
  }
}
