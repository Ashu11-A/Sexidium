package com.sexidium.core.game.chaos;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.AbstractGame;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ChallengeBinding;
import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.game.experience.ExperiencePersistence;
import com.sexidium.core.game.experience.ExperienceState;
import com.sexidium.core.game.experience.ExperienceWorldType;
import com.sexidium.core.game.experience.PersistenceHost;
import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.game.experience.compose.BlockBreakService;
import com.sexidium.core.game.experience.compose.ChallengeContext;
import com.sexidium.core.game.experience.compose.DamageContext;
import com.sexidium.core.game.experience.compose.DamagePipeline;
import com.sexidium.core.game.experience.compose.DropPipeline;
import com.sexidium.core.game.experience.compose.ExperienceStats;
import com.sexidium.core.game.experience.compose.HealthModel;
import com.sexidium.core.game.experience.compose.MobRegistry;
import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.TabListHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Chaos mode — the third game family beside experiences and minigames. One shared, open-ended survival
 * world in which EACH player is independently assigned a random set of challenge twists whose effects
 * apply only to that player, and a global timer reshuffles every player's twists every N minutes (10 by
 * default), resetting their previous effects (size, potion effects, health scale, …) first.
 *
 * <p>It reuses the experience composition layer wholesale: the shared {@link DropPipeline}/
 * {@link DamagePipeline}/{@link HealthModel}/{@link BlockBreakService} pipelines plus the unified
 * {@link GameHud}, with each player's challenges wired through a {@link PlayerScope} +
 * {@link ScopedChallengeRegistry} so an unchanged challenge implementation acts per-player. The only
 * challenge that needed a tweak is Shared Life (single-holder → mode-average health). Inherently shared
 * twists degrade gracefully when scoped to one holder (Shared Inventory = the player's own inventory,
 * Chained = no rope).</p>
 */
public final class ChaosGame extends AbstractGame implements ChallengeContext, PersistenceHost {
  public static final String MODE_ID = "chaos";

  // Shared pipelines (contributions are owner-gated per player by ScopedChallengeRegistry).
  private final DropPipeline dropPipeline = new DropPipeline(this);
  private final BlockBreakService blockBreakService = new BlockBreakService(dropPipeline);
  private final DamagePipeline damagePipeline = new DamagePipeline();
  private final HealthModel healthModel = new HealthModel(this);
  private final MobRegistry mobRegistry = new MobRegistry(this);
  private final ExperienceStats experienceStats = new ExperienceStats(this);
  // The unified per-player HUD is owned by AbstractGame; installed in start() and reached via hud().
  private final Map<Class<?>, Object> services = new java.util.HashMap<>();
  // Mode-wide (host) state for shared stats; per-player challenge state lives in playerStates.
  private final ExperienceState hostState = ExperienceState.empty();
  // Reuses the experience per-player inventory/position store so a Chaos world persists like an
  // experience: items + spot survive disconnect, return-to-lobby and re-entry. The random per-player
  // twists are intentionally NOT persisted — they reshuffle every cycle anyway.
  private final ExperiencePersistence persistence = new ExperiencePersistence(this, gameContext);

  private final Map<java.util.UUID, List<ChallengeBinding>> perPlayer = new LinkedHashMap<>();
  private final Map<java.util.UUID, PlayerScope> scopes = new LinkedHashMap<>();
  private final Map<java.util.UUID, ExperienceState> playerStates = new LinkedHashMap<>();
  // The binding currently being wired, so a player's timers/contributions can be torn down on reroll.
  private ChallengeBinding activeBinding;

  private List<String> pool = List.of();
  private int challengesPerPlayer;
  private int cycleSeconds;
  private int cycleSecondsRemaining;
  private boolean announceRolls;
  private boolean ready;

  public ChaosGame(GameContext gameContext, List<String> args) {
    super(gameContext, MODE_ID, "Chaos", 1);
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    beginRunning();
    // Resolve the experience id + persistent-world flag first so each starting participant restores their
    // saved inventory/position in this Chaos world.
    persistence.loadState();
    cycleSeconds = Math.max(30, cfg().getInt("chaos.cycle-minutes", 10) * 60);
    cycleSecondsRemaining = cycleSeconds;
    challengesPerPlayer = Math.max(1, cfg().getInt("chaos.challenges-per-player", 3));
    announceRolls = cfg().getBoolean("chaos.announce-rolls", true);
    pool = resolvePool();
    // The world-integrity guard, installed before any challenge registers: the blocks no mode may ever
    // destroy (the End portal and its frames, bedrock, admin blocks) are decided once, here, for all.
    blockBreakService.guard(com.sexidium.core.game.experience.compose.BlockGuard.fromConfig(cfg()));
    dropPipeline.maxStackSize(Math.max(1, cfg().getInt("experiences.common.max-item-stack-size", 64)));
    if (participants != null) {
      for (PlayerAdapter participant : participants) {
        startParticipant(participant);
      }
    }
    // Install the unified HUD (owned by AbstractGame), then attach the chaos shared section.
    long hudPeriod = HudCadence.ticks(cfg(), "experiences.common.hud-refresh-ticks");
    installHud(LocalizedText.of(MessageKey.GAME_HUD_TITLE), hudPeriod, null);
    installHudSection();
    rollCycle();
    for (PlayerAdapter player : online()) {
      hud().show(player);
    }
    runTimer(this::composeTick, 1L, 1L);
    runTimer(this::cycleTick, 20L, 20L);
    // Half-second dimension sampling, so a death returns the player to the dimension it happened in.
    runTimer(this::trackDimensions, 10L, 10L);
    // Periodic player snapshot autosave so a hard crash loses at most one window of position/inventory.
    long autosave = Math.max(100L, cfg().getLong("experiences.common.autosave-ticks", 600L));
    runTimer(this::autosavePlayers, autosave, autosave);
    // Open-ended mode: points come from time-in-world, not wins/kills.
    startExperiencePlaytimeAwards();
    ready = true;
  }

  private void startParticipant(PlayerAdapter player) {
    addParticipant(player);
    // Clear BEFORE restoring so a first entry arrives empty and a return entry is fully overwritten by the
    // saved snapshot — the same lobby-item-leak fix experiences use.
    if (cfg().getBoolean("experiences.common.clear-inventory", true)) {
      player.clearInventory();
    }
    prepareSurvival(player);
    awardParticipation(player);
    persistence.restorePlayerState(player);
  }

  private void autosavePlayers() {
    if (!isRunning() || !persistence.persistentWorld()) {
      return;
    }
    for (PlayerAdapter player : online()) {
      persistence.capturePlayerState(player);
    }
  }

  private void composeTick() {
    if (!isRunning()) {
      return;
    }
    mobRegistry.tick();
    healthModel.writeAll();
  }

  /**
   * Samples which dimension each living participant is in, so a death can return them to it — by the time
   * the respawn fires vanilla has already moved them out of it.
   */
  private void trackDimensions() {
    if (!isRunning()) {
      return;
    }
    WorldAdapter world = world();
    for (PlayerAdapter player : online()) {
      persistence.recordDimension(player, world);
    }
  }

  /** One-second cycle countdown; rerolls every player's twists when it reaches zero. */
  private void cycleTick() {
    if (!isRunning()) {
      return;
    }
    cycleSecondsRemaining--;
    if (cycleSecondsRemaining <= 0) {
      rollCycle();
    }
  }

  // ----- cycle / reroll ------------------------------------------------------------------------

  private void rollCycle() {
    for (PlayerAdapter player : online()) {
      rerollPlayer(player);
    }
    cycleSecondsRemaining = cycleSeconds;
    if (announceRolls) {
      for (PlayerAdapter player : online()) {
        player.sendActionBar("<light_purple><bold>New chaos twists!</bold></light_purple>");
      }
    }
    // The HUD is installed in start(); a match restored after a restart has not run start(), so hud() can
    // be null here until the restore path installs one. Render only when there is one to render to.
    if (hud() != null) {
      hud().render();
    }
  }

  /** Resets a player's previous twists (effects undone) and binds a fresh random set. */
  private void rerollPlayer(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    java.util.UUID id = player.uniqueId();
    teardownPlayer(id, player);
    // Fresh per-player state each cycle so a twist starts clean (no carried-over scale/multiplier).
    playerStates.put(id, ExperienceState.empty());
    scopes.put(id, new PlayerScope(this, id));
    // Belt-and-suspenders client reset in case a removed twist had residual state.
    player.resetScale();
    player.resetHealthScale();
    List<ChallengeBinding> bindings = new ArrayList<>();
    for (String challengeId : rollChallenges()) {
      ChallengeBinding binding = bindFor(id, challengeId);
      if (binding != null) {
        bindings.add(binding);
      }
    }
    perPlayer.put(id, bindings);
  }

  private void teardownPlayer(java.util.UUID id, PlayerAdapter player) {
    List<ChallengeBinding> existing = perPlayer.remove(id);
    if (existing == null) {
      return;
    }
    for (ChallengeBinding binding : existing) {
      try {
        binding.challenge.onStop();
      } catch (Exception exception) {
        gameContext.server().logger().warning("Error stopping chaos challenge " + binding.challenge.id(), exception);
      }
      binding.teardown();
      if (player != null) {
        try {
          binding.challenge.resetPlayer(player);
        } catch (Exception exception) {
          gameContext.server().logger().warning("Error resetting chaos challenge " + binding.challenge.id(), exception);
        }
      }
    }
  }

  private ChallengeBinding bindFor(java.util.UUID owner, String challengeId) {
    List<Challenge> created = ChallengeCatalog.create(List.of(challengeId));
    if (created.isEmpty()) {
      return null;
    }
    Challenge challenge = created.get(0);
    PlayerScope scope = scopes.get(owner);
    ChallengeBinding binding = new ChallengeBinding(challenge);
    challenge.attach(scope);
    ScopedChallengeRegistry registry = new ScopedChallengeRegistry(this, scope);
    PlayerAdapter ownerPlayer = gameContext.server().player(owner).filter(PlayerAdapter::online).orElse(null);
    activeBinding = binding;
    try {
      challenge.register(registry);
      challenge.onStart(ownerPlayer == null ? List.of() : List.of(ownerPlayer));
    } finally {
      activeBinding = null;
    }
    return binding;
  }

  private List<String> rollChallenges() {
    List<String> options = new ArrayList<>(pool);
    Collections.shuffle(options, new java.util.Random(ThreadLocalRandom.current().nextLong()));
    int count = Math.min(challengesPerPlayer, options.size());
    return new ArrayList<>(options.subList(0, count));
  }

  private List<String> resolvePool() {
    List<String> configured = cfg().getStringList("chaos.pool");
    List<String> ids = new ArrayList<>();
    if (configured != null) {
      for (String entry : configured) {
        if (entry != null && ChallengeCatalog.contains(entry)) {
          ids.add(entry.trim().toLowerCase(Locale.ROOT));
        }
      }
    }
    if (ids.isEmpty()) {
      for (ChallengeCatalog.Entry entry : ChallengeCatalog.available()) {
        ids.add(entry.id());
      }
    }
    return ids;
  }

  private void installHudSection() {
    hud().sharedSection(context -> {
      context.stat("Twists reset in", formatTime(Math.max(0, cycleSecondsRemaining)));
      List<String> names = twistNames(context.player().uniqueId());
      if (names.isEmpty()) {
        context.line("<gray>Rolling your twists…</gray>");
      } else {
        context.line("<light_purple>Your twists:</light_purple>");
        for (String name : names) {
          context.line("<dark_gray>·</dark_gray> <white>" + name + "</white>");
        }
      }
      context.line("<dark_gray>───────</dark_gray>");
    });
  }

  private List<String> twistNames(java.util.UUID owner) {
    List<String> names = new ArrayList<>();
    for (ChallengeBinding binding : perPlayer.getOrDefault(owner, List.of())) {
      names.add(binding.challenge.displayName());
    }
    return names;
  }

  private static String formatTime(int totalSeconds) {
    int minutes = totalSeconds / 60;
    int seconds = totalSeconds % 60;
    return String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
  }

  // ----- event dispatch ------------------------------------------------------------------------

  @Override
  public void handle(GameEvent gameEvent) {
    if (!isRunning() || !ready) {
      return;
    }
    // PC HUD toggle: a double-tap of the sneak key flips the HUD view (handled by AbstractGame).
    routeHudGesture(gameEvent);
    // Death never ejects, and it never changes dimension either: redirect the respawn back into the
    // dimension the player died in (see ExperiencePersistence.respawnPosition).
    if (gameEvent instanceof PlayerRespawnGameEvent respawn && isParticipant(respawn.playerAdapter())) {
      PlayerAdapter player = respawn.playerAdapter();
      reviveParticipant(player);
      WorldPosition target = persistence.respawnPosition(
          player, world(), ExperienceWorldType.NORMAL, respawn.vanillaPosition());
      if (target != null) {
        respawn.setRespawnPosition(target);
        runLater(() -> {
          if (player.online()) {
            player.teleport(target);
          }
        }, 1L);
      }
      if (hud() != null) {
        hud().show(player);
      }
    }
    if (gameEvent instanceof BlockBreakGameEvent breakEvent
        && isParticipant(breakEvent.playerAdapter())
        && breakEvent.blockKey() != null) {
      experienceStats.recordBlocksBroken(1L);
      if (!dropPipeline.isEmpty()
          && blockBreakService.onManualBreak(breakEvent.playerAdapter(), breakEvent.blockPosition(), breakEvent.blockKey())) {
        breakEvent.setDropItems(false);
      }
    }
    // Any hit may be the fatal one, so sample the victim's dimension now — the periodic sample can be up
    // to half a second stale, and after the death vanilla has already moved them.
    if (gameEvent instanceof PlayerDamageGameEvent hit && isParticipant(hit.victim())) {
      persistence.recordDimension(hit.victim(), world());
    }
    if (gameEvent instanceof PlayerDamageGameEvent damageEvent
        && !damagePipeline.isEmpty()
        && isParticipant(damageEvent.victim())) {
      DamageContext damageContext = new DamageContext(damageEvent.victim(), damageEvent.attacker(),
          damageEvent.damageCauseType(), damageEvent.finalDamage());
      damagePipeline.process(damageContext);
      if (damageContext.absorbed()) {
        damageEvent.setCancelled(true);
      }
      healthModel.writeNow(damageEvent.victim());
    }
    for (List<ChallengeBinding> bindings : perPlayer.values()) {
      for (ChallengeBinding binding : bindings) {
        try {
          binding.challenge.onEvent(gameEvent);
        } catch (Exception exception) {
          gameContext.server().logger().warning("Error in chaos challenge " + binding.challenge.id(), exception);
        }
      }
    }
  }

  @Override
  public void stop(LocalizedText reason) {
    markEnded();
    ready = false;
    for (java.util.UUID id : new ArrayList<>(perPlayer.keySet())) {
      teardownPlayer(id, gameContext.server().player(id).orElse(null));
    }
    // Save each still-present player's spot + items, then flush shared state before the match tears down.
    for (PlayerAdapter player : online()) {
      persistence.capturePlayerState(player);
    }
    persistence.flushState();
    for (PlayerAdapter player : online()) {
      releaseAndReset(player);
    }
    cleanup();
    clearParticipants();
  }

  @Override
  public void onParticipantAdded(PlayerAdapter player) {
    if (player == null || isParticipant(player)) {
      return;
    }
    startParticipant(player);
    rerollPlayer(player); // give a late joiner twists immediately, not only at the next cycle
    if (hud() != null) {
      hud().show(player);
    }
  }

  @Override
  protected void onParticipantLeaving(java.util.UUID playerId, PlayerAdapter player, boolean voluntary) {
    // Capture the spot + items BEFORE release-to-lobby teleports them away, then clear the live inventory
    // ONLY when the snapshot was saved so Chaos items don't reach the lobby (they return on the next
    // visit). A transient (no persistent folder) Chaos saves nothing, so must NOT clear.
    if (persistence.capturePlayerState(player) && player != null) {
      player.clearInventory();
    }
    persistence.forgetDimension(playerId);
    teardownPlayer(playerId, player);
    scopes.remove(playerId);
    playerStates.remove(playerId);
    if (hud() != null) {
      hud().hide(player);
    }
  }

  @Override
  public void onParticipantDisconnect(PlayerAdapter playerAdapter) {
    // Save the exact spot + items so a disconnect (or the match later ending) returns the player here.
    persistence.capturePlayerState(playerAdapter);
  }

  @Override
  public void onParticipantRejoin(PlayerAdapter playerAdapter) {
    // In-session reconnect: re-enter from the .yml snapshot (clear, restore inventory/health, teleport to
    // the saved spot), then re-give fresh chaos twists and the HUD. The world files are authoritative, so
    // we do NOT call super (which would re-apply the match snapshot instead).
    if (playerAdapter == null) {
      return;
    }
    markReconnected(playerAdapter.uniqueId());
    if (!isParticipant(playerAdapter)) {
      addParticipant(playerAdapter);
    }
    if (cfg().getBoolean("experiences.common.clear-inventory", true)) {
      playerAdapter.clearInventory();
    }
    PlayerSnapshot snapshot = persistence.loadSnapshot(playerAdapter);
    if (snapshot != null) {
      snapshot.applyTo(playerAdapter, gameContext.server().inventorySerializer());
    } else {
      prepareSurvival(playerAdapter);
    }
    WorldPosition target = persistence.resolveEntryPosition(snapshot, world());
    if (target != null) {
      playerAdapter.teleport(target);
    }
    rerollPlayer(playerAdapter);
    if (hud() != null) {
      hud().show(playerAdapter);
    }
  }

  /** Saved position used as the entry teleport target, retrieved BEFORE the player is teleported. */
  @Override
  public WorldPosition entrySpawn(PlayerAdapter playerAdapter, WorldAdapter worldAdapter) {
    return persistence.entrySpawn(playerAdapter, worldAdapter);
  }

  @Override
  public boolean isReconnectable() {
    return true;
  }

  @Override
  public boolean handlesOwnRespawn() {
    return true;
  }

  @Override
  public boolean allowsWorldChange(PlayerAdapter playerAdapter, String fromWorld, String toWorld) {
    return isParticipant(playerAdapter);
  }

  // ----- ChallengeContext / ExperienceHost -----------------------------------------------------

  @Override
  public GameContext gameContext() {
    return gameContext;
  }

  @Override
  public List<PlayerAdapter> online() {
    return super.online();
  }

  @Override
  public boolean isParticipant(PlayerAdapter playerAdapter) {
    return super.isParticipant(playerAdapter);
  }

  @Override
  public BossBarHandle track(BossBarHandle bossBarHandle) {
    BossBarHandle handle = super.track(bossBarHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.bars.add(handle);
    }
    return handle;
  }

  @Override
  public HudPanelHandle track(HudPanelHandle hudPanelHandle) {
    HudPanelHandle handle = super.track(hudPanelHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.panels.add(handle);
    }
    return handle;
  }

  @Override
  public HudSurfaceHandle track(HudSurfaceHandle hudSurfaceHandle) {
    HudSurfaceHandle handle = super.track(hudSurfaceHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.surfaces.add(handle);
    }
    return handle;
  }

  @Override
  public TabListHandle track(TabListHandle tabListHandle) {
    TabListHandle handle = super.track(tabListHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.tabLists.add(handle);
    }
    return handle;
  }

  @Override
  public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    ScheduledTask task = super.runTimer(runnable, delayTicks, periodTicks);
    if (activeBinding != null && task != null) {
      activeBinding.tasks.add(task);
    }
    return task;
  }

  @Override
  public ScheduledTask runLater(Runnable runnable, long delayTicks) {
    ScheduledTask task = super.runLater(runnable, delayTicks);
    if (activeBinding != null && task != null) {
      activeBinding.tasks.add(task);
    }
    return task;
  }

  /** Records a teardown hook on the binding currently being wired (no-op when nothing is active). */
  void recordUndo(Runnable undo) {
    if (activeBinding != null && undo != null) {
      activeBinding.undo.add(undo);
    }
  }

  ExperienceState playerState(java.util.UUID owner) {
    return playerStates.computeIfAbsent(owner, ignored -> ExperienceState.empty());
  }

  List<Challenge> challengesOf(java.util.UUID owner) {
    List<Challenge> challenges = new ArrayList<>();
    for (ChallengeBinding binding : perPlayer.getOrDefault(owner, List.of())) {
      challenges.add(binding.challenge);
    }
    return challenges;
  }

  @Override
  public WorldAdapter world() {
    if (gameContext.games() == null) {
      return null;
    }
    var match = gameContext.games().matchByGame(this);
    return match == null ? null : match.world();
  }

  @Override
  public ExperienceState sharedState() {
    return hostState;
  }

  @Override
  public void killParticipant(PlayerAdapter playerAdapter) {
    if (playerAdapter == null || !playerAdapter.online()) {
      return;
    }
    // Real death for a life-challenge depletion: pause the merged health governance so it does not heal
    // the player back, then deal the lethal blow next tick — the vanilla death animation plays and the
    // death screen appears. keepInventory keeps items + XP; softRespawn re-seats them and resumes governance.
    java.util.UUID id = playerAdapter.uniqueId();
    healthModel.suspend(id);
    runLater(() -> gameContext.server().player(id)
        .filter(PlayerAdapter::online)
        .ifPresent(player -> player.setHealth(0.0)), 1L);
  }

  @Override
  public void softRespawn(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    reviveParticipant(playerAdapter);
    WorldAdapter world = world();
    if (world == null) {
      WorldPosition lobby = lobbyLocation();
      if (lobby != null) {
        playerAdapter.teleport(lobby);
      }
      return;
    }
    // No platform placement to respect on a soft reset, so this resolves to the spawn of the dimension
    // the player is in — never the Overworld by default.
    WorldPosition target = persistence.respawnPosition(playerAdapter, world, ExperienceWorldType.NORMAL, null);
    if (target != null) {
      playerAdapter.teleport(target);
    }
  }

  /** Shared "you are alive again" state for both a real respawn and a challenge soft-reset. */
  private void reviveParticipant(PlayerAdapter playerAdapter) {
    healthModel.resume(playerAdapter.uniqueId());
    experienceStats.recordDeath(playerAdapter);
    playerAdapter.setHealth(Math.max(1.0, playerAdapter.maxHealth()));
    playerAdapter.setFoodLevel(20);
    playerAdapter.setGameMode(GameModeType.SURVIVAL);
  }

  @Override
  public List<Challenge> challenges() {
    List<Challenge> all = new ArrayList<>();
    for (List<ChallengeBinding> bindings : perPlayer.values()) {
      for (ChallengeBinding binding : bindings) {
        all.add(binding.challenge);
      }
    }
    return all;
  }

  @Override
  public Optional<Challenge> challenge(String id) {
    if (id == null) {
      return Optional.empty();
    }
    for (Challenge challenge : challenges()) {
      if (challenge.id().equalsIgnoreCase(id)) {
        return Optional.of(challenge);
      }
    }
    return Optional.empty();
  }

  @Override
  public <C extends Challenge> Optional<C> challenge(Class<C> type) {
    if (type == null) {
      return Optional.empty();
    }
    for (Challenge challenge : challenges()) {
      if (type.isInstance(challenge)) {
        return Optional.of(type.cast(challenge));
      }
    }
    return Optional.empty();
  }

  @Override
  public <T> void publish(Class<T> type, T implementation) {
    if (type != null && implementation != null) {
      services.put(type, implementation);
    }
  }

  @Override
  public <T> Optional<T> service(Class<T> type) {
    if (type == null) {
      return Optional.empty();
    }
    Object implementation = services.get(type);
    return implementation == null ? Optional.empty() : Optional.of(type.cast(implementation));
  }

  @Override
  public DropPipeline drops() {
    return dropPipeline;
  }

  @Override
  public BlockBreakService blocks() {
    return blockBreakService;
  }

  @Override
  public DamagePipeline damage() {
    return damagePipeline;
  }

  @Override
  public HealthModel health() {
    return healthModel;
  }

  @Override
  public MobRegistry mobs() {
    return mobRegistry;
  }

  @Override
  public ExperienceStats stats() {
    return experienceStats;
  }
}
