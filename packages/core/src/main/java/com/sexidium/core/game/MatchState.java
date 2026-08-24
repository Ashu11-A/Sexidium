package com.sexidium.core.game;

import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutable, shared bookkeeping for {@link GameManager} and its collaborators (launch, lifecycle,
 * session, pending). Holds the live-match maps, the player→match indexes, the reservation set used
 * to block duplicate async starts, and the "starting" UI flags. This is package-private state that
 * the focused collaborators read and mutate directly, exactly as the former god-class did.
 */
final class MatchState {
  final Map<UUID, ActiveMatch> matches = new LinkedHashMap<>();
  final Map<UUID, UUID> playerIndex = new LinkedHashMap<>();
  final Map<UUID, MatchSnapshot> pending = new LinkedHashMap<>();
  final Map<UUID, UUID> pendingIndex = new LinkedHashMap<>();
  // Players with an in-flight start() whose world is still being created asynchronously. They are not
  // yet in playerIndex, so this set stops a second start (e.g. spam-clicking an NPC) from spawning a
  // duplicate match/world for the same player before the first one finishes launching.
  final Set<UUID> reservingPlayers = new HashSet<>();
  boolean starting;
  String startingDisplayName;

  ActiveMatch matchOf(UUID playerId) {
    UUID matchId = playerIndex.get(playerId);
    return matchId == null ? null : matches.get(matchId);
  }

  ActiveMatch matchByGame(Game game) {
    if (game == null) {
      return null;
    }
    for (ActiveMatch activeMatch : matches.values()) {
      if (activeMatch.game() == game) {
        return activeMatch;
      }
    }
    return null;
  }

  boolean isReserved(UUID playerId) {
    return playerIndex.containsKey(playerId) || reservingPlayers.contains(playerId);
  }

  void clearStarting() {
    starting = false;
    startingDisplayName = null;
  }

  static List<PlayerAdapter> sanitize(Collection<? extends PlayerAdapter> participants) {
    Map<UUID, PlayerAdapter> uniquePlayers = new LinkedHashMap<>();
    if (participants != null) {
      for (PlayerAdapter participant : participants) {
        if (participant != null && participant.online()) {
          uniquePlayers.put(participant.uniqueId(), participant);
        }
      }
    }
    return List.copyOf(uniquePlayers.values());
  }

  static void addWorldName(List<String> worldNames, String worldName) {
    if (worldName != null && !worldName.isBlank() && !worldNames.contains(worldName)) {
      worldNames.add(worldName);
    }
  }

  static String lastPathSegment(String name) {
    if (name == null) {
      return null;
    }
    int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return separator >= 0 ? name.substring(separator + 1) : name;
  }
}
