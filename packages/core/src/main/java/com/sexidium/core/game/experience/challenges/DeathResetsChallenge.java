package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.game.GameEvents.PlayerDeathGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ExperienceWorldReset;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.ExperienceStats;
import com.sexidium.core.game.hardcore.HardcoreDeathOutcome;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.lib.DurationText;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.TabListHandle;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.HudAnchor;

import java.util.List;
import java.util.Set;

/**
 * <b>Death Resets.</b> Hardcore, no goals, and one rule: when anyone dies, the world ends and a new one
 * takes its place. Everybody is stripped back to nothing and dropped into fresh terrain, and what carries
 * over is the run's history — how long it has been played, how many in-game days this world has lasted,
 * and how many worlds it has been through.
 *
 * <h2>What this challenge actually does</h2>
 * Very little, and that is the point. It declares the stakes ({@link #requiresHardcore()}) and what a
 * death costs ({@link #hardcoreDeathOutcome()}), keeps its own two counters, names what survives a
 * regeneration, and asks the host for a reset. The player wipe, the folder delete and the regeneration
 * all belong to {@link ExperienceWorldReset}, because they are the host's world, lease and persistence —
 * not a challenge's to touch. The played-time and per-player death statistics likewise belong to the
 * host ({@link com.sexidium.core.game.experience.compose.ExperienceStats}); this mode only asks for them
 * to be carried and puts them on screen.
 *
 * <h2>The day counter</h2>
 * Counted from a baseline recorded when the world became the current one, not from the world clock
 * directly — see {@link DeathResetsClock} for why the obvious version is wrong. It is the age of THIS
 * world, so it returns to zero on every reset; the played-time figure beside it is the one that spans
 * the whole run.
 *
 * <h2>Where the numbers are shown</h2>
 * In a top-left corner overlay when the platform can draw one — on Paper that means BetterHud, so not on
 * Bedrock and not on a server without the plugin — and on the shared HUD panel when it cannot. Exactly
 * one of the two, chosen live and PER PLAYER: this challenge claims the screen ({@link #ownsHud()}) but
 * a given player's sidebar is only actually suppressed while
 * {@link #hudSurfaceActive(PlayerAdapter)} confirms the corner is drawing for them. So the Java player
 * gets the corner and nothing else while the Bedrock player beside them keeps the sidebar, rather than
 * one of the two ending up with an empty screen.
 */
public final class DeathResetsChallenge extends Challenge {
  private static final String KEY_RESETS = "resets";
  private static final String KEY_BASELINE = "daybaseline";
  /**
   * A readable mirror of the day count. The live figure is computed from the world clock, which can only
   * be read while the world is loaded — so a run parked between visits would otherwise have nothing to
   * say about how far it had got. Written here, it is part of the experience's saved state like every
   * other statistic. Deliberately NOT carried across a regeneration: a new world begins at day zero.
   */
  private static final String KEY_DAYS = "days";
  private static final String SURFACE_ID = "deathresets";
  /** Names the tab-list column. Short: the platform may have to fit it into a scoreboard object name. */
  private static final String TAB_COLUMN_ID = "deaths";
  // Row keys of the declared surface. Separate constants from the KEY_* state keys above even where the
  // spelling coincides: one names a slot on screen, the other a column in saved state, and a rename of
  // either must not silently move the other.
  private static final String ROW_DURATION = "duration";
  private static final String ROW_DAYS = "days";
  private static final String ROW_RESETS = "resets";
  /** Dawn — the hour vanilla starts a new world at, and what {@code /time set day} means. */
  private static final long MORNING_TICKS = 1000L;

  private long dayLengthTicks = DeathResetsClock.VANILLA_DAY_TICKS;
  private HudSurfaceHandle readout = HudSurfaceHandle.NOOP;
  /**
   * The death count beside every name in tab. Unlike the corner overlay this is a vanilla surface, so it
   * reaches every player — including the Bedrock players the overlay never gets to — and unlike the
   * sidebar it shows everyone's figure to everyone. In a mode where one person's death costs the whole
   * table its world, who has been dying is shared information.
   */
  private TabListHandle deathColumn = TabListHandle.NOOP;

  public DeathResetsChallenge() {
    super("deathresets", "Death Resets");
  }

  /** The whole challenge is the stakes, so the owner's hardcore toggle does not get a say. */
  @Override
  public boolean requiresHardcore() {
    return true;
  }

  /** A death here does not end the run — it ends the WORLD, and the run continues in the next one. */
  @Override
  public HardcoreDeathOutcome hardcoreDeathOutcome() {
    return HardcoreDeathOutcome.RESET_WORLD;
  }

  /**
   * This mode draws its own readout in the top-left corner, so the scoreboard sidebar is suppressed for
   * the whole experience. The two counters ARE the interface here — the sidebar's active-challenge list,
   * death count and blocks-broken tally are noise beside them.
   */
  @Override
  public boolean ownsHud() {
    return true;
  }

  /**
   * The three counters, declared once.
   *
   * <p>There is no second, hand-written copy of this for the players the corner cannot reach. The
   * driver stack renders the same declaration in BetterHud's corner for a Java player who has it and
   * on the scoreboard sidebar for everyone else — the Bedrock player in the same run, the whole server
   * that never installed the plugin — and {@link #hudSurfaceActive(PlayerAdapter)} answers per player
   * from which of the two actually drew. That used to be two renderings, two code paths and a
   * hand-maintained edge report to keep them in step.</p>
   */
  public static HudSurfaceSpec readoutSpec() {
    return HudSurfaceSpec.persistent(SURFACE_ID)
        .anchor(HudAnchor.TOP_LEFT)
        .text(ROW_DURATION, LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
        .text(ROW_DAYS, LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DAYS))
        .text(ROW_RESETS, LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_RESETS))
        .build();
  }

  @Override
  public void register(ChallengeRegistry registry) {
    readout = hudSurface(registry, readoutSpec());
    registry.hud(this::describeDebug);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // A "day" here means a real 20 minutes — 24000 ticks at 20 TPS — because that is what the counter on
    // screen promises the player. The override exists for experiments, but a value that is not a whole
    // number of minutes (or is absurdly short) turns the readout into a number with no meaning, so it is
    // clamped to at least one real minute and anything unusual is said out loud rather than absorbed.
    long configured = cfg().getLong(configPath("day-length-ticks"), DeathResetsClock.VANILLA_DAY_TICKS);
    dayLengthTicks = Math.max(1200L, configured);
    if (dayLengthTicks != DeathResetsClock.VANILLA_DAY_TICKS) {
      host.gameContext().server().logger().warning("[deathresets] day-length-ticks is " + dayLengthTicks
          + ", not the vanilla " + DeathResetsClock.VANILLA_DAY_TICKS + " (20 real minutes): the "
          + "on-screen day count is now " + String.format("%.1f", dayLengthTicks / 1200.0)
          + " real minutes per day.");
    }
    seedBaseline();
    deathColumn = tabList(TAB_COLUMN_ID);
    for (PlayerAdapter participant : participants) {
      readout.show(participant);
      showDeathColumn(participant);
    }
    // Driven from its OWN timer rather than from the HUD render pass, because this mode suppresses the
    // scoreboard panel for whoever the corner reaches (see ownsHud) — so the render pass does not paint
    // for them at all. Same cadence as the HUD would have used.
    long period = HudCadence.ticks(cfg(), "experiences.common.hud-refresh-ticks");
    runTimer(this::pushReadout, period, period);
  }

  /**
   * Publishes the three counters.
   *
   * <p>Pushed unconditionally, whichever surface ends up drawing them and whether or not one currently
   * is. The values are cheap and idempotent by contract, and a surface that comes up mid-run — the
   * operator switching BetterHud on, a Java player joining a Bedrock-only run — then has something to
   * draw on its first frame rather than a row of dashes until the next tick.</p>
   */
  private void pushReadout() {
    // The day mirror is written whether or not anything is drawing: it is a saved statistic, not a
    // rendering detail, and a run whose players are all on Bedrock still has days to remember. Only on a
    // CHANGE, which is at most once per in-game day — a write per second would mean a file write per
    // second for a number that moves every twenty minutes.
    long days = days();
    if (stateLong(KEY_DAYS, -1L) != days) {
      setStateLong(KEY_DAYS, days);
    }
    // The tab column is refreshed on the same timer but OUTSIDE the overlay's active check: it is a
    // vanilla scoreboard surface, so it works for every player whether or not BetterHud reached them —
    // which is the whole reason the death counts live there and not only in the corner. The platform
    // handle skips writes that would not change a number, so this costs nothing between deaths.
    for (PlayerAdapter participant : online()) {
      deathColumn.count(participant, (int) stats().runDeaths(participant.uniqueId()));
    }
    deathColumn.refresh();
    readout.text(ROW_DURATION, LocalizedText.of(MessageKey.GAME_HUD_LINE,
        MessageArg.text("text", DurationText.compact(liveRunSeconds()))));
    readout.number(ROW_DAYS, days);
    readout.number(ROW_RESETS, resets());
    readout.refresh();
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    readout.show(playerAdapter);
    showDeathColumn(playerAdapter);
  }

  /**
   * Stops drawing the column FOR this player, but leaves their own number in it for everyone still
   * inside. Somebody who logs off after four deaths has still died four times, and a run where the
   * counts vanish as people come and go is not a record of anything.
   */
  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    readout.hide(playerAdapter);
    deathColumn.hide(playerAdapter);
  }

  /** Enrols a player as a viewer of the column and seats their own figure in it straight away. */
  private void showDeathColumn(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    deathColumn.show(playerAdapter);
    deathColumn.count(playerAdapter, (int) stats().runDeaths(playerAdapter.uniqueId()));
  }

  /**
   * Somebody died, so this world is over.
   *
   * <p>The counter is incremented HERE, before the reset is asked for, so it is already part of the
   * shared state that the reset flushes and carries across the wipe. Doing it afterwards would mean a
   * failed regeneration lost the count of the world it had just destroyed.</p>
   */
  @Override
  public void onPlayerDeath(PlayerDeathGameEvent event) {
    ExperienceWorldReset reset = service(ExperienceWorldReset.class).orElse(null);
    if (reset == null || reset.running()) {
      return;
    }
    setStateInt(KEY_RESETS, resets() + 1);
    PlayerAdapter victim = event.playerAdapter();
    reset.reset(
        text(MessageKey.EXPERIENCE_RESET_STARTING,
            MessageArg.text("player", victim == null ? "?" : victim.name())),
        // The world is replaced; the RUN is not. The two named keys are this mode's own counters, and the
        // prefix carries the host's run-lifetime statistics — played time and per-player deaths — whose
        // keys are per-UUID and so cannot be named exactly. Everything else, the day mirror included,
        // described terrain that is about to stop existing.
        Set.of(stateKey(KEY_RESETS), stateKey(KEY_BASELINE), ExperienceStats.RUN_KEY_PREFIX + "*"),
        succeeded -> {
          if (succeeded) {
            announce(MessageKey.EXPERIENCE_RESET_DONE, MessageArg.text("resets", resets()));
            return;
          }
          // The world could not be replaced and everyone is still in the one they were in, so no world
          // was burned through — take the count back rather than leave it claiming otherwise.
          setStateInt(KEY_RESETS, Math.max(0, resets() - 1));
        });
  }

  /**
   * A brand-new world means a brand-new clock: start counting its days from wherever it begins — and
   * make where it begins be morning.
   *
   * <p>The world arrives from the warm pool, which has been ticking since the server booted, so its
   * hour is arbitrary. Roughly half the time that means everyone who just lost a world respawns into
   * the dark with the mobs already out, which on a hardcore mode where the next death costs the world
   * too is a punishment for the reset rather than a fresh start.</p>
   *
   * <p><b>Order matters.</b> The clock is wound first and the baseline read after, because
   * {@link WorldAdapter#setTimeOfDay} moves the clock FORWARD — up to a full day. A baseline captured
   * before the wind would already be that far behind, so the run's first day would be short by however
   * dark the world happened to arrive.</p>
   */
  @Override
  public void onWorldReset(WorldAdapter world) {
    startInTheMorning(world);
    setStateLong(KEY_BASELINE, world == null ? 0L : world.fullTimeTicks());
  }

  /**
   * Puts a world at dawn. Only ever called for a world nobody has lived in yet — a reset's replacement,
   * or the very first world of a run — never for one being resumed, where the hour is the players' own.
   */
  private void startInTheMorning(WorldAdapter world) {
    if (world != null) {
      world.setTimeOfDay(MORNING_TICKS);
    }
  }

  /**
   * The operator's diagnostics, and nothing else.
   *
   * <p>The three counters used to be duplicated here, because the corner overlay could not reach every
   * player and had no way to be translated. Both of those are the driver's problem now: it renders the
   * declared rows on whichever surface a given player can actually see, in that player's language. What
   * is left is genuinely sidebar-only — the tuning knobs an operator turns {@code debug} on to read,
   * which have no business on a player's screen and no business in a language file.</p>
   */
  private void describeDebug(HudContext context) {
    if (!context.debug()) {
      return;
    }
    context.debugHeader(displayName());
    context.debugStat("Day length (ticks)", dayLengthTicks);
    context.debugStat("Baseline (ticks)", stateLong(KEY_BASELINE, 0L));
    context.debugStat("World clock", world() == null ? "—" : world().fullTimeTicks());
    context.debugStat("Readout", readout.active() ? "driver" : "sidebar");
    PlayerAdapter viewer = context.player();
    if (viewer != null) {
      context.debugStat("Your time", DurationText.compact(liveRunSeconds(viewer.uniqueId())));
      context.debugStat("Your deaths", stats().runDeaths(viewer.uniqueId()));
    }
  }

  private int resets() {
    return stateInt(KEY_RESETS, 0);
  }

  private long days() {
    WorldAdapter world = world();
    if (world == null) {
      return 0L;
    }
    long now = world.fullTimeTicks();
    long baseline = stateLong(KEY_BASELINE, 0L);
    // A clock behind its own baseline means the world changed under us (or an operator wound the time
    // back). Re-seat rather than report a negative age.
    if (DeathResetsClock.baselineStale(now, baseline)) {
      setStateLong(KEY_BASELINE, now);
      return 0L;
    }
    return DeathResetsClock.days(now, baseline, dayLengthTicks);
  }

  /**
   * Seats the day counter the first time this experience ever runs — and only then.
   *
   * <p>The absence of a baseline IS the "brand-new run" signal: it is written once and carried across
   * every reset, so a later start is somebody resuming a world that already has a history. That is why
   * the morning nudge lives inside the guard rather than beside it. Resuming an experience must not wind
   * its clock: the hour those players left it at is theirs, and moving it would shorten the day they are
   * halfway through.</p>
   */
  private void seedBaseline() {
    if (!stateHas(KEY_BASELINE)) {
      WorldAdapter world = world();
      // Same order as onWorldReset, for the same reason: the wind moves the clock, so read after it.
      startInTheMorning(world);
      setStateLong(KEY_BASELINE, world == null ? 0L : world.fullTimeTicks());
    }
  }
}
