package com.sexidium.core.world.hotbar;

import com.sexidium.core.data.FriendService;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.menu.MenuService;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.world.lobby.LobbyManager;

/**
 * The bundle of platform-agnostic services a {@link HotbarItem} may need to decide its visibility, build
 * its icon, or act on a click. Held by the {@link HotbarController} and handed to each item through a
 * {@link HotbarContext}. Any field may be {@code null} when the owning subsystem is disabled (e.g. no
 * database → no {@code friends}), so items null-check before use.
 */
public record HotbarServices(ServerAdapter server, GameManager games, LobbyManager lobbies,
    FriendService friends, MenuService menus) {
}
