package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;
import com.sexidium.core.world.lobby.Lobby;

import java.util.List;

/**
 * Slots 3–6 (any member of a configured, team-based lobby) — one coloured-wool tile per team. It appears
 * only while the lobby has that many teams (so it updates live as the host changes the team count),
 * badges the current headcount as the stack size, and marks the tile the viewer is currently on. Clicking
 * it joins (or leaves) that team. Both the owner and invited members get these, so anyone can pick a team.
 */
public final class LobbyTeamColorItem extends HotbarItem {
  private final int teamIndex;

  public LobbyTeamColorItem(int teamIndex) {
    this.teamIndex = teamIndex;
  }

  @Override
  public String id() {
    return "lobby-team-" + teamIndex;
  }

  @Override
  public int slot() {
    return 3 + teamIndex;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    Lobby lobby = context.lobby();
    return lobby != null && lobby.isConfigured() && lobby.teamsEnabled()
        && teamIndex < lobby.teamCount() && teamIndex < TeamColor.values().length;
  }

  @Override
  public UiItem build(HotbarContext context) {
    Lobby lobby = context.lobby();
    TeamColor color = TeamColor.values()[teamIndex];
    int selected = lobby == null ? 0 : lobby.selectedTeamSize(teamIndex);
    int teamSize = lobby == null ? 0 : lobby.teamSize();
    Integer own = lobby == null ? null : lobby.selectedTeam(context.player().uniqueId());
    boolean mine = own != null && own == teamIndex;
    String name = (mine ? "<green><bold>✔ </bold></green>" : "")
        + color.colorize("<bold>" + color.displayName() + "</bold>")
        + " <gray>(" + selected + "/" + teamSize + ")</gray>";
    return new UiItem(ItemKey.minecraft(color.woolItem()), Math.max(1, selected), name,
        List.of(mine ? "<yellow>Click to leave this team</yellow>" : "<yellow>Click to join this team</yellow>"),
        null, null);
  }

  @Override
  public void onClick(HotbarContext context) {
    if (context.lobbies() == null) {
      return;
    }
    TeamColor color = TeamColor.values()[teamIndex];
    switch (context.lobbies().chooseTeam(context.player(), teamIndex)) {
      case TEAM_SELECTED -> context.player().sendActionBar("<green>Joined " + color.colorize(color.displayName()) + ".</green>");
      case TEAM_LEFT -> context.player().sendActionBar("<yellow>Left " + color.colorize(color.displayName()) + ".</yellow>");
      case FULL -> context.player().sendActionBar("<red>That team is full.</red>");
      default -> context.player().sendActionBar("<red>Could not choose that team.</red>");
    }
  }
}
