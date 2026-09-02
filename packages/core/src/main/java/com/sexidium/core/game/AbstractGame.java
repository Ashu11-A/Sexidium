package com.sexidium.core.game;

import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerToggleSneakGameEvent;
import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.game.hud.HudGestures;
import com.sexidium.core.game.hud.HudHost;
import com.sexidium.core.game.hud.surface.HudDriverStack;
import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.game.persist.Props;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.HudDriver;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.InventorySerializer;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.TabListHandle;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.lib.Countdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public abstract class AbstractGame implements Game, HudHost {
  protected final GameContext gameContext;
  protected final Set<UUID> players = new LinkedHashSet<>();
  protected GameState state = GameState.IDLE;
  private final Set<UUID> disconnectedPlayers = new LinkedHashSet<>();
  private final String modeId;
  private final String displayName;
  private final int minPlayers;
  private final List<ScheduledTask> scheduledTasks = new ArrayList<>();
  private final List<Countdown> countdowns = new ArrayList<>();
  private final List<BossBarHandle> bossBars = new ArrayList<>();
  private final List<HudPanelHandle> hudPanels = new ArrayList<>();
  // Declared surfaces follow the panels exactly: shown, hidden and closed with the rest of a player's
  // match UI, so a mode can never leave one floating on a screen after the player has left.
  private final List<HudSurfaceHandle> hudSurfaces = new ArrayList<>();
  private final List<TabListHandle> tabLists = new ArrayList<>();
  // The single unified per-player HUD (panel + toggle gestures), installed on demand via installHud().
  // Null until a game installs one, so modes with no HUD pay nothing.
  private GameHud hud;
  private HudGestures hudGestures;
  // Built on first use by hudDriver(); see there for why not in the constructor.
  private HudDriver hudDriver;
  // Last-known per-player state, kept fresh while online so it survives a disconnect/shutdown, and
  // re-applied when a player reconnects into a match rebuilt from disk after a restart.
  private final Map<UUID, PlayerSnapshot> playerStates = new LinkedHashMap<>();
  private final Set<UUID> pendingRestore = new LinkedHashSet<>();
  private boolean paused;

  protected AbstractGame(GameContext gameContext, String modeId, String displayName, int minPlayers) {
    this.gameContext = gameContext;
    this.modeId = modeId;
    this.displayName = displayName;
    this.minPlayers = minPlayers;
  }

  @Override
  public final String id() {
    return modeId;
  }

  @Override
  public final String displayName() {
    return displayName;
  }

  @Override
  public final int minPlayers() {
    return minPlayers;
  }

  @Override
  public final GameState state() {
    return state;
  }

  protected ConfigurationAdapter cfg() {
    return gameContext.server().configuration();
  }

  protected boolean isRunning() {
    return state == GameState.RUNNING;
  }

  protected void beginRunning() {
    players.clear();
    disconnectedPlayers.clear();
    state = GameState.RUNNING;
  }

  protected void markEnded() {
    state = GameState.ENDED;
  }

  protected boolean isPaused() {
    return paused;
  }

  protected void pause() {
    if (paused) {
      return;
    }
    paused = true;
    if (state == GameState.RUNNING) {
      state = GameState.STANDBY;
    }
    for (Countdown countdown : countdowns) {
      countdown.pause();
    }
  }

  protected void resume() {
    if (!paused) {
      return;
    }
    paused = false;
    if (state == GameState.STANDBY) {
      state = GameState.RUNNING;
    }
    for (Countdown countdown : countdowns) {
      countdown.resume();
    }
  }

  protected void markDisconnected(UUID playerId) {
    if (players.contains(playerId)) {
      disconnectedPlayers.add(playerId);
    }
  }

  protected void markReconnected(UUID playerId) {
    disconnectedPlayers.remove(playerId);
  }

  protected boolean isDisconnected(UUID playerId) {
    return disconnectedPlayers.contains(playerId);
  }

  protected Set<UUID> disconnected() {
    return disconnectedPlayers;
  }

  @Override
  public boolean isEmpty() {
    return onlineCount() == 0;
  }

  @Override
  public int onlineCount() {
    int onlineCount = 0;
    for (UUID playerId : players) {
      if (gameContext.server().player(playerId).filter(PlayerAdapter::online).isPresent()) {
        onlineCount++;
      }
    }
    return onlineCount;
  }

  protected void announce(MessageKey messageKey, MessageArg... messageArguments) {
    LocalizedText localizedText = LocalizedText.of(messageKey, messageArguments);
    for (PlayerAdapter playerAdapter : online()) {
      gameContext.server().messages().send(playerAdapter, localizedText);
    }
    gameContext.server().messages().send(gameContext.server().console(), localizedText);
  }

  protected ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    ScheduledTask scheduledTask = gameContext.server().scheduler().runTimer(runnable, delayTicks, periodTicks);
    scheduledTasks.add(scheduledTask);
    return scheduledTask;
  }

  protected ScheduledTask runLater(Runnable runnable, long delayTicks) {
    ScheduledTask scheduledTask = gameContext.server().scheduler().runLater(runnable, delayTicks);
    scheduledTasks.add(scheduledTask);
    return scheduledTask;
  }

  protected Countdown track(Countdown countdown) {
    countdowns.add(countdown);
    return countdown;
  }

  protected BossBarHandle track(BossBarHandle bossBarHandle) {
    bossBars.add(bossBarHandle);
    return bossBarHandle;
  }

  protected HudPanelHandle track(HudPanelHandle hudPanelHandle) {
    if (hudPanelHandle != null) {
      hudPanels.add(hudPanelHandle);
    }
    return hudPanelHandle == null ? HudPanelHandle.NOOP : hudPanelHandle;
  }

  protected HudSurfaceHandle track(HudSurfaceHandle hudSurfaceHandle) {
    if (hudSurfaceHandle != null) {
      hudSurfaces.add(hudSurfaceHandle);
    }
    return hudSurfaceHandle == null ? HudSurfaceHandle.NOOP : hudSurfaceHandle;
  }

  protected TabListHandle track(TabListHandle tabListHandle) {
    if (tabListHandle != null) {
      tabLists.add(tabListHandle);
    }
    return tabListHandle == null ? TabListHandle.NOOP : tabListHandle;
  }

  protected LocalizedText text(MessageKey messageKey, MessageArg... messageArguments) {
    return LocalizedText.of(messageKey, messageArguments);
  }

  protected void popup(PlayerAdapter playerAdapter, PopupType popupType, MessageKey messageKey, MessageArg... messageArguments) {
    gameContext.server().ui().showPopup(playerAdapter, popupType, text(messageKey, messageArguments));
  }

  protected void popupAll(PopupType popupType, MessageKey messageKey, MessageArg... messageArguments) {
    LocalizedText localizedText = text(messageKey, messageArguments);
    for (PlayerAdapter playerAdapter : online()) {
      gameContext.server().ui().showPopup(playerAdapter, popupType, localizedText);
    }
  }

  protected Countdown timerBar(MessageKey messageKey, int seconds, BossBarColor bossBarColor, Runnable onComplete, MessageArg... messageArguments) {
    return timerBar(messageKey, seconds, bossBarColor, null, onComplete, messageArguments);
  }

  protected Countdown timerBar(
      MessageKey messageKey,
      int seconds,
      BossBarColor bossBarColor,
      IntConsumer onSecond,
      Runnable onComplete,
      MessageArg... messageArguments
  ) {
    Countdown countdown = track(new Countdown(gameContext, messageKey, seconds, bossBarColor, onSecond, onComplete, messageArguments));
    countdown.start(online());
    return countdown;
  }

  /**
   * A countdown with no boss bar: the same per-second clock, drawn by the caller instead.
   *
   * <p>For a count that already has a louder surface of its own. Two surfaces counting the same
   * seconds do not reinforce each other — they read as two different timers.</p>
   */
  protected Countdown timerHidden(int seconds, IntConsumer onSecond, Runnable onComplete) {
    Countdown countdown = track(new Countdown(gameContext, null, seconds, null, onSecond, onComplete));
    countdown.start(online());
    return countdown;
  }

  protected void addParticipant(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      players.add(playerAdapter.uniqueId());
    }
  }

  protected void addParticipant(UUID playerId) {
    if (playerId != null) {
      players.add(playerId);
    }
  }

  protected void removeParticipant(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      players.remove(playerAdapter.uniqueId());
    }
  }

  protected void removeParticipant(UUID playerId) {
    players.remove(playerId);
  }

  protected boolean isParticipant(PlayerAdapter playerAdapter) {
    return playerAdapter != null && players.contains(playerAdapter.uniqueId());
  }

  protected void clearParticipants() {
    players.clear();
  }

  // Public (widened from protected) so this game can serve as the HudHost for its GameHud/HudGestures.
  @Override
  public List<PlayerAdapter> online() {
    List<PlayerAdapter> onlinePlayers = new ArrayList<>();
    for (UUID playerId : players) {
      gameContext.server().player(playerId)
          .filter(PlayerAdapter::online)
          .ifPresent(onlinePlayers::add);
    }
    return onlinePlayers;
  }

  @Override
  public GameContext gameContext() {
    return gameContext;
  }

  // ----- Unified HUD ownership (shared by Experience, Chaos and every minigame) --------------------

  /**
   * Installs the single per-player {@link GameHud} (one center-right panel + the double-sneak / look-up
   * toggle gestures) for this game and returns it. Idempotent: a second call adds more contributors to
   * the existing HUD without restarting its timers. The render timer repaints every {@code refreshTicks}
   * ticks; the look-up gesture is polled a few times a second. Resources are tracked, so the HUD is torn
   * down with the match.
   */
  protected GameHud installHud(LocalizedText title, long refreshTicks, Consumer<HudContext> sharedSection, HudContributor... contributors) {
    boolean firstInstall = hud == null;
    if (firstInstall) {
      hud = new GameHud(this, title);
      hudGestures = new HudGestures(this, hud);
    }
    if (sharedSection != null) {
      hud.sharedSection(sharedSection);
    }
    if (contributors != null) {
      for (HudContributor contributor : contributors) {
        hud.register(contributor);
      }
    }
    if (firstInstall) {
      long period = Math.max(1L, refreshTicks);
      runTimer(hud::render, period, period);
      // The mobile look-up gesture is held ~1s, so a few polls a second is ample.
      runTimer(hudGestures::pollLookUpToggle, 4L, 4L);
    }
    return hud;
  }

  /** The installed unified HUD, or null when this game never called {@link #installHud}. */
  public GameHud hud() {
    return hud;
  }

  /**
   * This game's HUD driver: the platform's stacked over the core sidebar renderer.
   *
   * <p>Built lazily and once, and deliberately not in the constructor — it closes over
   * {@link #hud()}, which does not exist until a mode installs one, and a mode that never declares a
   * surface should never build one.</p>
   */
  public HudDriver hudDriver() {
    if (hudDriver == null) {
      HudDriver platform = gameContext.server().ui() == null
          ? HudDriver.NOOP
          : gameContext.server().ui().hudDriver();
      hudDriver = new HudDriverStack(platform, this::hud, () -> gameContext.server().ui());
    }
    return hudDriver;
  }

  /**
   * Declares an on-screen surface and tracks it with the rest of this match's UI.
   *
   * <p>The one entry point for a mode that wants its own readout. Whether it renders through the
   * platform's overlay plugin, on the scoreboard sidebar, or differently for two players standing
   * next to each other, is the driver's decision — see {@link HudDriverStack}. Challenges reach the
   * same machinery through {@code ChallengeRegistry#hudSurface}, which additionally unbinds the
   * surface when the challenge is unbound.</p>
   */
  protected HudSurfaceHandle hudSurface(HudSurfaceSpec spec) {
    return track(hudDriver().open(spec));
  }

  /**
   * A game draws a sidebar for a player unless its HUD is actively suppressing that player's panel.
   * Answered from the LIVE HUD rather than cached, because suppression is decided after the challenges
   * start and can flip during the match (Death Resets un-suppresses a player whenever its corner overlay
   * stops drawing for them).
   */
  @Override
  public boolean drawsSidebar(PlayerAdapter playerAdapter) {
    return hud == null || !hud.panelSuppressed(playerAdapter);
  }

  /**
   * Forwards a sneak event to the HUD toggle gestures (PC double-tap). No-op when no HUD is installed or
   * the event is not a participant sneaking. Called from the base event handlers, so every game that
   * installs a HUD gets the toggle for free.
   */
  protected void routeHudGesture(GameEvent gameEvent) {
    if (hudGestures != null
        && gameEvent instanceof PlayerToggleSneakGameEvent sneak
        && sneak.sneaking()
        && isParticipant(sneak.playerAdapter())) {
      hudGestures.onSneak(sneak.playerAdapter());
    }
  }

  protected void prepareSurvival(PlayerAdapter playerAdapter) {
    heal(playerAdapter);
    playerAdapter.setFoodLevel(20);
    playerAdapter.setGameMode(GameModeType.SURVIVAL);
  }

  protected void giveKit(PlayerAdapter playerAdapter, String kitName) {
    if (playerAdapter != null && kitName != null && !kitName.isBlank()) {
      gameContext.kits().apply(playerAdapter, kitName);
    }
  }

  protected void release(PlayerAdapter playerAdapter, WorldPosition targetPosition) {
    if (playerAdapter == null) {
      return;
    }
    prepareSurvival(playerAdapter);
    if (targetPosition != null) {
      playerAdapter.teleport(targetPosition);
    }
  }

  protected WorldPosition lobbyLocation() {
    return gameContext.server().worlds().lobbySpawn().orElse(null);
  }

  protected void releaseToLobby(PlayerAdapter playerAdapter) {
    // On a worker the lobby is not a place, it is another server: hand the player to the proxy and
    // do NOT teleport, because the only local candidate is the world they are being released FROM.
    if (gameContext.routeToLobby(playerAdapter)) {
      prepareSurvival(playerAdapter);
      return;
    }
    release(playerAdapter, lobbyLocation());
  }

  protected void releaseAndReset(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    // Hide this match's overlays for the player first, via the cross-platform per-match hide() (which
    // works where the player-level clearBossBars() cannot be trusted). This covers mid-match
    // eliminations (combat/gather/tntwar) which release the victim without going through GameManager.
    releasePlayerUi(playerAdapter);
    playerAdapter.resetStatuses();
    if (gameContext.routeToLobby(playerAdapter)) {
      return; // Being transferred to the lobby node; there is nothing here to teleport to.
    }
    WorldPosition target = lobbyLocation();
    if (target != null) {
      // The lobby is never hardcore, and a client only learns what a world is when it is sent one — so
      // this is the moment, right before the teleport, that a hardcore world's hearts are given up.
      EntryPolicy.leaveHardcoreWorld(playerAdapter, target);
      playerAdapter.teleport(target);
      // Re-point the compass at the lobby now that the player has been moved there, instead of leaving
      // it on the (soon-discarded) match world spawn that resetStatuses() resolved.
      playerAdapter.setCompassTarget(target);
    }
  }

  /**
   * Re-shows this match's boss bars, HUD panels and countdown bars for a player who is staying in the
   * match (e.g. a respawning hunter), restoring overlays that a generic release/reset cleared.
   */
  protected void restorePlayerUi(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    for (BossBarHandle bossBarHandle : bossBars) {
      bossBarHandle.show(playerAdapter);
    }
    for (HudPanelHandle hudPanelHandle : hudPanels) {
      hudPanelHandle.show(playerAdapter);
    }
    for (HudSurfaceHandle hudSurfaceHandle : hudSurfaces) {
      hudSurfaceHandle.show(playerAdapter);
    }
    for (TabListHandle tabListHandle : tabLists) {
      tabListHandle.show(playerAdapter);
    }
    if (hud != null) {
      hud.show(playerAdapter);
    }
    for (Countdown countdown : countdowns) {
      countdown.addViewer(playerAdapter);
    }
  }

  protected void endSoon(long delayTicks) {
    runLater(() -> {
      if (gameContext.games() != null && gameContext.games().contains(this)) {
        gameContext.games().endMatch(this, null);
      }
    }, delayTicks);
  }

  protected void awardParticipation(PlayerAdapter playerAdapter) {
    gameContext.ranks().awardParticipation(playerAdapter);
  }

  protected void awardKill(PlayerAdapter playerAdapter) {
    gameContext.ranks().awardKill(playerAdapter);
  }

  protected void awardWin(PlayerAdapter playerAdapter) {
    gameContext.ranks().awardWin(playerAdapter, modeId);
  }

  /**
   * Starts the recurring time-in-world point award for the open-ended experience modes (Experience +
   * Chaos), which have no win/lose condition and so cannot earn points from wins or kills. Every
   * {@code ranks.award.experience-interval-seconds} (default 60s) each still-online participant earns
   * playtime points at the configured per-minute rate. The timer is tracked like any other and torn
   * down with the match; the {@code isRunning} guard makes a fire after the match ends a no-op.
   */
  protected void startExperiencePlaytimeAwards() {
    long intervalSeconds = Math.max(5L, cfg().getLong("ranks.award.experience-interval-seconds", 60L));
    long periodTicks = intervalSeconds * 20L;
    runTimer(() -> {
      if (!isRunning()) {
        return;
      }
      for (PlayerAdapter playerAdapter : online()) {
        gameContext.ranks().awardPlaytime(playerAdapter, intervalSeconds);
      }
    }, periodTicks, periodTicks);
  }

  protected boolean isLethal(PlayerAdapter playerAdapter, double finalDamage) {
    return playerAdapter != null && playerAdapter.health() - finalDamage <= 0.0;
  }

  protected void heal(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      playerAdapter.setHealth(Math.max(1.0, playerAdapter.maxHealth()));
    }
  }

  @Override
  public final void onParticipantRemoved(UUID playerId, boolean voluntary) {
    if (playerId == null) {
      return;
    }
    PlayerAdapter playerAdapter = gameContext.server().player(playerId).orElse(null);
    try {
      onParticipantLeaving(playerId, playerAdapter, voluntary);
    } finally {
      removeParticipant(playerId);
      disconnectedPlayers.remove(playerId);
      pendingRestore.remove(playerId);
      playerStates.remove(playerId);
      if (hudGestures != null) {
        hudGestures.forget(playerId);
      }
    }
  }

  /** Hook for mode-specific leave handling; core always clears membership/session state afterwards. */
  protected void onParticipantLeaving(UUID playerId, PlayerAdapter playerAdapter, boolean voluntary) {
  }

  /**
   * Hides this match's boss bars, HUD panels and countdown bars for a single player. Called when a
   * player exits/leaves the match (or quits the server) so they do not keep seeing this match's
   * overlays after they have been returned to the lobby, while everyone still playing keeps theirs.
   */
  @Override
  public void releasePlayerUi(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    for (BossBarHandle bossBarHandle : bossBars) {
      bossBarHandle.hide(playerAdapter);
    }
    for (HudPanelHandle hudPanelHandle : hudPanels) {
      hudPanelHandle.hide(playerAdapter);
    }
    for (HudSurfaceHandle hudSurfaceHandle : hudSurfaces) {
      hudSurfaceHandle.hide(playerAdapter);
    }
    for (TabListHandle tabListHandle : tabLists) {
      tabListHandle.hide(playerAdapter);
    }
    if (hud != null) {
      hud.hide(playerAdapter);
    }
    for (Countdown countdown : countdowns) {
      countdown.removeViewer(playerAdapter);
    }
  }

  // ----- Reconnect / resume persistence -------------------------------------------------------

  /**
   * Generic per-player snapshot capture. Refreshes the saved state of every online participant
   * (inventory, health, position, gamemode, role) and preserves the last-known state of offline
   * ones, then writes mode-specific data via {@link #writeModeData(Props)}. Reconnectable modes get
   * working persistence without re-implementing player capture.
   */
  @Override
  public void writeSnapshot(MatchSnapshot matchSnapshot) {
    InventorySerializer inventorySerializer = gameContext.server().inventorySerializer();
    for (UUID playerId : players) {
      PlayerAdapter playerAdapter = gameContext.server().player(playerId).filter(PlayerAdapter::online).orElse(null);
      if (playerAdapter != null) {
        PlayerSnapshot playerSnapshot = new PlayerSnapshot(playerId, playerAdapter.name(), roleOf(playerId));
        playerSnapshot.captureLive(playerAdapter, inventorySerializer);
        playerSnapshot.status = isDisconnected(playerId)
            ? PlayerSnapshot.Status.DISCONNECTED
            : PlayerSnapshot.Status.CONNECTED;
        playerStates.put(playerId, playerSnapshot);
      } else {
        PlayerSnapshot priorSnapshot = playerStates.get(playerId);
        if (priorSnapshot != null) {
          priorSnapshot.status = PlayerSnapshot.Status.DISCONNECTED;
        }
      }
    }
    playerStates.keySet().retainAll(players);
    matchSnapshot.players.addAll(playerStates.values());
    writeModeData(matchSnapshot.data);
  }

  /**
   * Generic restore after a server restart: re-registers every participant (offline at this point),
   * keeps their saved state for re-application on reconnect, marks the game RUNNING, and lets the
   * mode rebuild its own state via {@link #restoreModeData(Props)}.
   */
  @Override
  public void restore(MatchSnapshot matchSnapshot) {
    state = GameState.RUNNING;
    for (PlayerSnapshot playerSnapshot : matchSnapshot.players) {
      players.add(playerSnapshot.playerId);
      playerStates.put(playerSnapshot.playerId, playerSnapshot);
      markDisconnected(playerSnapshot.playerId);
      pendingRestore.add(playerSnapshot.playerId);
    }
    restoreModeData(matchSnapshot.data);
  }

  @Override
  public void onParticipantRejoin(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    markReconnected(playerAdapter.uniqueId());
    // Only re-apply saved inventory/health/position when the match was rebuilt from disk (restart).
    // For an in-session reconnect the player kept their live state, so we leave it untouched.
    if (pendingRestore.remove(playerAdapter.uniqueId())) {
      PlayerSnapshot playerSnapshot = playerStates.get(playerAdapter.uniqueId());
      if (playerSnapshot != null) {
        WorldPosition savedPosition = playerSnapshot.applyTo(playerAdapter, gameContext.server().inventorySerializer());
        if (savedPosition != null) {
          playerAdapter.teleport(savedPosition);
        }
      }
    }
    // Re-show this match's overlays (boss bars / HUD / countdowns) that were hidden on disconnect.
    restorePlayerUi(playerAdapter);
  }

  /** Role label persisted per player (e.g. "fugitive"/"hunter"); null by default. */
  protected String roleOf(UUID playerId) {
    return null;
  }

  /** Hook for modes to persist their own state into the match snapshot. */
  protected void writeModeData(Props data) {
  }

  /** Hook for modes to rebuild their own state (roles, timers) from a restored snapshot. */
  protected void restoreModeData(Props data) {
  }

  protected void cleanup() {
    if (hud != null) {
      hud.close();
    }
    for (ScheduledTask scheduledTask : scheduledTasks) {
      scheduledTask.cancel();
    }
    scheduledTasks.clear();
    for (Countdown countdown : countdowns) {
      countdown.stop();
    }
    countdowns.clear();
    for (BossBarHandle bossBarHandle : bossBars) {
      bossBarHandle.close();
    }
    bossBars.clear();
    for (HudPanelHandle hudPanelHandle : hudPanels) {
      hudPanelHandle.close();
    }
    hudPanels.clear();
    for (HudSurfaceHandle hudSurfaceHandle : hudSurfaces) {
      hudSurfaceHandle.close();
    }
    hudSurfaces.clear();
    for (TabListHandle tabListHandle : tabLists) {
      tabListHandle.close();
    }
    tabLists.clear();
  }
}
