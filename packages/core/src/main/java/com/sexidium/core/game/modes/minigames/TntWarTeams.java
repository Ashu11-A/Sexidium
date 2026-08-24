package com.sexidium.core.game.modes.minigames;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure Red/Blue team assignment for {@link TntWarGame}. If explicit per-player team indices were passed
 * (0 = red, 1 = blue), they are honoured and any unassigned player is balanced onto the smaller side;
 * otherwise players are simply alternated red/blue in roster order. No platform dependency.
 */
final class TntWarTeams {
  static final String RED = "red";
  static final String BLUE = "blue";

  private TntWarTeams() {
  }

  /**
   * @param order       online player ids in roster order
   * @param assignments explicit per-player team index (0/1); empty for auto-alternate
   * @return ordered map of player id to "red"/"blue"
   */
  static Map<UUID, String> assign(List<UUID> order, Map<UUID, Integer> assignments) {
    Map<UUID, String> teamOf = new LinkedHashMap<>();
    if (!assignments.isEmpty()) {
      int red = 0;
      int blue = 0;
      for (UUID id : order) {
        Integer teamIndex = assignments.get(id);
        String team;
        if (teamIndex != null && teamIndex == 0) {
          team = RED;
        } else if (teamIndex != null && teamIndex == 1) {
          team = BLUE;
        } else {
          team = red <= blue ? RED : BLUE;
        }
        teamOf.put(id, team);
        if (RED.equals(team)) {
          red++;
        } else {
          blue++;
        }
      }
      return teamOf;
    }
    int index = 0;
    for (UUID id : order) {
      teamOf.put(id, index++ % 2 == 0 ? RED : BLUE);
    }
    return teamOf;
  }
}
