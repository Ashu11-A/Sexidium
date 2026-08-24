package com.sexidium.core.game;

import com.sexidium.core.game.persist.MatchRepository;
import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;

import java.util.ArrayList;

/**
 * Owns match teardown and persistence: ending/stopping matches, the async stop+cleanup ordering,
 * snapshot persistence, emptiness checks, and shutdown handling. Mutates the shared {@link MatchState}.
 */
final class MatchLifecycle {
  private final GameContext gameContext;
  private final MatchRepository matchRepository;
  private final MatchState state;

  MatchLifecycle(GameContext gameContext, MatchRepository matchRepository, MatchState state) {
    this.gameContext = gameContext;
    this.matchRepository = matchRepository;
    this.state = state;
  }

  void endActiveGame() {
    for (ActiveMatch activeMatch : new ArrayList<>(state.matches.values())) {
      endMatch(activeMatch, null);
      return;
    }
  }

  void endMatch(Game game, LocalizedText reason) {
    ActiveMatch activeMatch = state.matchByGame(game);
    if (activeMatch != null) {
      endMatch(activeMatch, reason);
    }
  }

  void endMatch(ActiveMatch activeMatch, LocalizedText reason) {
    if (activeMatch == null || !state.matches.containsKey(activeMatch.id())) {
      return;
    }
    state.matches.remove(activeMatch.id());
    state.playerIndex.values().removeIf(matchId -> matchId.equals(activeMatch.id()));
    Game game = activeMatch.game();
    try {
      game.stop(reason);
    } catch (Exception exception) {
      gameContext.server().logger().warning("Error while stopping match", exception);
    }
    gameContext.server().events().unregisterGame(game);
    if (activeMatch.lease() != null) {
      activeMatch.lease().close();
    }
    if (matchRepository != null) {
      matchRepository.deleteAsync(activeMatch.id());
    }
    // The roster rows go with the match. `clear` had ZERO production callers, so match_handoffs was
    // append-only: 24 rows for one player over two days on the live network, every one of them
    // scanned on every join on every node, and every one of them another chance for the arrival gate
    // to pick the wrong row.
    com.sexidium.core.network.MatchHandoffService rosters = handoffs;
    if (rosters != null) {
      rosters.clear(activeMatch.id().toString());
    }
  }

  /** The cross-node roster store, so a finished match's rows can be swept. Null standalone. */
  private volatile com.sexidium.core.network.MatchHandoffService handoffs;

  void setHandoffs(com.sexidium.core.network.MatchHandoffService handoffs) {
    this.handoffs = handoffs;
  }

  void stopActiveGame(LocalizedText reason) {
    for (ActiveMatch activeMatch : new ArrayList<>(state.matches.values())) {
      endMatch(activeMatch, reason);
    }
  }

  void persist(ActiveMatch activeMatch) {
    if (activeMatch == null || matchRepository == null || !activeMatch.game().isReconnectable()) {
      return;
    }
    // Persistent-world modes are deliberately NOT persisted here. A `matches` row is a reconnect
    // snapshot for a disposable world; for an experience it was a second, weaker copy of a pointer
    // that already exists in experience_players, and rehydrating it re-opened a shared world folder
    // through a door with no placement check at all.
    if (isPersistentWorldMode(activeMatch.modeId())) {
      return;
    }
    matchRepository.saveAsync(activeMatch.buildSnapshot());
  }

  /** Modes whose world outlives the match, and whose return path is the durable pointer, not a row. */
  static boolean isPersistentWorldMode(String modeId) {
    return com.sexidium.core.game.experience.ExperienceGame.MODE_ID.equals(modeId)
        || com.sexidium.core.game.chaos.ChaosGame.MODE_ID.equals(modeId);
  }

  boolean checkEmpty(ActiveMatch activeMatch) {
    if (activeMatch.game().isEmpty()) {
      endMatch(activeMatch, null);
      return true;
    }
    return false;
  }

  void prepareShutdown() {
    for (ActiveMatch activeMatch : new ArrayList<>(state.matches.values())) {
      if (activeMatch.game().isReconnectable() && matchRepository != null
          && !isPersistentWorldMode(activeMatch.modeId())) {
        MatchSnapshot matchSnapshot = activeMatch.buildSnapshot();
        for (PlayerSnapshot playerSnapshot : matchSnapshot.players) {
          playerSnapshot.status = PlayerSnapshot.Status.DISCONNECTED;
        }
        matchRepository.saveBlocking(matchSnapshot);
        gameContext.server().worlds().preserveSingle(activeMatch.worldName());
      } else {
        endMatch(activeMatch, LocalizedText.of(MessageKey.STOP_SERVER_SHUTDOWN));
      }
    }
  }
}
