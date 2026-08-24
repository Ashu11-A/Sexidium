package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.data.FriendService;
import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;

import java.util.List;

/**
 * Slot 8 — the conditional "Friend Requests" indicator. It appears <i>only</i> when the player has one
 * or more incoming friend requests (so a clean hotbar means no pending requests), badges the count as
 * the stack size, and opens the Invites inbox — where each request has separate single-tap Accept /
 * Decline buttons — on click.
 */
public final class FriendRequestsItem extends HotbarItem {
  @Override
  public String id() {
    return "friend-requests";
  }

  @Override
  public int slot() {
    return 8;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    // Hidden while managing a lobby (that context uses slot 8 for the leave/disband tool).
    if (context.managingLobby()) {
      return false;
    }
    FriendService friends = context.friends();
    return friends != null && !friends.incomingRequests(context.player().uniqueId()).isEmpty();
  }

  @Override
  public UiItem build(HotbarContext context) {
    FriendService friends = context.friends();
    int count = friends == null ? 0 : friends.incomingRequests(context.player().uniqueId()).size();
    int badge = Math.max(1, Math.min(64, count));
    return new UiItem(ItemKey.minecraft("writable_book"), badge, "<light_purple><bold>Friend Requests</bold></light_purple>",
        List.of("<aqua>" + count + " pending request(s)</aqua>", "<yellow>Tap to accept or decline</yellow>"),
        null, MenuArt.model(MenuArt.ICON_INVITES));
  }

  @Override
  public void onClick(HotbarContext context) {
    context.menus().openInvites(context.player());
  }
}
