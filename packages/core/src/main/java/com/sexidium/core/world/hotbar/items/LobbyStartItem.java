package com.sexidium.core.world.hotbar.items;

import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.hotbar.HotbarContext;
import com.sexidium.core.world.hotbar.HotbarItem;
import com.sexidium.core.world.lobby.Lobby;

import java.util.List;

/** Slot 7 (lobby owner, configured lobby) — starts the match, reporting why it can't when it can't. */
public final class LobbyStartItem extends HotbarItem {
  @Override
  public String id() {
    return "lobby-start";
  }

  @Override
  public int slot() {
    return 7;
  }

  @Override
  public boolean visibleFor(HotbarContext context) {
    Lobby lobby = context.lobby();
    return lobby != null && lobby.isConfigured() && context.leadsLobby();
  }

  @Override
  public UiItem build(HotbarContext context) {
    Lobby lobby = context.lobby();
    int have = lobby == null ? 0 : lobby.size();
    int need = lobby == null ? 0 : lobby.requiredPlayersForStart();
    return UiItem.of(ItemKey.minecraft("lime_concrete"), "<green><bold>Start match</bold></green>",
        List.of("<gray>Players: <white>" + have + "</white></gray>",
            have < need ? "<red>Need " + need + " players</red>" : "<yellow>Click to begin</yellow>"));
  }

  @Override
  public void onClick(HotbarContext context) {
    if (context.lobbies() == null) {
      return;
    }
    switch (context.lobbies().start(context.player())) {
      case STARTED -> context.player().sendActionBar("<green>Starting…</green>");
      case TOO_FEW -> context.player().sendActionBar("<red>Not enough players to start.</red>");
      case FULL -> context.player().sendActionBar("<red>Too many players for the team slots.</red>");
      case NOT_LEADER -> context.player().sendActionBar("<red>Only the host can start.</red>");
      default -> context.player().sendActionBar("<red>Could not start the match.</red>");
    }
  }
}
