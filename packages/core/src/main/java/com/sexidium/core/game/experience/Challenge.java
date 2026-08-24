package com.sexidium.core.game.experience;

import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.BlockPlaceGameEvent;
import com.sexidium.core.game.GameEvents.EntityDeathGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.InventoryChangeGameEvent;
import com.sexidium.core.game.GameEvents.MobDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerAdvancementGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDeathGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDropItemGameEvent;
import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.GameEvents.PlayerJumpGameEvent;
import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.GameEvents.PlayerToggleSneakGameEvent;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.experience.compose.BlockBreakService;
import com.sexidium.core.game.experience.compose.ChallengeContext;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DamagePipeline;
import com.sexidium.core.game.experience.compose.DropPipeline;
import com.sexidium.core.game.experience.compose.ExperienceStats;
import com.sexidium.core.game.experience.compose.HealthModel;
import com.sexidium.core.game.experience.compose.MobRegistry;
import com.sexidium.core.game.hardcore.HardcoreDeathOutcome;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.TabListHandle;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One self-contained experience twist (double drops, shared life, xp-health, …). An
 * {@link ExperienceGame} composes any number of challenges; each one only contributes its mechanic —
 * the match lifecycle (participants, world, survival prep, persistence, never-ending semantics) is
 * owned by the host. Challenges read their config from {@code experiences.modes.<id>}, the same keys
 * the standalone modes used, so existing config carries over.
 *
 * <p>The helper methods mirror the old {@code AbstractGame}/{@code experience framework} surface so the
 * per-twist logic could be lifted across with minimal change. Crucially there is NO
 * {@code eliminate}/{@code requestEnd} here: a lethal condition becomes {@link #softReset} (heal +
 * respawn in place), because experiences are open-ended and a death never ejects a player.</p>
 */
public abstract class Challenge {
  private final String id;
  private final String displayName;
  // Surfaces this challenge declared, so hudSurfaceActive() can answer from what is actually drawing
  // instead of each subclass hand-tracking it. Appended by hudSurface(); the host owns teardown.
  private final List<HudSurfaceHandle> surfaces = new ArrayList<>();
  protected ChallengeContext host;

  protected Challenge(String id, String displayName) {
    this.id = id;
    this.displayName = displayName;
  }

  public final String id() {
    return id;
  }

  public final String displayName() {
    return displayName;
  }

  /** Wires the challenge to its host. Called once by {@link ExperienceGame} before {@link #register}. */
  public final void attach(ChallengeContext host) {
    this.host = host;
  }

  /**
   * Dispatch/contribution ordering hint within the shared pipelines (lower runs first). Most
   * challenges leave this at 0; the life challenges override it to sequence damage handling.
   */
  public int priority() {
    return 0;
  }

  /**
   * Declare contributions to the shared pipelines and publish capabilities. Called once after
   * {@link #attach} and before {@link #onStart}. The default registers nothing — a challenge that
   * still uses {@link #onEvent} keeps working unchanged.
   */
  public void register(ChallengeRegistry registry) {
  }

  /**
   * Whether an experience containing this challenge must be generated as an empty VOID world (no natural
   * terrain), so the challenge can build its own world from scratch — the SkyBlock-style Random-Layers
   * mode does. Read at world-creation time (before any instance is attached), so it must not depend on
   * runtime state. Default false: a normal terrain world.
   */
  public boolean requiresVoidWorld() {
    return false;
  }

  /**
   * Whether the experience's linked NETHER sibling should also be generated void (for a SkyBlock whose
   * Nether mirrors its void Overworld). Only meaningful together with {@link #requiresVoidWorld()}. Read at
   * world-creation time. Default false: the Nether keeps normal terrain.
   */
  public boolean requiresVoidNether() {
    return false;
  }

  /**
   * Whether an experience containing this challenge must be HARDCORE regardless of what its owner chose
   * — for a challenge whose whole point is the stakes (Death Resets). Read at world-creation time from a
   * fresh instance, like {@link #requiresVoidWorld()}, so it must not depend on runtime state.
   *
   * <p>A challenge that returns true also locks the owner's hardcore toggle on for as long as it is
   * selected, so the rule cannot be turned off out from under it. Default false: the owner decides.</p>
   */
  public boolean requiresHardcore() {
    return false;
  }

  /**
   * Whether this challenge renders its OWN on-screen surface and wants the shared scoreboard sidebar
   * suppressed for the whole experience.
   *
   * <p>For a challenge whose readout is the entire point and belongs somewhere else (Death Resets draws
   * its two counters in BetterHud's top-left overlay): the sidebar's shared header — the active-challenge
   * list, deaths, blocks broken — is noise next to it, and two surfaces showing different things is worse
   * than one. A single challenge saying yes suppresses the panel for the experience, so do not use this
   * for a challenge that merely has a HUD line.</p>
   *
   * <p>Claiming the screen is not the same as getting it. A player's sidebar is only actually suppressed
   * while {@link #hudSurfaceActive(PlayerAdapter)} agrees the other surface is drawing for THAT player —
   * see there for why.</p>
   *
   * <p>Default false: contribute to the shared panel like everything else.</p>
   */
  public boolean ownsHud() {
    return false;
  }

  /**
   * Whether the surface this challenge claimed with {@link #ownsHud()} is, right now, actually drawing
   * where the given player can see it.
   *
   * <p>This is the guard that keeps a claim from turning into a blank screen. No platform is obliged to
   * draw a free-floating surface, and on Paper it needs BetterHud installed AND capable AND a Java
   * client. Suppressing the sidebar on the strength of {@code ownsHud()} alone means every server
   * without that stack sees no readout at all.</p>
   *
   * <p>Asked per PLAYER because the answer differs between two people in the same match: BetterHud rides
   * boss bars, so the Java player has the corner readout while the Bedrock player beside them has
   * nothing and needs the sidebar. One shared answer had to be wrong for one of them.</p>
   *
   * <p>ANSWERED BY DEFAULT from the surfaces this challenge declared through
   * {@link #hudSurface(HudSurfaceSpec)}: true when one of them is drawing off-sidebar for this player.
   * Overriding is for a challenge whose surface is not one of ours. A subclass that does override must
   * report changes with {@link ExperienceHost#hudSurfaceChanged()} so the decision can be revisited
   * mid-match; declared surfaces are re-read every time it is asked, so they need no such call.</p>
   */
  public boolean hudSurfaceActive(PlayerAdapter playerAdapter) {
    for (HudSurfaceHandle surface : surfaces) {
      if (surface.activeFor(playerAdapter)) {
        return true;
      }
    }
    return hudSurfaceActive();
  }

  /**
   * Whether the claimed surface is drawing for anybody at all. Only a fallback for
   * {@link #hudSurfaceActive(PlayerAdapter)}, which is what actually decides a player's screen.
   *
   * <p>Default: whether any declared surface is alive.</p>
   */
  public boolean hudSurfaceActive() {
    for (HudSurfaceHandle surface : surfaces) {
      if (surface.active()) {
        return true;
      }
    }
    return false;
  }

  /**
   * What a hardcore death should cost in an experience running this challenge. Default null: no opinion,
   * so the mode's own default (lose the world) stands. The first challenge with an opinion wins, so two
   * of them cannot fight over one death.
   *
   * <p>Read from the LIVE instance when challenges are initialised, not at world-creation time — unlike
   * {@link #requiresHardcore()}, this decides nothing about the shape of the world.</p>
   */
  public HardcoreDeathOutcome hardcoreDeathOutcome() {
    return null;
  }

  // ----- lifecycle hooks ----------------------------------------------------------------------

  /** Set up the mechanic for the given starting participants. */
  public void onStart(List<PlayerAdapter> participants) {
  }

  /** Tear down any mechanic-specific state. Tracked tasks/boss bars are cancelled by the host. */
  public void onStop() {
  }

  /**
   * The experience's world has been thrown away and replaced by a brand-new one, while the match kept
   * running. Called on the fresh world BEFORE any player is teleported into it, so a challenge that
   * builds terrain has somewhere to build and nobody is standing in void while it does.
   *
   * <p>Anything a challenge derived from the OLD world is now wrong: coordinates it cached, "already
   * built" markers, per-chunk bookkeeping. Note that persisted state is not carried across a reset
   * unless the mode explicitly asks for a key to survive, so in most cases the right implementation is
   * to re-seed whatever {@link #onStart} would have seeded. Default no-op.</p>
   */
  public void onWorldReset(WorldAdapter world) {
  }

  /**
   * Single entry for game events: dispatches to the typed {@code on…} hook below so a challenge
   * overrides only the events it cares about instead of re-deriving the type with {@code instanceof}.
   * Override this directly only for a challenge that genuinely needs the raw stream.
   */
  public void onEvent(GameEvent gameEvent) {
    switch (gameEvent) {
      case BlockBreakGameEvent event -> onBlockBreak(event);
      case BlockPlaceGameEvent event -> onBlockPlace(event);
      case PlayerMoveGameEvent event -> onPlayerMove(event);
      case PlayerInteractGameEvent event -> onPlayerInteract(event);
      case PlayerDamageGameEvent event -> onPlayerDamage(event);
      case MobDamageGameEvent event -> onMobDamage(event);
      case EntityDeathGameEvent event -> onEntityDeath(event);
      case PlayerAdvancementGameEvent event -> onPlayerAdvancement(event);
      case PlayerDeathGameEvent event -> onPlayerDeath(event);
      case PlayerRespawnGameEvent event -> onPlayerRespawn(event);
      case PlayerDropItemGameEvent event -> onPlayerDropItem(event);
      case InventoryChangeGameEvent event -> onInventoryChange(event);
      case PlayerToggleSneakGameEvent event -> onPlayerToggleSneak(event);
      case PlayerJumpGameEvent event -> onPlayerJump(event);
      default -> { }
    }
  }

  /** A participant broke a block. */
  public void onBlockBreak(BlockBreakGameEvent event) {
  }

  /** A participant placed a block. */
  public void onBlockPlace(BlockPlaceGameEvent event) {
  }

  /** A participant moved. */
  public void onPlayerMove(PlayerMoveGameEvent event) {
  }

  /** A participant interacted (left/right click on a block/air). */
  public void onPlayerInteract(PlayerInteractGameEvent event) {
  }

  /** A participant took damage (cancellable via the event). */
  public void onPlayerDamage(PlayerDamageGameEvent event) {
  }

  /** A participant dealt damage to a mob. */
  public void onMobDamage(MobDamageGameEvent event) {
  }

  /** A (non-player) entity died. */
  public void onEntityDeath(EntityDeathGameEvent event) {
  }

  /** A participant earned an advancement. */
  public void onPlayerAdvancement(PlayerAdvancementGameEvent event) {
  }

  /**
   * A participant died. Fired at the death, not at the respawn that may follow — in a hardcore world
   * there is no Respawn button, so a respawn is not a reliable proxy for a death.
   */
  public void onPlayerDeath(PlayerDeathGameEvent event) {
  }

  /** A participant respawned (death never ejects in an experience). */
  public void onPlayerRespawn(PlayerRespawnGameEvent event) {
  }

  /**
   * Where this challenge insists a death sends the player, overriding the experience's normal rule of
   * respawning in the dimension they died in. Default null: no opinion, and the usual rule applies.
   *
   * <p>For a mode whose whole shape is a descent (Layered Dimensions), being left where you died is what
   * would make dying cheap — so it sends you back to the top of the Overworld column instead. The first
   * challenge with an opinion wins, so two of them cannot fight over a respawn.</p>
   */
  public WorldPosition respawnPosition(PlayerAdapter playerAdapter) {
    return null;
  }

  /**
   * A participant is about to throw an item on the ground; cancellable via the event. Fires BEFORE the
   * drop, unlike {@link #onInventoryChange}, which reports the change a successful drop then causes.
   */
  public void onPlayerDropItem(PlayerDropItemGameEvent event) {
  }

  /** A participant's inventory changed. */
  public void onInventoryChange(InventoryChangeGameEvent event) {
  }

  /** A participant toggled sneak. */
  public void onPlayerToggleSneak(PlayerToggleSneakGameEvent event) {
  }

  /**
   * A participant pressed JUMP — the input, not upward motion. Being knocked into the air by a mob, an
   * explosion or a piston does NOT fire this; the jump half of a critical hit and a jump taken out of
   * water do. Fires on the player's own region thread, so a handler may act inline.
   */
  public void onPlayerJump(PlayerJumpGameEvent event) {
  }

  /** A player joined the running experience. */
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
  }

  /** A player left (or was removed from) the running experience. */
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
  }

  /**
   * Undo any per-player CLIENT/visual state this challenge applied to the player (body scale, the
   * potion effects it adds, health-display scale, XP bar), returning them to neutral. Called when this
   * challenge is removed from the player mid-run — by the live experience editor or by the Chaos mode's
   * cycle reset. World/block/mob changes are intentionally NOT reverted (they belong to the shared
   * world). Default: no-op.
   */
  public void resetPlayer(PlayerAdapter playerAdapter) {
  }

  // ----- helpers (mirror AbstractGame / experience framework) -------------------------------------

  protected GameContext gameContext() {
    return host.gameContext();
  }

  protected ConfigurationAdapter cfg() {
    return host.gameContext().server().configuration();
  }

  protected String configPrefix() {
    return "experiences.modes." + id;
  }

  /** Whether the global {@code debug} flag is set — gates diagnostic/spammy chat notices (e.g. the
   * Shared Inventory inherit message) so players never see them unless debugging is turned on. */
  protected boolean debugEnabled() {
    return cfg().getBoolean("debug", false);
  }

  protected String configPath(String childPath) {
    return configPrefix() + "." + childPath;
  }

  protected List<PlayerAdapter> online() {
    return host.online();
  }

  /** Every player in the wider mode (full roster), even when this challenge is scoped to one owner. */
  protected List<PlayerAdapter> modePopulation() {
    return host.modePopulation();
  }

  protected boolean isParticipant(PlayerAdapter playerAdapter) {
    return host.isParticipant(playerAdapter);
  }

  /** The world the experience runs in (may be null in tests). */
  protected WorldAdapter world() {
    return host.world();
  }

  protected ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    return host.runTimer(runnable, delayTicks, periodTicks);
  }

  protected ScheduledTask runLater(Runnable runnable, long delayTicks) {
    return host.runLater(runnable, delayTicks);
  }

  protected BossBarHandle track(BossBarHandle bossBarHandle) {
    return host.track(bossBarHandle);
  }

  protected HudPanelHandle track(HudPanelHandle hudPanelHandle) {
    return host.track(hudPanelHandle);
  }

  /**
   * Declares an on-screen surface from a {@link HudSurfaceSpec} during {@link #register}, and records
   * it so the challenge can answer {@link #hudSurfaceActive(PlayerAdapter)} from what is really
   * drawing.
   *
   * <p><b>This is the one to call.</b> Going to {@code registry.hudSurface(spec)} directly still
   * works and is still unbound with the challenge, but the handle never reaches {@link #surfaces} —
   * so a challenge that also claims the screen with {@link #ownsHud()} would report its surface
   * inactive forever, and every player would get the corner readout AND the sidebar it was supposed
   * to replace.</p>
   *
   * <p>Unlike the corner-overlay helper this replaces, the result is never additive-only and never
   * needs a hand-written twin on the sidebar: the driver stack renders the same declaration through
   * the platform's overlay where it reaches a player and through the scoreboard where it does not.
   * Declare it once and the Bedrock player in the same match is covered.</p>
   */
  protected HudSurfaceHandle hudSurface(ChallengeRegistry registry, HudSurfaceSpec spec) {
    return record(registry.hudSurface(spec));
  }

  /**
   * The same, for a surface opened outside {@link #register} — where there is no registry, and so no
   * automatic unbinding. Prefer {@link #hudSurface(ChallengeRegistry, HudSurfaceSpec)}.
   */
  protected HudSurfaceHandle hudSurface(HudSurfaceSpec spec) {
    return record(host.track(host.hudDriver().open(spec)));
  }

  private HudSurfaceHandle record(HudSurfaceHandle handle) {
    if (handle == null) {
      return HudSurfaceHandle.NOOP;
    }
    if (handle != HudSurfaceHandle.NOOP) {
      surfaces.add(handle);
    }
    return handle;
  }

  /**
   * Opens a numeric column in the tab player list and tracks it, or gets {@link TabListHandle#NOOP}
   * where the platform has no such surface.
   *
   * <p>For a figure worth reading about OTHER players — the sidebar and the overlay are each a player's
   * own view of themselves. The column draws a bare number with no label, so use it only where the
   * meaning is obvious from the mode, and never as the only place a number appears.</p>
   */
  protected TabListHandle tabList(String columnId) {
    return host.track(gameContext().server().ui().createTabList(columnId));
  }

  // ----- composition layer (shared pipelines + sibling discovery) ---------------------------------

  protected DropPipeline drops() {
    return host.drops();
  }

  protected BlockBreakService blocks() {
    return host.blocks();
  }

  protected DamagePipeline damagePipeline() {
    return host.damage();
  }

  protected HealthModel health() {
    return host.health();
  }

  protected MobRegistry mobs() {
    return host.mobs();
  }

  protected ExperienceStats stats() {
    return host.stats();
  }

  /**
   * Played time including the seconds not yet written to the experience's state.
   *
   * <p>What a readout should show. {@code stats().runSeconds()} is the persisted figure and only moves
   * when the host commits, so a counter built on it advances in jumps rather than per second.</p>
   */
  protected long liveRunSeconds() {
    return host.liveRunSeconds();
  }

  /** The same, for one player. See {@link #liveRunSeconds()}. */
  protected long liveRunSeconds(java.util.UUID playerId) {
    return host.liveRunSeconds(playerId);
  }

  /** Fetch a capability a sibling challenge published, if present. */
  protected <T> Optional<T> service(Class<T> type) {
    return host.service(type);
  }

  /** Fetch a sibling challenge instance by type, if it is part of this experience. */
  protected <C extends Challenge> Optional<C> sibling(Class<C> type) {
    return host.challenge(type);
  }

  // ----- shared persistent state (survives disconnect / restart, shared across players) -----------

  /** Namespaces a state key under this challenge's id so two challenges never collide on the blob. */
  protected final String stateKey(String key) {
    return id() + "." + key;
  }

  protected ExperienceState state() {
    return host == null ? null : host.sharedState();
  }

  protected int stateInt(String key, int defaultValue) {
    ExperienceState state = state();
    return state == null ? defaultValue : state.getInt(stateKey(key), defaultValue);
  }

  protected void setStateInt(String key, int value) {
    ExperienceState state = state();
    if (state != null) {
      state.setInt(stateKey(key), value);
    }
  }

  protected long stateLong(String key, long defaultValue) {
    ExperienceState state = state();
    return state == null ? defaultValue : state.getLong(stateKey(key), defaultValue);
  }

  protected void setStateLong(String key, long value) {
    ExperienceState state = state();
    if (state != null) {
      state.setLong(stateKey(key), value);
    }
  }

  protected String stateString(String key, String defaultValue) {
    ExperienceState state = state();
    return state == null ? defaultValue : state.getString(stateKey(key), defaultValue);
  }

  protected void setStateString(String key, String value) {
    ExperienceState state = state();
    if (state != null) {
      state.setString(stateKey(key), value);
    }
  }

  protected double stateDouble(String key, double defaultValue) {
    ExperienceState state = state();
    return state == null ? defaultValue : state.getDouble(stateKey(key), defaultValue);
  }

  protected void setStateDouble(String key, double value) {
    ExperienceState state = state();
    if (state != null) {
      state.setDouble(stateKey(key), value);
    }
  }

  protected boolean stateHas(String key) {
    ExperienceState state = state();
    return state != null && state.has(stateKey(key));
  }

  protected void heal(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      playerAdapter.setHealth(Math.max(1.0, playerAdapter.maxHealth()));
    }
  }

  /** Open-ended replacement for elimination: heal + feed + respawn the player in the experience. */
  protected void softReset(PlayerAdapter playerAdapter) {
    host.softRespawn(playerAdapter);
  }

  /**
   * A REAL death for this challenge's depletion condition (e.g. XP/shared life running out): the vanilla
   * death animation plays and the death screen appears, instead of the player being silently teleported
   * to spawn. keepInventory on the experience world keeps their items + XP; the respawn re-seats them.
   */
  protected void kill(PlayerAdapter playerAdapter) {
    host.killParticipant(playerAdapter);
  }

  protected LocalizedText text(MessageKey messageKey, MessageArg... messageArguments) {
    return LocalizedText.of(messageKey, messageArguments);
  }

  protected void popup(PlayerAdapter playerAdapter, PopupType popupType, MessageKey messageKey, MessageArg... messageArguments) {
    host.gameContext().server().ui().showPopup(playerAdapter, popupType, text(messageKey, messageArguments));
  }

  protected void popupAll(PopupType popupType, MessageKey messageKey, MessageArg... messageArguments) {
    LocalizedText localizedText = text(messageKey, messageArguments);
    for (PlayerAdapter playerAdapter : online()) {
      host.gameContext().server().ui().showPopup(playerAdapter, popupType, localizedText);
    }
  }

  protected void announce(MessageKey messageKey, MessageArg... messageArguments) {
    LocalizedText localizedText = text(messageKey, messageArguments);
    for (PlayerAdapter playerAdapter : online()) {
      host.gameContext().server().messages().send(playerAdapter, localizedText);
    }
    host.gameContext().server().messages().send(host.gameContext().server().console(), localizedText);
  }
}
