package com.sexidium.core.game;

import com.sexidium.core.game.persist.MatchRepository;
import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.world.lobby.MatchLauncher;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLease;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Public facade over the game subsystem. The actual responsibilities are split into focused,
 * package-private collaborators that share the mutable {@link MatchState} bookkeeping:
 * <ul>
 *   <li>{@link GameLauncher} — match start/launch flow and world acquisition,</li>
 *   <li>{@link MatchLifecycle} — end/stop/persist/shutdown,</li>
 *   <li>{@link PlayerSessionCoordinator} — quit/join/respawn/remove and admit/join-in-progress,</li>
 *   <li>{@link PendingMatchStore} — reconnect-pending snapshots and world-name protection.</li>
 * </ul>
 * GameManager keeps a stable public API and simply delegates.
 */
public final class GameManager implements MatchLauncher {
  private final GameContext gameContext;
  private final GameRegistry gameRegistry;
  private final MatchState state = new MatchState();
  private final MatchLifecycle lifecycle;
  private final GameLauncher launcher;
  private final PendingMatchStore pendingStore;
  private final PlayerSessionCoordinator sessions;

  public GameManager(GameContext gameContext, GameRegistry gameRegistry, MatchRepository matchRepository) {
    this.gameContext = gameContext;
    this.gameRegistry = gameRegistry;
    this.lifecycle = new MatchLifecycle(gameContext, matchRepository, state);
    this.launcher = new GameLauncher(gameContext, gameRegistry, state, lifecycle);
    this.pendingStore = new PendingMatchStore(gameContext, gameRegistry, matchRepository, state);
    this.sessions = new PlayerSessionCoordinator(gameContext, state, lifecycle, pendingStore);
    this.gameContext.attachGameManager(this);
  }

  public Collection<ActiveMatch> matches() {
    return new ArrayList<>(state.matches.values());
  }

  /** The number of players currently in a running match of the given mode (summed across its matches). */
  public int playersInMode(String modeId) {
    if (modeId == null) {
      return 0;
    }
    int count = 0;
    for (ActiveMatch activeMatch : state.matches.values()) {
      if (modeId.equals(activeMatch.modeId())) {
        count += activeMatch.game().onlineCount();
      }
    }
    return count;
  }

  public List<GameModeDescriptor> descriptors() {
    return gameRegistry.descriptors();
  }

  public Game active() {
    for (ActiveMatch activeMatch : state.matches.values()) {
      return activeMatch.game();
    }
    return null;
  }

  public boolean isRunning() {
    return !state.matches.isEmpty();
  }

  public boolean isStarting() {
    return state.starting;
  }

  public String startingDisplayName() {
    return state.startingDisplayName;
  }

  public boolean contains(Game game) {
    return matchByGame(game) != null;
  }

  public ActiveMatch matchOf(PlayerAdapter playerAdapter) {
    return playerAdapter == null ? null : matchOf(playerAdapter.uniqueId());
  }

  public ActiveMatch matchOf(UUID playerId) {
    return state.matchOf(playerId);
  }

  public ActiveMatch matchByGame(Game game) {
    return state.matchByGame(game);
  }

  public boolean hasPersistedSession(UUID playerId) {
    return state.playerIndex.containsKey(playerId) || state.pendingIndex.containsKey(playerId);
  }

  public boolean start(String modeId, Collection<? extends PlayerAdapter> participants, CommandSource initiator) {
    return start(modeId, participants, initiator, List.of());
  }

  public boolean start(
      String modeId,
      Collection<? extends PlayerAdapter> participants,
      CommandSource initiator,
      List<String> modeArgs
  ) {
    return launcher.start(modeId, participants, initiator, modeArgs);
  }

  /**
   * Starts (or recreates) a player-owned experience in its PERSISTENT world. Unlike {@link #start},
   * this never draws from the disposable temp pool: it loads or creates the stable {@code worldName}
   * via {@link com.sexidium.core.platform.WorldLeaseService#acquireOrCreatePersistent}, so the map
   * survives emptiness and restarts and is never auto-deleted.
   */
  public boolean startExperience(
      com.sexidium.core.world.WorldKey worldKey,
      Collection<? extends PlayerAdapter> participants,
      CommandSource initiator,
      List<String> challengeIds
  ) {
    return launcher.startExperience(worldKey, participants, initiator, challengeIds);
  }

  /** Starts a persistent experience-style world running an explicit {@code modeId} (experience or chaos). */
  public boolean startExperience(
      com.sexidium.core.world.WorldKey worldKey,
      String modeId,
      Collection<? extends PlayerAdapter> participants,
      CommandSource initiator,
      List<String> modeArgs
  ) {
    return launcher.startExperience(worldKey, modeId, participants, initiator, modeArgs);
  }

  /** The live match running in the given world, or null. Used to enter/resume a specific experience. */
  public ActiveMatch matchByWorldKey(com.sexidium.core.world.WorldKey worldKey) {
    if (worldKey == null) {
      return null;
    }
    // Compare on the last path segment: a match's live world name is the platform RUNTIME name, which
    // for an experience is a slash path (experiences/exp_ab12) or the platform's flattened label, while
    // the key is the bare id. Without this an experience match is never found by a second joiner, so
    // they would start a parallel match in the same world instead of joining — leaving everyone "solo".
    String target = worldKey.key();
    for (ActiveMatch activeMatch : state.matches.values()) {
      if (target.equals(MatchState.lastPathSegment(activeMatch.worldName()))) {
        return activeMatch;
      }
    }
    return null;
  }

  /** Adds an online, not-already-in-a-match player to a specific live match (e.g. an experience). */
  public JoinResult enterMatch(PlayerAdapter playerAdapter, ActiveMatch activeMatch) {
    return sessions.enterMatch(playerAdapter, activeMatch);
  }

  public boolean startWithPlayers(String modeId, Collection<UUID> participantIds, CommandSource initiator) {
    return startWithPlayers(modeId, participantIds, initiator, List.of());
  }

  public boolean startWithPlayers(
      String modeId,
      Collection<UUID> participantIds,
      CommandSource initiator,
      List<String> modeArgs
  ) {
    List<PlayerAdapter> players = new ArrayList<>();
    if (participantIds != null) {
      for (UUID playerId : participantIds) {
        gameContext.server().player(playerId)
            .filter(PlayerAdapter::online)
            .ifPresent(players::add);
      }
    }
    return start(modeId, players, initiator, modeArgs);
  }

  public void endActiveGame() {
    lifecycle.endActiveGame();
  }

  public void endMatch(Game game, LocalizedText reason) {
    lifecycle.endMatch(game, reason);
  }

  public void endMatch(ActiveMatch activeMatch, LocalizedText reason) {
    lifecycle.endMatch(activeMatch, reason);
  }

  public void stopActiveGame(MessageKey reason, MessageArg... messageArguments) {
    stopActiveGame(LocalizedText.of(reason, messageArguments));
  }

  public void stopActiveGame(LocalizedText reason) {
    lifecycle.stopActiveGame(reason);
  }

  public void persist(Game game) {
    lifecycle.persist(matchByGame(game));
  }

  /**
   * Points a running game's match at a freshly created world, after the mode regenerated its own world
   * underneath itself. Re-persists the match immediately so a restart in the next second reconnects
   * players to the new world rather than the one that was just deleted.
   *
   * @return true when the match was found and re-pointed
   */
  public boolean replaceMatchWorld(Game game, WorldLease replacement) {
    ActiveMatch activeMatch = matchByGame(game);
    if (activeMatch == null) {
      return false;
    }
    activeMatch.replaceLease(replacement);
    lifecycle.persist(activeMatch);
    return true;
  }

  public void handleQuit(PlayerAdapter playerAdapter) {
    sessions.handleQuit(playerAdapter);
  }

  /** Wire cross-node match assembly into both halves: the launcher and the arrival gate. */
  public void setHandoffs(
      com.sexidium.core.network.MatchHandoffService handoffs, String nodeId, long nodeEpoch) {
    launcher.setHandoffs(handoffs, nodeId, nodeEpoch);
    sessions.setHandoffs(handoffs);
    lifecycle.setHandoffs(handoffs);
  }

  /**
   * How a transferred player's experience is opened on arrival, and how the launcher learns that a
   * "failed" world acquisition was really a handoff. Both halves of the same transfer; wired
   * together so a networked deployment cannot install one and forget the other.
   */
  /** The typed arrival gate, and this node's address on it. Wired by the core when networked. */
  public void setArrivalGate(
      com.sexidium.core.network.transfer.ArrivalGate arrivals, String nodeId, long nodeEpoch) {
    sessions.setArrivalGate(arrivals, nodeId, nodeEpoch);
  }

  public void setTransferHooks(
      java.util.function.BiConsumer<PlayerAdapter, com.sexidium.core.world.WorldKey> resume,
      java.util.function.Predicate<UUID> beingTransferred) {
    sessions.setTransferResume(resume);
    launcher.setBeingTransferred(beingTransferred);
  }

  public boolean handleJoin(PlayerAdapter playerAdapter) {
    return sessions.handleJoin(playerAdapter);
  }

  public void removePlayer(UUID playerId, boolean voluntary) {
    sessions.removePlayer(playerId, voluntary);
  }

  public void handleRespawn(PlayerAdapter playerAdapter) {
    sessions.handleRespawn(playerAdapter);
  }

  public void handleChangedWorld(PlayerAdapter playerAdapter, String fromWorld, String toWorld) {
    sessions.handleChangedWorld(playerAdapter, fromWorld, toWorld);
  }

  public boolean removePlayer(PlayerAdapter playerAdapter, boolean voluntary) {
    if (playerAdapter == null || matchOf(playerAdapter) == null) {
      return false;
    }
    removePlayer(playerAdapter.uniqueId(), voluntary);
    return true;
  }

  public ActiveMatch findRunningMatchByMode(String modeId) {
    if (modeId == null) {
      return null;
    }
    for (ActiveMatch activeMatch : state.matches.values()) {
      if (modeId.equals(activeMatch.modeId())) {
        return activeMatch;
      }
    }
    return null;
  }

  public JoinResult joinInProgress(PlayerAdapter playerAdapter, String modeId) {
    return joinInProgress(playerAdapter, modeId, null);
  }

  /**
   * Joins a running match of the given mode. When {@code relatedPlayerIds} is non-null the joiner may
   * only enter a match that already contains one of those players (their party members / friends);
   * if a match of the mode is running but contains none of them, {@link JoinResult#NOT_RELATED} is
   * returned. A null set means no relationship restriction (internal / admin use).
   */
  public JoinResult joinInProgress(PlayerAdapter playerAdapter, String modeId, Set<UUID> relatedPlayerIds) {
    return sessions.joinInProgress(playerAdapter, modeId, relatedPlayerIds);
  }

  public List<String> runningModeIds() {
    List<String> modeIds = new ArrayList<>();
    for (ActiveMatch activeMatch : state.matches.values()) {
      if (!modeIds.contains(activeMatch.modeId())) {
        modeIds.add(activeMatch.modeId());
      }
    }
    return modeIds;
  }

  public void importPersisted(List<MatchSnapshot> matchSnapshots) {
    pendingStore.importPersisted(matchSnapshots);
  }

  public void discardPending(UUID matchId, UUID playerId) {
    pendingStore.discardPending(matchId, playerId);
  }

  public void discardStalePending() {
    pendingStore.discardStalePending();
  }

  public List<String> pendingWorldNames() {
    return pendingStore.pendingWorldNames();
  }

  /** World names that must be protected from temp-world GC: live matches plus reconnect-pending ones. */
  public List<String> protectedWorldNames() {
    return pendingStore.protectedWorldNames();
  }

  public void prepareShutdown() {
    lifecycle.prepareShutdown();
  }

  public List<String> modeIds() {
    return gameRegistry.modeIds();
  }

  public enum JoinResult {
    JOINED,
    NOT_RUNNING,
    NOT_RELATED,
    ALREADY_IN_MATCH,
    PLAYER_OFFLINE
  }
}
