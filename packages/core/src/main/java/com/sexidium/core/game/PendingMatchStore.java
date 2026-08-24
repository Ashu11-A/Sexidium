package com.sexidium.core.game;

import com.sexidium.core.game.experience.ExperienceGame;
import com.sexidium.core.game.persist.MatchRepository;
import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.platform.WorldLease;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Owns reconnect-pending snapshots: import on boot, rehydration of a persisted match when its owner
 * rejoins, stale-pending cleanup, and the world-name protection lists that keep temp-world GC from
 * deleting live or pending match worlds. Mutates the shared {@link MatchState}.
 */
final class PendingMatchStore {
  private final GameContext gameContext;
  private final GameRegistry gameRegistry;
  private final MatchRepository matchRepository;
  private final MatchState state;

  PendingMatchStore(GameContext gameContext, GameRegistry gameRegistry, MatchRepository matchRepository, MatchState state) {
    this.gameContext = gameContext;
    this.gameRegistry = gameRegistry;
    this.matchRepository = matchRepository;
    this.state = state;
  }

  void importPersisted(List<MatchSnapshot> matchSnapshots) {
    if (matchSnapshots == null) {
      return;
    }
    for (MatchSnapshot matchSnapshot : matchSnapshots) {
      state.pending.put(matchSnapshot.matchId, matchSnapshot);
      for (PlayerSnapshot playerSnapshot : matchSnapshot.players) {
        state.pendingIndex.put(playerSnapshot.playerId, matchSnapshot.matchId);
      }
    }
  }

  void discardPending(UUID matchId, UUID playerId) {
    MatchSnapshot matchSnapshot = state.pending.remove(matchId);
    state.pendingIndex.values().removeIf(pendingMatchId -> pendingMatchId.equals(matchId));
    // Experience worlds are persistent and player-owned — never delete them on a stale-pending sweep.
    // The experience stays in the ExperienceManager registry and can be re-entered later, which
    // recreates the match in the preserved world.
    if (matchSnapshot != null && matchSnapshot.worldName != null
        && !ExperienceGame.MODE_ID.equals(matchSnapshot.modeId)) {
      gameContext.server().worlds().discardByName(matchSnapshot.worldName);
    }
    if (matchRepository != null) {
      matchRepository.deleteAsync(matchId);
    }
  }

  void discardStalePending() {
    for (UUID matchId : new ArrayList<>(state.pending.keySet())) {
      discardPending(matchId, null);
    }
  }

  List<String> pendingWorldNames() {
    List<String> worldNames = new ArrayList<>();
    for (MatchSnapshot matchSnapshot : state.pending.values()) {
      if (matchSnapshot.worldName != null) {
        MatchState.addWorldName(worldNames, matchSnapshot.worldName);
      }
    }
    return List.copyOf(worldNames);
  }

  List<String> protectedWorldNames() {
    List<String> worldNames = new ArrayList<>();
    for (ActiveMatch activeMatch : state.matches.values()) {
      MatchState.addWorldName(worldNames, activeMatch.worldName());
    }
    for (String worldName : pendingWorldNames()) {
      MatchState.addWorldName(worldNames, worldName);
    }
    return List.copyOf(worldNames);
  }

  ActiveMatch rehydrate(UUID matchId) {
    MatchSnapshot matchSnapshot = state.pending.get(matchId);
    if (matchSnapshot == null) {
      return null;
    }
    // No experience branch. It went through a persistent-world door that opened a folder with no
    // placement check and no capability check -- and this method runs on EVERY join, off match rows
    // that every node imported from every other node. Persistent-world modes no longer persist a
    // match row at all (see MatchLifecycle.persist): experience_players plus ExperienceStateStore
    // already carry everything a returning player needs, and both go through the gated entry path.
    WorldLease worldLease = matchSnapshot.worldName == null
        ? null
        : gameContext.server().worlds().reacquireByName(matchSnapshot.worldName).orElse(null);
    if (matchSnapshot.worldName != null && worldLease == null) {
      discardPending(matchId, null);
      return null;
    }
    Game game = create(matchSnapshot.modeId, matchSnapshot.modeArgs);
    if (game == null) {
      discardPending(matchId, null);
      if (worldLease != null) {
        worldLease.close();
      }
      return null;
    }
    gameContext.server().events().registerGame(game);
    ActiveMatch activeMatch = new ActiveMatch(matchId, matchSnapshot.modeId, matchSnapshot.modeArgs, game, worldLease);
    state.matches.put(matchId, activeMatch);
    for (PlayerSnapshot playerSnapshot : matchSnapshot.players) {
      state.playerIndex.put(playerSnapshot.playerId, matchId);
    }
    try {
      game.restore(matchSnapshot);
    } catch (Exception exception) {
      gameContext.server().logger().warning("Failed to restore match " + matchId, exception);
    }
    state.pending.remove(matchId);
    state.pendingIndex.values().removeIf(pendingMatchId -> pendingMatchId.equals(matchId));
    return activeMatch;
  }

  private Game create(String modeId, List<String> modeArgs) {
    return gameRegistry.create(gameContext, modeId, modeArgs).orElse(null);
  }
}
