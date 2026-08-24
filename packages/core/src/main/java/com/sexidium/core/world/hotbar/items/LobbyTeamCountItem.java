package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;
import com.sexidium.core.world.lobby.Lobby;

import java.util.List;

/**
 * Slot 1 (lobby owner, configured match lobby) — a comparator that cycles the team count
 * FFA → 2 → 3 → 4 → FFA on each click, mirroring the team-count control in the lobby chest GUI so the
 * host can set up teams without opening a menu.
 */
public final class LobbyTeamCountItem extends HotbarItem {
  @Override
  public String id() {
    return "lobby-team-count";
  }

  @Override
  public int slot() {
    return 1;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    Lobby lobby = context.lobby();
    return lobby != null && lobby.isConfigured() && context.leadsLobby();
  }

  @Override
  public UiItem build(HotbarContext context) {
    Lobby lobby = context.lobby();
    String value = lobby != null && lobby.teamsEnabled() ? String.valueOf(lobby.teamCount()) : "FFA";
    return UiItem.of(ItemKey.minecraft("comparator"), "<yellow><bold>Teams: " + value + "</bold></yellow>",
        List.of("<gray>Click to cycle FFA → 2 → 3 → 4</gray>"));
  }

  @Override
  public void onClick(HotbarContext context) {
    Lobby lobby = context.lobby();
    if (lobby == null || context.lobbies() == null) {
      return;
    }
    int next = !lobby.teamsEnabled() ? 2 : (lobby.teamCount() >= Lobby.MAX_TEAMS ? 0 : lobby.teamCount() + 1);
    context.lobbies().setTeamCount(context.player(), next);
    context.player().sendActionBar(next == 0
        ? "<yellow>Free-for-all.</yellow>"
        : "<yellow>" + next + " teams.</yellow>");
  }
}
