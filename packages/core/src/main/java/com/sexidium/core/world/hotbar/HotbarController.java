package com.sexidium.core.world.hotbar;

import com.sexidium.core.data.FriendService;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.menu.MenuService;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.world.hotbar.items.FriendRequestsItem;
import com.sexidium.core.world.hotbar.items.FriendsItem;
import com.sexidium.core.world.hotbar.items.LobbyInviteItem;
import com.sexidium.core.world.hotbar.items.LobbyLeaveItem;
import com.sexidium.core.world.hotbar.items.LobbyStartItem;
import com.sexidium.core.world.hotbar.items.LobbyTeamColorItem;
import com.sexidium.core.world.hotbar.items.LobbyTeamCountItem;
import com.sexidium.core.world.hotbar.items.MinigamesItem;
import com.sexidium.core.world.hotbar.items.MyExperiencesItem;
import com.sexidium.core.world.lobby.LobbyManager;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The single, platform-agnostic authority over a player's dynamic hotbar. It owns one
 * {@link HotbarProfile} per {@link HotbarScope} and, given a player and their current scope,
 * {@link #resolve}s the currently-visible items into concrete {@link HotbarSlot}s that the platform
 * renders. Behaviour lives on the {@link HotbarItem}s; the platform only renders the resolved slots and,
 * on a click, forwards the item's id back to {@link #handleClick}.
 *
 * <p>Only the {@link HotbarScope#LOBBY} profile carries items, so resolving for any other scope returns
 * an empty list — the platform then strips managed slots, which is exactly why lobby items can never
 * enter a match (and vice versa). To add a lobby item, subclass {@link HotbarItem} and add it to the
 * LOBBY profile here; the platform picks it up with no adapter change. See
 * {@code docs/interface/ui-interaction-system.md}.</p>
 */
public final class HotbarController {
  private static final HotbarProfile EMPTY = new HotbarProfile(List.of());

  private final HotbarServices services;
  private final Map<HotbarScope, HotbarProfile> profiles = new EnumMap<>(HotbarScope.class);

  public HotbarController(ServerAdapter server, GameManager games, LobbyManager lobbies,
      FriendService friends, MenuService menus) {
    this.services = new HotbarServices(server, games, lobbies, friends, menus);
    // The lobby hotbar. Each item decides its own visibility per context, so the same profile renders the
    // default lobby items for a solo player AND swaps to the lobby-management tools (team controls, invite,
    // start, per-team colour picks, leave) once the player configures/joins a match lobby.
    profiles.put(HotbarScope.LOBBY, new HotbarProfile(List.of(
        // Default (not-managing) items: Slot 0 (Hotbar 1) = Minigames sword, Slot 1 (Hotbar 2) = Ender Chest.
        new MinigamesItem(),
        new MyExperiencesItem(),
        new FriendsItem(),
        new FriendRequestsItem(),
        // Lobby-management items (owner + invited), gated by lobby state / role inside each item.
        new LobbyTeamCountItem(),
        new LobbyInviteItem(),
        new LobbyTeamColorItem(0),
        new LobbyTeamColorItem(1),
        new LobbyTeamColorItem(2),
        new LobbyTeamColorItem(3),
        new LobbyStartItem(),
        new LobbyLeaveItem())));
    // Game worlds own the player's kit, so their hotbar is empty — resolving to nothing is what strips
    // any lobby item on match entry.
    profiles.put(HotbarScope.MINIGAME, EMPTY);
    profiles.put(HotbarScope.EXPERIENCE, EMPTY);
  }

  /** The visible hotbar items for the player's current scope (empty for any non-lobby scope). */
  public List<HotbarSlot> resolve(PlayerAdapter player, HotbarScope scope) {
    HotbarContext context = new HotbarContext(player, scope, services);
    List<HotbarSlot> slots = new ArrayList<>();
    for (HotbarItem item : profileFor(scope).items()) {
      if (item.visibleFor(context)) {
        slots.add(new HotbarSlot(item.slot(), item.id(), item.build(context)));
      }
    }
    return slots;
  }

  /** Runs the click behaviour of the item with the given id in the player's current scope, if one matches. */
  public void handleClick(PlayerAdapter player, HotbarScope scope, String id) {
    if (id == null) {
      return;
    }
    HotbarContext context = new HotbarContext(player, scope, services);
    for (HotbarItem item : profileFor(scope).items()) {
      if (item.id().equals(id)) {
        item.onClick(context);
        return;
      }
    }
  }

  private HotbarProfile profileFor(HotbarScope scope) {
    return profiles.getOrDefault(scope, EMPTY);
  }
}
