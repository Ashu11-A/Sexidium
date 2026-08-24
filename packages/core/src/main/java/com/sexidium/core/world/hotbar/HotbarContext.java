package com.sexidium.core.world.hotbar;

import com.sexidium.core.data.FriendService;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.menu.MenuService;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;

/**
 * The per-render / per-click context handed to a {@link HotbarItem}: the player, their current
 * {@link HotbarScope}, and thin accessors onto the shared {@link HotbarServices}. Entirely
 * platform-agnostic, so hotbar items behave identically on every adapter.
 */
public final class HotbarContext {
  private final PlayerAdapter player;
  private final HotbarScope scope;
  private final HotbarServices services;

  HotbarContext(PlayerAdapter player, HotbarScope scope, HotbarServices services) {
    this.player = player;
    this.scope = scope;
    this.services = services;
  }

  public PlayerAdapter player() {
    return player;
  }

  public HotbarScope scope() {
    return scope;
  }

  public ServerAdapter server() {
    return services.server();
  }

  public GameManager games() {
    return services.games();
  }

  public LobbyManager lobbies() {
    return services.lobbies();
  }

  public FriendService friends() {
    return services.friends();
  }

  public MenuService menus() {
    return services.menus();
  }

  /** This player's current lobby group, or {@code null} when they are not in one. */
  public Lobby lobby() {
    return services.lobbies() == null ? null : services.lobbies().lobbyOf(player.uniqueId());
  }

  /**
   * True when the player is in a lobby that has been configured for a match (or queued) — the state in
   * which the hotbar swaps from the default lobby items to the lobby-management tools (team controls,
   * invite, start, team-colour picks). A solo, un-configured lobby is NOT managing.
   */
  public boolean managingLobby() {
    Lobby lobby = lobby();
    return lobby != null && (lobby.isConfigured() || lobby.isQueued());
  }

  /** True when the player leads (hosts) their current managed lobby — gates owner-only tools. */
  public boolean leadsLobby() {
    Lobby lobby = lobby();
    return lobby != null && lobby.isLeader(player.uniqueId());
  }
}
