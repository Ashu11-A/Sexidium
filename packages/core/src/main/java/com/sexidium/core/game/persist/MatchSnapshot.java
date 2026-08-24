package com.sexidium.core.game.persist;

import com.sexidium.core.game.GameState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MatchSnapshot {
  public final UUID matchId;
  public String modeId;
  public List<String> modeArgs = new ArrayList<>();
  public String worldName;
  public GameState state = GameState.RUNNING;
  public final Props data = new Props();
  public final List<PlayerSnapshot> players = new ArrayList<>();
  public long createdAt;
  public long updatedAt;

  public MatchSnapshot(UUID matchId, String modeId) {
    this.matchId = matchId;
    this.modeId = modeId;
  }

  public PlayerSnapshot player(UUID playerId) {
    for (PlayerSnapshot playerSnapshot : players) {
      if (playerSnapshot.playerId.equals(playerId)) {
        return playerSnapshot;
      }
    }
    return null;
  }

  public PlayerSnapshot getOrCreatePlayer(UUID playerId, String playerName, String role) {
    PlayerSnapshot existingSnapshot = player(playerId);
    if (existingSnapshot != null) {
      return existingSnapshot;
    }
    PlayerSnapshot createdSnapshot = new PlayerSnapshot(playerId, playerName, role);
    players.add(createdSnapshot);
    return createdSnapshot;
  }
}
