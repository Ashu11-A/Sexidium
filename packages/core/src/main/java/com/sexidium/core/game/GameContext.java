package com.sexidium.core.game;

import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceStateStore;
import com.sexidium.core.platform.KitAdapter;
import com.sexidium.core.platform.ServerAdapter;

import java.util.Objects;

public final class GameContext {
  private final ServerAdapter serverAdapter;
  private final KitAdapter kitAdapter;
  private final RankAwardPort rankAwardPort;
  private final com.sexidium.core.game.presence.PlayerControlWatch playerControl =
      new com.sexidium.core.game.presence.PlayerControlWatch();
  private GameManager gameManager;
  private ExperienceManager experienceManager;
  private ExperienceStateStore experienceStore;
  private com.sexidium.core.game.experience.compose.StackMergeService stackMergeService;

  public GameContext(ServerAdapter serverAdapter, KitAdapter kitAdapter, RankAwardPort rankAwardPort) {
    this.serverAdapter = Objects.requireNonNull(serverAdapter, "serverAdapter");
    this.kitAdapter = Objects.requireNonNull(kitAdapter, "kitAdapter");
    this.rankAwardPort = rankAwardPort == null ? RankAwardPort.noop() : rankAwardPort;
  }

  public ServerAdapter server() {
    return serverAdapter;
  }

  /**
   * Sends a player back to the lobby node. Null standalone and on the lobby itself, where the lobby
   * is a world in this JVM and a teleport is the whole story.
   *
   * <p>A worker has no lobby world at all: {@code worlds().lobbySpawn()} is empty there, so every
   * "you are done, go back" path — leaving a match, being eliminated, quitting an experience — used
   * to log a warning and leave the player standing in the world they had just left. On a network the
   * return trip is a transfer, and only the proxy can perform it.</p>
   */
  @FunctionalInterface
  public interface LobbyRouter {
    /** True when the player is being transferred and no local teleport should happen. */
    boolean send(com.sexidium.core.platform.PlayerAdapter player);
  }

  private volatile LobbyRouter lobbyRouter;

  public void attachLobbyRouter(LobbyRouter lobbyRouter) {
    this.lobbyRouter = lobbyRouter;
  }

  /** True when the player has been handed to the proxy to be moved to the lobby node. */
  public boolean routeToLobby(com.sexidium.core.platform.PlayerAdapter player) {
    LobbyRouter router = lobbyRouter;
    return player != null && router != null && router.send(player);
  }

  public KitAdapter kits() {
    return kitAdapter;
  }

  public RankAwardPort ranks() {
    return rankAwardPort;
  }

  public GameManager games() {
    return gameManager;
  }

  /** The persistent experience registry, or null when no database/registry is wired (e.g. tests). */
  public ExperienceManager experiences() {
    return experienceManager;
  }

  void attachGameManager(GameManager gameManager) {
    this.gameManager = gameManager;
  }

  public void attachExperiences(ExperienceManager experienceManager) {
    this.experienceManager = experienceManager;
  }

  /** File-backed per-experience state/snapshot store (.yml in the world folder), or null in tests. */
  public ExperienceStateStore experienceStore() {
    return experienceStore;
  }

  public void attachExperienceStore(ExperienceStateStore experienceStore) {
    this.experienceStore = experienceStore;
  }

  /** Server-wide over-cap loot-stack consolidator, or null in tests where it is not wired. */
  public com.sexidium.core.game.experience.compose.StackMergeService stackMerge() {
    return stackMergeService;
  }

  public void attachStackMerge(com.sexidium.core.game.experience.compose.StackMergeService stackMergeService) {
    this.stackMergeService = stackMergeService;
  }

  /**
   * Who is not currently driving their own character. Constructed eagerly and never null: every caller
   * is on a path where "there is no watch" and "nobody is down" would have to be handled identically,
   * and a null check that can only ever mean the second is a null check that will eventually be
   * forgotten on the path where it mattered.
   */
  public com.sexidium.core.game.presence.PlayerControlWatch playerControl() {
    return playerControl;
  }
}
