package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.game.GameEvents.EntityDeathGameEvent;
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
import com.sexidium.core.platform.hud.HudAlign;
import com.sexidium.core.platform.hud.HudColor;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.WorldKey;

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
 * <h2>The boss checklist</h2>
 * Beneath the counters, the four bosses a world is asked to get through — Elder Guardian, Warden,
 * Wither, Ender Dragon — as a to-do list that ticks off and strikes through as they fall. See
 * {@link BossLadder} for the order and for why a kill counts whichever order it happens in, and
 * {@link #onEntityDeath} for why the ticks are WORLD-scoped: a reset takes the list back to nothing,
 * because the monument, the Deep Dark and the End those bosses lived in stopped existing with it.
 *
 * <p>The strike-through is real wherever the line goes through the chat component pipeline — the
 * sidebar fallback, and so every Bedrock player and every server without the overlay plugin. It is
 * NOT real in BetterHud's corner: that renderer draws through a font atlas and reads a row's colour
 * and nothing else, so the line is flattened to plain text before it arrives. The state therefore
 * also rides in a character the flatten cannot take away — {@code ☐} against {@code ☑}, the same
 * width in the font, so a rung ticking off does not shift the column.</p>
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
  /** Whether the checklist is hidden. World-scoped like the rungs themselves, and for the same reason. */
  private static final String KEY_LADDER_HIDDEN = "bosses.hidden";
  private static final String SURFACE_ID = "deathresets";
  /** Names the tab-list column. Short: the platform may have to fit it into a scoreboard object name. */
  private static final String TAB_COLUMN_ID = "deaths";
  // Row keys of the declared surface. Separate constants from the KEY_* state keys above even where the
  // spelling coincides: one names a slot on screen, the other a column in saved state, and a rename of
  // either must not silently move the other.
  private static final String ROW_DURATION = "duration";
  private static final String ROW_DAYS = "days";
  private static final String ROW_RESETS = "resets";
  /**
   * The gap between the run counters and the boss checklist, in pixels — a little under one row.
   *
   * <p>The two halves answer different questions: the counters are how the run is doing, the checklist
   * is what it is FOR. Eight rows in one unbroken column reads as eight equally important numbers, and
   * a spacer is the cheapest way to say otherwise — it costs no row and no words in any language.</p>
   */
  private static final int LADDER_GAP_PIXELS = 6;
  /**
   * Every row is drawn at 1.0, and the difference between the two halves is carried by COLOUR alone.
   *
   * <p>Not for want of trying a size hierarchy. BetterHud draws through a bitmap font atlas, and a
   * fractional scale there resamples glyphs rather than reflowing them — a 0.9 row is a blurrier row,
   * not a smaller one, and at an 8px cap height there is nothing to give away. So the counters are
   * WHITE (live figures, read at a glance) and the whole checklist is GRAY (the standing objective),
   * which is a hierarchy the overlay can actually render.</p>
   */
  private static final double ROW_SCALE = 1.0d;
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
   * the whole experience. The counters and the boss checklist ARE the interface here — the sidebar's
   * active-challenge list, death count and blocks-broken tally are noise beside them.
   */
  @Override
  public boolean ownsHud() {
    return true;
  }

  /**
   * The three counters and the boss checklist, declared once.
   *
   * <p>There is no second, hand-written copy of this for the players the corner cannot reach. The
   * driver stack renders the same declaration in BetterHud's corner for a Java player who has it and
   * on the scoreboard sidebar for everyone else — the Bedrock player in the same run, the whole server
   * that never installed the plugin — and {@link #hudSurfaceActive(PlayerAdapter)} answers per player
   * from which of the two actually drew. That used to be two renderings, two code paths and a
   * hand-maintained edge report to keep them in step.</p>
   */
  public static HudSurfaceSpec readoutSpec() {
    HudSurfaceSpec.Builder builder = HudSurfaceSpec.persistent(SURFACE_ID)
        .anchor(HudAnchor.TOP_LEFT)
        .text(ROW_DURATION, LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
        .text(ROW_DAYS, LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DAYS))
        .text(ROW_RESETS, LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_RESETS))
        .spacer(LADDER_GAP_PIXELS);
    for (BossLadder boss : BossLadder.ORDER) {
      // A bare <value> pass-through, and it has to be. A row's template is fixed when the surface is
      // declared, but whether a rung reads as pending or defeated is a runtime fact — so the WHOLE
      // line is pushed, and the two states are two templates the publisher picks between.
      // WHITE as the floor because that is the resting state's colour: both row templates colour
      // themselves throughout, so this is only ever reached by a template that stopped doing so.
      builder.text(boss.rowKey(), LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_BOSS_ROW),
          HudAlign.LEFT, ROW_SCALE, HudColor.WHITE);
    }
    return builder.build();
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
    pushLadder();
    readout.refresh();
  }

  /**
   * Publishes the checklist: one line per rung, reading either as an unticked box or as a ticked,
   * struck-through one.
   *
   * <p>There is deliberately no tally row. Four lines that already say which are ticked do not also
   * need a number saying how many — on a corner readout that is a fifth line of reading for a fact the
   * reader can see. The count still exists where it is worth words: the announcement when a boss
   * falls, and {@code /sx experience boss list}.</p>
   *
   * <p>Read straight out of the shared state on every pass rather than mirrored into a field. That is
   * what makes a regeneration free: the rungs are world-scoped keys, a reset does not carry them, and
   * a publisher that read a cached copy would keep drawing four ticked boxes for a world in which
   * nothing has been killed yet. Pushes are idempotent and the values compare equal between pushes,
   * so re-publishing an unchanged list costs nothing downstream.</p>
   */
  private void pushLadder() {
    boolean hidden = hidden();
    for (BossLadder boss : BossLadder.ORDER) {
      boolean defeated = defeated(boss);
      // Blanked rather than left unpushed. The counters above keep running while the checklist is
      // hidden, so the surface is still live — a row with no value at all would draw the unset dash,
      // which is a readout claiming it has lost track rather than one that was switched off.
      readout.blank(boss.rowKey(), hidden);
      readout.text(boss.rowKey(), LocalizedText.of(
          defeated
              ? MessageKey.EXPERIENCE_DEATHRESETS_BOSS_DEFEATED
              : MessageKey.EXPERIENCE_DEATHRESETS_BOSS_PENDING,
          MessageArg.localized("boss", LocalizedText.of(boss.displayName()))));
    }
  }

  // ----- the live controls the boss command drives ---------------------------------------------

  /**
   * Ticks a rung off by hand, or takes it back. Returns false when nothing changed, so a caller can
   * tell "done" from "was already done".
   *
   * <p>Marking by hand records the same three facts a kill does, so a rung ticked from a command is
   * indistinguishable downstream from one ticked by a Warden dying — there is no second shape of
   * "done" for a reader to have to know about. UNticking clears all three: leaving a kill time behind
   * a cleared flag would be a record of something the checklist says never happened.</p>
   */
  public boolean markBoss(BossLadder boss, boolean done) {
    if (boss == null || defeated(boss) == done) {
      return false;
    }
    recordKill(boss, done);
    pushLadder();
    readout.refresh();
    return true;
  }

  /** Whether the checklist is currently hidden for this experience. */
  public boolean hidden() {
    return stateBoolean(KEY_LADDER_HIDDEN, false);
  }

  /**
   * Shows or hides the checklist without touching what it holds.
   *
   * <p>The counters are deliberately unaffected: this hides the OBJECTIVE, which is the part a player
   * may want out of the way once they know it by heart, not the run's own figures. Persisted, so it
   * survives a restart — a player who turned it off does not get it back on the next boot.</p>
   */
  public void setHidden(boolean hidden) {
    setStateBoolean(KEY_LADDER_HIDDEN, hidden);
    pushLadder();
    readout.refresh();
  }

  /** When a rung fell, as epoch millis, or 0 if it has not. */
  public long defeatedAt(BossLadder boss) {
    return boss == null ? 0L : stateLong(boss.stateKeyAt(), 0L);
  }

  /** The run's played seconds at the moment a rung fell, or 0 if it has not. */
  public long defeatedAtPlayed(BossLadder boss) {
    return boss == null ? 0L : stateLong(boss.stateKeyPlayed(), 0L);
  }

  /** Whether a rung is ticked off in the world currently being played. Public for the boss command. */
  public boolean isDefeated(BossLadder boss) {
    return defeated(boss);
  }

  /**
   * Writes (or clears) the three facts about a rung: that it fell, when by the wall clock, and how far
   * into the run's played time. Kept in one place so a hand mark and a real kill cannot drift apart.
   */
  private void recordKill(BossLadder boss, boolean done) {
    setStateBoolean(boss.stateKey(), done);
    if (done) {
      setStateLong(boss.stateKeyAt(), System.currentTimeMillis());
      setStateLong(boss.stateKeyPlayed(), liveRunSeconds());
      return;
    }
    setStateLong(boss.stateKeyAt(), 0L);
    setStateLong(boss.stateKeyPlayed(), 0L);
  }

  /**
   * Something died. If it was one of the four, and it died in THIS world, tick it off.
   *
   * <h2>Why the ticked rungs do not survive a reset</h2>
   * They are not carried by {@link #onPlayerDeath}'s allowlist, and that is the design rather than an
   * omission. A world is what a boss was killed IN: the monument, the Deep Dark and the End that the
   * four of them lived in all stop existing when somebody dies, so a list that carried over would be
   * crediting a run for bosses that are, along with their worlds, gone. Keeping the list world-scoped
   * is also what gives the mode its shape — reaching the Ender Dragon and then dying costs all four,
   * which is the same bargain the world itself is already on.
   *
   * <h2>Scoped to this run, because the event is not</h2>
   * {@code GameEventRouter} hands every entity death to every running match, so two Death Resets
   * worlds on one node would otherwise tick each other's lists. The order of the guards is the hot
   * path: a string compare rejects the zombie, and only a genuine boss kill in a run that has not
   * already recorded it ever pays for parsing a world name.
   */
  @Override
  public void onEntityDeath(EntityDeathGameEvent event) {
    BossLadder boss = BossLadder.match(event == null ? null : event.entityType()).orElse(null);
    if (boss == null || defeated(boss) || !inThisWorld(event.deathPosition())) {
      return;
    }
    recordKill(boss, true);
    // Straight away, not on the next cadence. A boss fight ends in a moment the player is watching for,
    // and a box that ticks itself a second later reads as the HUD having missed it.
    pushLadder();
    readout.refresh();
    int felled = defeatedCount();
    announce(MessageKey.EXPERIENCE_DEATHRESETS_BOSS_FELLED,
        MessageArg.localized("boss", LocalizedText.of(boss.displayName())),
        MessageArg.text("done", felled),
        MessageArg.text("total", BossLadder.total()));
    if (felled >= BossLadder.total()) {
      announce(MessageKey.EXPERIENCE_DEATHRESETS_BOSSES_COMPLETE);
    }
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
    // The rungs were never carried, so the state is already empty — but the SCREEN is not, and the
    // players arriving in the new world are looking at it. Repaint now rather than leave a list of
    // ticked boxes standing over terrain in which nothing has been killed.
    pushLadder();
    readout.refresh();
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
    context.debugStat("Bosses", defeatedCount() + "/" + BossLadder.total()
        + (hidden() ? " (hidden)" : ""));
    PlayerAdapter viewer = context.player();
    if (viewer != null) {
      context.debugStat("Your time", DurationText.compact(liveRunSeconds(viewer.uniqueId())));
      context.debugStat("Your deaths", stats().runDeaths(viewer.uniqueId()));
    }
  }

  private int resets() {
    return stateInt(KEY_RESETS, 0);
  }

  /** Whether this rung has been ticked off in the world currently being played. */
  private boolean defeated(BossLadder boss) {
    return stateBoolean(boss.stateKey(), false);
  }

  private int defeatedCount() {
    int felled = 0;
    for (BossLadder boss : BossLadder.ORDER) {
      if (defeated(boss)) {
        felled++;
      }
    }
    return felled;
  }

  /**
   * Whether something that died at {@code at} died in the world this experience is playing.
   *
   * <p>A linked Nether or End is not a world of its own — it is opened by, and travels with, its
   * Overworld — so the Ender Dragon dying in {@code …_r3_end} has to answer yes for the run whose
   * Overworld is {@code …_r3}. {@link WorldKey#fromRuntime} strips those suffixes, which is exactly
   * the question being asked. The GENERATION is kept in the comparison rather than falling back to
   * {@code sameRun}: a kill in a previous generation still loaded somewhere belongs to a world this
   * run has already thrown away.</p>
   */
  private boolean inThisWorld(WorldPosition at) {
    WorldAdapter world = world();
    if (world == null || at == null || at.worldName() == null || gameContext() == null) {
      return false;
    }
    if (at.worldName().equals(world.name())) {
      return true;
    }
    String namespace = gameContext().server().worlds().experiencesSubdirName();
    WorldKey ours = WorldKey.fromRuntime(world.name(), namespace).orElse(null);
    return ours != null
        && WorldKey.fromRuntime(at.worldName(), namespace).filter(ours::equals).isPresent();
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
