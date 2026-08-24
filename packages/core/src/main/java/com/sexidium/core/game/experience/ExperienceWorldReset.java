package com.sexidium.core.game.experience;

import com.sexidium.core.game.EntryPolicy;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.hardcore.HardcoreDeathOutcome;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.lib.Countdown;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.hud.HudAlign;
import com.sexidium.core.platform.hud.HudColor;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.WorldNaming;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Replaces a running experience's world with a brand-new one, without ending the match and without ever
 * sending anybody to the lobby.
 *
 * <h2>Why the two worlds overlap</h2>
 * The obvious implementation — delete this world, build another under the same name — cannot work, and
 * failing at it is instructive. A teleport is asynchronous, so players are still physically inside the
 * world when the delete runs; the platform then refuses to unload an occupied world, and deleting its
 * folder anyway leaves a <em>loaded world with nothing behind it</em> that the acquire path cheerfully
 * hands back as "fresh". Everything reports success and nothing has changed.
 *
 * <p>So the new world is built <b>alongside</b> the old one, under the next generation name
 * ({@code …_ab12cd34} → {@code …_ab12cd34_r1}), while everyone stands frozen in the world they lost. Only
 * once they have been moved across — and verifiably left — is the old world deleted. Nobody ever passes
 * through the lobby, and the world that is deleted is always empty.</p>
 *
 * <p>The changing name is not a workaround either. {@link EntryPolicy#prepareArrival} may only re-send
 * the hardcore view when the world <em>name</em> changes, so a same-name rebuild could never restore the
 * hardened hearts. Here it changes on every reset, and the arrival is an ordinary world change.</p>
 *
 * <h2>The four phases</h2>
 * <ol>
 *   <li><b>A, the death tick</b> — start a visible countdown and immediately begin acquiring the
 *       replacement. The countdown exists to cover the acquire, not the other way round.</li>
 *   <li><b>B, when the world arrives</b> — configure it and let challenges build into it, all while the
 *       match still points at the old world.</li>
 *   <li><b>C, at the end of the countdown</b> — the swap, in one tick, in a fixed order.</li>
 *   <li><b>D, afterwards</b> — delete the old world, once it is actually empty.</li>
 * </ol>
 *
 * <p>State is carried across by <b>allowlist</b>, never wholesale: a regenerated world is a new world, so
 * state describing the old one is wrong. Carrying everything would break composition immediately — a map
 * challenge guards its build with an "already built" flag, and carrying that into a fresh world leaves
 * everyone standing in empty space.</p>
 *
 * <p>An allowlist entry is either an exact key or, written with a trailing {@code *}, a namespace prefix
 * (see {@link StateCarry}). The prefix form is not a shortcut — it is the only way to name state whose
 * keys are not knowable in advance, which in practice means anything keyed by player: the run's played
 * time and per-player death counts. Those describe the RUN, not the world, and the run is the one thing a
 * regeneration does not end.</p>
 *
 * <p>There is exactly one thing the allowlist does not govern: state a challenge writes from
 * {@link Challenge#onWorldReset} in phase B. That runs on the REPLACEMENT world and is about the
 * replacement world — a SkyBlock mode recording that it has built its island there — so it is carried
 * unconditionally ({@link StateCarry#writtenDuringRebuild}). The allowlist is about what survives from
 * the world being thrown away; this is not that.</p>
 */
public final class ExperienceWorldReset {
  private static final int DEFAULT_COUNTDOWN_SECONDS = 5;
  private static final long DEFAULT_TIMEOUT_TICKS = 600L;
  private static final int DEFAULT_DELETE_ATTEMPTS = 15;
  private static final int DEFAULT_GRACE_TICKS = 100;

  private static final String COUNTDOWN_SURFACE = "resetcountdown";
  private static final String ROW_SECONDS = "seconds";
  /**
   * The resting and peak sizes of the number, as multiples of normal chat text.
   *
   * <p>Big enough to be the only thing on the screen worth looking at, because for these five seconds
   * it is: the world is ending and nothing a player does in it counts. The peak is where each new
   * number lands before it settles.</p>
   */
  private static final double COUNTDOWN_SCALE = 3.0d;
  private static final double COUNTDOWN_PEAK_SCALE = 5.0d;
  /** How long the fall from peak to resting size takes — comfortably inside one second. */
  private static final java.time.Duration COUNTDOWN_SETTLE = java.time.Duration.ofMillis(420);
  /**
   * How long the popup survives without being taken down.
   *
   * <p>A backstop, not the mechanism: the countdown is hidden explicitly when the reset releases, on
   * every exit path there is. This only decides how long a stale number could linger if the process
   * lost the reset entirely, so it is set to cover the reset watchdog rather than the countdown — a
   * duration merely as long as the countdown would blank the number during a slow acquire, which is
   * exactly when the player most needs to see that something is still happening.</p>
   */
  private static final java.time.Duration COUNTDOWN_POPUP_DURATION = java.time.Duration.ofSeconds(45);

  private final ExperienceGame game;

  private boolean running;
  private String oldWorldName;
  private String newWorldName;
  private com.sexidium.core.world.WorldKey oldWorldKey;
  private com.sexidium.core.world.WorldKey newWorldKey;
  private WorldLease pendingLease;
  private WorldAdapter pendingWorld;
  private Map<String, String> carried = Map.of();
  /** The allowlist itself, kept so the carried snapshot can be retaken after challenges rebuild. */
  private Set<String> keepKeys = Set.of();
  private Consumer<Boolean> onDone;
  // The countdown and the acquire race each other; whichever finishes last performs the swap.
  private boolean countdownDone;
  private boolean worldPrepared;

  private Countdown countdown;
  /**
   * The big number in the middle of the screen. Opened on the first reset of a match and kept for the
   * rest of it — opening a surface is cheap, but it is not free, and a mode whose whole premise is
   * that this happens repeatedly should not pay for it on every death.
   */
  private HudSurfaceHandle countdownReadout = HudSurfaceHandle.NOOP;
  private ScheduledTask watchdog;
  private ScheduledTask teardown;
  private int teardownAttempts;
  /**
   * Players whose move into the new world has been issued but has not landed. A platform teleport is
   * asynchronous, so "not in the new world yet" and "needs teleporting again" are different questions,
   * and answering the second with the first stacks teleports onto a client that is still processing the
   * previous one.
   */
  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
  /** Per-player grace revoke timers, so a second reset cannot have its window cut short by the first. */
  private final Map<UUID, ScheduledTask> graceTasks = new ConcurrentHashMap<>();

  ExperienceWorldReset(ExperienceGame game) {
    this.game = game;
  }

  /** Whether a regeneration is in flight. Entry paths refuse while this is true. */
  public boolean running() {
    return running;
  }

  /**
   * The countdown, as a number in the middle of the screen.
   *
   * <h2>Why the middle, and why it is not the boss bar</h2>
   * The boss bar below is still there and still says what it says, because it is the surface that
   * reaches everybody — Bedrock included, and every server that never installed an overlay plugin. But
   * a boss bar is chrome: it is where a mode puts a timer you can ignore. These five seconds are not
   * ignorable. Someone has just cost everyone the world they were playing in, and the count is the
   * only thing on screen that matters until it reaches zero, so it is drawn where the game already
   * puts the things it wants you to stop and look at.
   *
   * <h2>The animation is not decoration</h2>
   * Each new number arrives at {@link #COUNTDOWN_PEAK_SCALE} and settles to
   * {@link #COUNTDOWN_SCALE} over {@link #COUNTDOWN_SETTLE}. That movement is what makes a countdown
   * legible without reading it: a number that merely replaces the previous one at the same size gives
   * a glancing player nothing to tell "it is still counting" from "it has stuck", and a player who has
   * just been killed is doing a great deal of glancing.
   *
   * <p>Declared here rather than on Death Resets, which is the only mode that triggers a reset today,
   * because the countdown belongs to the reset: anything that asks for one gets the same five seconds,
   * on the same two surfaces, without having to know either exists.</p>
   */
  public static HudSurfaceSpec countdownSpec() {
    return HudSurfaceSpec.popup(COUNTDOWN_SURFACE)
        .anchor(HudAnchor.CENTER)
        .duration(COUNTDOWN_POPUP_DURATION)
        // RED, said twice on purpose. The template already carries <red><bold> for the drivers that
        // render it as a chat component (the vanilla title, the sidebar); an overlay driver flattens
        // the template to plain text and colours the whole row from its own layout, so it has to be
        // told here or it draws the same number white. See HudColor.
        .pulse(ROW_SECONDS, LocalizedText.of(MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER),
            HudAlign.CENTER, COUNTDOWN_SCALE, COUNTDOWN_PEAK_SCALE, COUNTDOWN_SETTLE, HudColor.RED)
        .build();
  }

  // ===== Phase A: the death tick ================================================================

  /**
   * Begins replacing the experience's world.
   *
   * @param reason        announced to everyone inside as the countdown starts
   * @param keepStateKeys the shared-state keys that survive into the new world — exact keys, or namespace
   *                      prefixes written with a trailing {@code *}. Everything else is dropped, because
   *                      it described a world that no longer exists.
   * @param onDone        called with whether the swap actually happened; may be null
   */
  public void reset(LocalizedText reason, Set<String> keepStateKeys, Consumer<Boolean> onDone) {
    // Two participants dying in the same tick must produce ONE reset, not two racing acquisitions.
    if (running || !game.running()) {
      return;
    }
    WorldAdapter world = game.world();
    if (world == null) {
      finish(onDone, false);
      return;
    }
    // Nobody inside means nobody to start again. Refused before `running` is even set, so no countdown
    // opens, no replacement is built and no folder is acquired for a world that would be handed to no one.
    if (game.online().isEmpty()) {
      context().server().logger().info("[deathresets] reset of '" + world.name()
          + "' refused: there is nobody inside it to start again.");
      finish(onDone, false);
      return;
    }
    running = true;
    countdownDone = false;
    worldPrepared = false;
    this.onDone = onDone;
    oldWorldName = world.name();
    // The identity, resolved once from the live world's label. Everything the world layer is asked to
    // do below is asked in terms of this, never the label.
    oldWorldKey = com.sexidium.core.world.WorldKey
        .fromRuntime(oldWorldName, context().server().worlds().experiencesSubdirName()).orElse(null);

    // Nothing is frozen. The reset used to cancel every gameplay event for the length of the countdown,
    // on the theory that a world about to be deleted should be inert — but the old world is not deleted
    // until everyone has verifiably left it, so nothing a player does in it during those seconds can
    // matter. All that machinery bought was a class of bugs where a freeze failed to lift and left the
    // world permanently unplayable. Players simply keep playing until they are moved.
    context().server().logger().info("[deathresets] reset started for '" + oldWorldName + "'");
    announce(reason);
    openCountdownReadout();

    watchdog = game.runLater(this::abortAndStayPut, timeoutTicks());

    // Stop every writer, then flush synchronously: the debounced save would otherwise land in a folder
    // that is on its way out.
    game.suspendPersistence(true);
    game.persistence().flushState();
    keepKeys = keepStateKeys == null ? Set.of() : Set.copyOf(keepStateKeys);
    carried = carryState(keepKeys);
    writeCrashNet(carried);

    // Take the dead off their death screens — a hardcore death screen has no Respawn button, so nothing
    // else ever will. They spend the countdown standing in the world they lost, like everybody else.
    //
    // Deferred by a tick, NOT run inline. This method is reached synchronously from the death event, so
    // respawning here re-enters the server's death handling while it is still unwinding: the old player
    // entity is being removed while a new one is placed. That is unsupported, and its signature is
    // exactly what was reported — a client stuck in a world that never finishes loading, entities frozen,
    // nothing breakable, cured only by relogging. The same next-tick deferral is used for the lethal blow
    // in ExperienceGame.killParticipant, for the same reason. The countdown covers the delay.
    game.runLater(this::respawnTheDead, 1L);

    countdown = game.startResetCountdown(
        countdownSeconds(), this::showCountdownNumber, this::onCountdownFinished);

    // Same tick: start building the replacement. The countdown is cover for this work, so it must not
    // wait for the countdown to end.
    newWorldKey = oldWorldKey == null
        ? null : context().server().worlds().nextExperienceGeneration(oldWorldKey);
    if (newWorldKey == null) {
      abortAndStayPut();
      return;
    }
    newWorldName = newWorldKey.runtimeName(context().server().worlds().experiencesSubdirName());
    context().server().worlds().acquireOrCreatePersistent(
        newWorldKey,
        List.of(),
        game.setup().generationFor(game.storedChallengeIds()),
        this::onNewWorldReady,
        this::abortAndStayPut
    );
  }

  /**
   * Takes every dead participant off their death screen. Re-run at the swap as well as at the start,
   * because a player who dies DURING the countdown is otherwise never respawned: their death cannot
   * start a second reset, and a teleport does not land on a corpse — so they would be left on a hardcore
   * death screen in a world about to be deleted, counted as a straggler until the teardown gave up.
   */
  private void respawnTheDead() {
    if (!running) {
      return;
    }
    for (PlayerAdapter player : List.copyOf(game.online())) {
      if (player.dead()) {
        player.forceRespawn();
      }
    }
  }

  // ===== Phase B: the replacement arrives =======================================================

  /**
   * The new world exists. Configure it and let challenges build into it — all while {@code game.world()}
   * is still the OLD world, so everything here addresses the new one by explicit reference.
   */
  private void onNewWorldReady(WorldLease lease) {
    if (!running || lease == null || lease.world() == null) {
      return;
    }
    pendingLease = lease;
    pendingWorld = lease.world();

    // A freshly acquired world carries the world layer's defaults, not the owner's.
    game.applyWorldSettings(pendingWorld);

    // Challenges rebuild BEFORE anyone is teleported, so a challenge that builds an island has somewhere
    // to build it and nobody spends a tick falling through void.
    // Already a copy (Props.asMap), so this is a genuine snapshot rather than a live view.
    Map<String, String> beforeRebuild = game.persistence().state().values();
    for (Challenge challenge : List.copyOf(game.liveChallenges())) {
      try {
        challenge.onWorldReset(pendingWorld);
      } catch (RuntimeException exception) {
        context().server().logger().warning(
            "Challenge '" + challenge.id() + "' failed to rebuild after a world reset", exception);
      }
    }

    // Re-snapshot the carried keys NOW, after the challenges have had their say. The first snapshot was
    // taken in Phase A, before this world existed, so anything a challenge writes from onWorldReset —
    // most importantly Death Resets re-seating its day baseline to the NEW world's clock — was written
    // into a state object that step 3-4 of the swap then overwrites with the Phase A copy. The symptom
    // was a day counter that kept counting from the previous world and never reset. Same allowlist, so
    // this cannot widen what survives.
    carried = carryState(keepKeys);
    // Then, ON TOP of the allowlist, whatever the rebuild just wrote. The allowlist governs what the run
    // remembers about the world it LOST; these entries describe the world it has just been given, and
    // filtering them through it discards exactly the bookkeeping a rebuilding challenge produces — its
    // "already built" marker most of all, whose loss makes onStart build the island again on the next
    // resume, over the top of whatever the players made of it. See StateCarry#writtenDuringRebuild.
    carried.putAll(StateCarry.writtenDuringRebuild(beforeRebuild, game.persistence().state().values()));
    worldPrepared = true;
    if (countdownDone) {
      // The acquire lost the race with the countdown; swap as soon as it lands.
      swap();
    }
  }

  /**
   * Puts one second of the count on screen, for everybody in the match.
   *
   * <p>Both halves of the surface are driven by the same two calls. The overlay reads the pushed value
   * and animates off the fact that it CHANGED, so the push alone is enough for it; the vanilla title
   * the other players get has no such memory and has to be re-sent, which is what {@code show} does.
   * Calling both every second is what makes one declaration cover a Java player with the plugin and
   * the Bedrock player standing next to them.</p>
   */
  private void showCountdownNumber(int secondsRemaining) {
    countdownReadout.number(ROW_SECONDS, secondsRemaining);
    countdownReadout.refresh();
    for (PlayerAdapter player : List.copyOf(game.online())) {
      countdownReadout.show(player);
    }
  }

  /** Opens the surface, once per match. A driver with nothing to draw it on returns a no-op handle. */
  private void openCountdownReadout() {
    if (countdownReadout == HudSurfaceHandle.NOOP) {
      countdownReadout = game.track(game.hudDriver().open(countdownSpec()));
    }
  }

  /** Takes the number off every screen. Called from {@link #release}, so every exit path clears it. */
  private void hideCountdownNumber() {
    for (PlayerAdapter player : List.copyOf(game.online())) {
      countdownReadout.hide(player);
    }
  }

  private void onCountdownFinished() {
    if (!running) {
      return;
    }
    // Zero, which the countdown's own tick never emits: it stops AT zero rather than announcing it.
    // Worth pushing even though the swap usually follows within the tick — when it does not, because
    // the replacement world is still being built, a number frozen at 1 reads as a hang where a zero
    // holding under the "taking a moment longer" notice reads as waiting.
    showCountdownNumber(0);
    countdownDone = true;
    if (worldPrepared) {
      swap();
      return;
    }
    // Rare: generation was needed because the pool was empty. Say so once and let Phase B finish; the
    // watchdog is the only thing that can end this.
    announce(LocalizedText.of(MessageKey.EXPERIENCE_RESET_SLOW));
  }

  // ===== Phase C: the swap ======================================================================

  /** Moves the experience — its registry row, its match, its state and its players — onto the new world. */
  private void swap() {
    if (!running || pendingWorld == null) {
      return;
    }
    // The load-bearing one. Everybody can drop DURING the countdown -- which is exactly what happened --
    // so checking only at reset() entry would have let this swap go through to an empty world, as it did.
    if (game.online().isEmpty()) {
      abandonNobodyHere();
      return;
    }
    long now = System.currentTimeMillis();
    String deletedWorld = oldWorldName;
    boolean succeeded = false;
    try {
      // 1. The registry row FIRST: loadState() below resolves through experiences.byWorld(worldName), so
      //    until the row names the new world, persistence would re-bind to the world being deleted.
      ExperienceManager experiences = context().experiences();
      String experienceId = game.persistence().experienceId();
      if (experiences != null && experiences.available() && experienceId == null) {
        // ABORT, do not carry on. This step is what makes the new world findable; without it the
        // registry keeps naming the generation that phase D is about to delete, and the next entry
        // generates an empty world over the run. Throwing lands in the catch below, which leaves the
        // match in the world it is already in and leaves `succeeded` false -- so phase D deletes
        // nothing. A reset that could not be recorded must not happen at all.
        throw new IllegalStateException("no experience registry row resolves for '" + oldWorldName
            + "'; refusing to swap, because the registry would keep pointing at the world being deleted");
      }
      if (experiences != null && experiences.available()) {
        experiences.updateWorldKey(experienceId, newWorldKey, now);
        // The per-player pointers move with it. Leaving them on the old generation would recreate the
        // whole bug one level down: a player offline across this reset would be sent back to a world
        // the teardown is about to delete.
        experiences.rehomePlayers(experienceId, newWorldKey, now);
      }
      // No registry at all (no database) is a legitimate configuration: the experience is transient,
      // nothing outside this process names its world, and there is nothing to keep in step. The case
      // that used to be silent -- a registry that IS there but resolved no row -- now throws above.

      // 2. Re-point the match. Every world read in the framework and in the mode resolves through it, so
      //    this one write moves all of them at once.
      context().games().replaceMatchWorld(game, pendingLease);

      // 3-4. Re-resolve persistence against the new folder, then write the carried keys straight back.
      game.persistence().loadState();
      game.persistence().adoptState(ExperienceState.fromValues(carried));

      // 4b. Carry the OFFLINE players' snapshots. The loop below saves everyone who is online as it
      //     moves them, which quietly made "was online at the moment somebody else died" the condition
      //     for keeping your own inventory. Everyone else was left in the folder phase D deletes, and
      //     came back to the right world with nothing in it. Before the moves, so an online player's
      //     fresher state overwrites this copy rather than the other way round.
      //     And in a mode where the reset means "everyone starts again from nothing", they come across
      //     with NOTHING -- otherwise being logged out at the moment somebody else died is a way to keep
      //     your inventory through a wipe, which is precisely the bug this argument closes.
      ExperienceStateStore store = context().experienceStore();
      if (store != null) {
        ExperienceStateStore.Carry carry =
            game.hardcoreDeathOutcome() == HardcoreDeathOutcome.RESET_WORLD
                ? ExperienceStateStore.Carry.WIPE_CONTENTS
                : ExperienceStateStore.Carry.KEEP_CONTENTS;
        int rescued = store.carryPlayerSnapshots(oldWorldName, newWorldName, carry);
        if (rescued > 0) {
          context().server().logger().info("[deathresets] carried " + rescued
              + " offline player snapshot(s) into '" + newWorldName + "'"
              + (carry == ExperienceStateStore.Carry.WIPE_CONTENTS
                  ? ", stripped to nothing like everyone who was online" : ""));
        }
      }

      // 5. Everyone across, stripped back to nothing. Each move is isolated: one player whose teleport
      //    fails must not strand the rest, nor abort the swap for everybody else.
      //    Anyone who died during the countdown is still on a death screen and cannot be teleported, so
      //    sweep again here before moving anybody.
      respawnTheDead();
      WorldAdapter freshWorld = pendingWorld;
      for (PlayerAdapter player : List.copyOf(game.online())) {
        try {
          player.resetStatuses();
          game.persistence().forgetDimension(player.uniqueId());
          WorldPosition target = game.entrySpawn(player, freshWorld);
          if (target == null) {
            context().server().logger().warning("No entry position in the regenerated world for "
                + player.name() + "; they were left where they were.");
            continue;
          }
          EntryPolicy policy = game.entryPolicy(player);
          // The client is re-told it is in a hardcore world — when its belief actually has to change.
          // In the common case it does NOT: both worlds are hardcore, so the platform already knows the
          // client believes that and sends nothing. (An earlier comment here claimed the generation
          // rename is what makes the hardcore hearts work. It is not — the rename exists so the two
          // worlds can coexist and the old one can be deleted safely.)
          // A player who has just been wiped to nothing and dropped into a fresh HARD-difficulty world
          // needs a few seconds of grace to orient and start gathering — otherwise they die on arrival
          // and re-trigger this very reset, looping. Granted before the teleport so the window covers
          // the move itself.
          PlayerAdapter arrived = player;
          grantGrace(arrived);
          // The move is asynchronous — the destination world was generated seconds ago and its spawn
          // chunk may still be loading — so everything that is only true of an ARRIVED player waits for
          // the teleport to SETTLE. Two things make this correct where the inline version was not: the
          // game mode and compass are applied to a player who is really there, and the teardown poll
          // below can tell "still travelling" from "needs moving again" instead of firing a second
          // teleport onto a client still processing the first.
          //
          // The failure branch is not defensive padding — it is the case that stranded players. A
          // refused teleport (unresolvable world, a cancelling plugin, a chunk that will not load, a
          // player still on a death screen) used to leave the UUID in `inFlight` for ever, which made the
          // straggler retry skip exactly the player it existed for, until the teardown gave up and the
          // world layer evacuated them to the lobby.
          inFlight.add(player.uniqueId());
          policy.arrive(arrived, freshWorld, target, moved -> {
            inFlight.remove(arrived.uniqueId());
            if (!Boolean.TRUE.equals(moved)) {
              context().server().logger().warning("[deathresets] " + arrived.name()
                  + " could not be moved into '" + newWorldName + "' yet; the teardown poll will retry.");
              return;
            }
            arrived.setCompassTarget(target);
          });
        } catch (RuntimeException exception) {
          context().server().logger().warning("Could not move " + player.name()
              + " into the regenerated world; they were left where they were.", exception);
        }
      }
      context().server().worlds().preserveSingle(newWorldName);
      succeeded = true;
    } catch (RuntimeException exception) {
      // Whatever else the swap failed to do, the finally below still releases the persistence suspension,
      // the countdown and the watchdog — so a failed swap can never leave the experience with its state
      // writing switched off. This log is so the failure is not silent.
      context().server().logger().severe("Error swapping to the regenerated world '" + newWorldName
          + "'; the experience stays playable in the world it was already in.", exception);
      // Give the replacement world back. It was built and never used, and `finish()` below only NULLS
      // the lease -- it does not close it -- so without this the world stays created and loaded for the
      // rest of the process's life. Worse than a leak: an orphaned `_r<n+1>` folder beside the live one
      // is exactly what makes the stale-generation guard refuse the NEXT legitimate entry, so an
      // abandoned reset would have locked the player out of their own experience.
      discardPendingWorld();
      // And say so. The player has been watching a countdown; the only other abort path (the watchdog)
      // announces, and silence here left them standing in a world with no idea what happened.
      announce(LocalizedText.of(MessageKey.EXPERIENCE_RESET_FAILED));
    } finally {
      release();
      context().server().logger().info("[deathresets] swap complete: '" + oldWorldName + "' -> '"
          + newWorldName + "' (succeeded=" + succeeded + ")");
      game.hud().render();
      finish(onDone, succeeded);
      if (succeeded) {
        // Only now, and never on the swap tick — the teleports above are asynchronous.
        scheduleTeardown(deletedWorld);
      }
    }
  }

  // ===== Phase D: the old world's teardown ======================================================

  /**
   * Deletes the old world once it is actually empty.
   *
   * <p>Polled rather than done inline because a teleport is asynchronous: at the moment the swap returns,
   * every player is still standing in the world it just moved them out of. Deleting it then is precisely
   * the bug this whole design exists to fix.</p>
   */
  private void scheduleTeardown(String worldName) {
    if (worldName == null) {
      return;
    }
    teardownAttempts = 0;
    teardown = game.runTimer(() -> tryTeardown(worldName), 20L, 20L);
  }

  private void tryTeardown(String worldName) {
    List<PlayerAdapter> stragglers = game.online().stream()
        .filter(player -> player.world() != null
            && WorldNaming.belongsToExperience(player.world().name(), worldName))
        .toList();
    if (!stragglers.isEmpty() && ++teardownAttempts < deleteAttempts()) {
      // Something put them back, or their teleport failed outright. Re-issue it rather than delete the
      // world out from under them — but ONLY for a player whose teleport is not still in flight. A move
      // into a freshly generated world can easily take longer than this poll's one second, and stacking
      // a second teleport onto an unfinished one is what desynchronises the client (see swap): it then
      // sits in a world that never finishes loading, unable to break anything, until it relogs. A player
      // who is still travelling is not a straggler, just slow.
      WorldAdapter current = game.world();
      for (PlayerAdapter straggler : stragglers) {
        if (inFlight.contains(straggler.uniqueId())) {
          continue;
        }
        WorldPosition target = current == null ? null : game.entrySpawn(straggler, current);
        if (target != null) {
          inFlight.add(straggler.uniqueId());
          PlayerAdapter moving = straggler;
          // Settle-based, so a refused retry frees the player to be retried again on the next poll
          // instead of pinning them as "still travelling" for the rest of the reset.
          straggler.teleport(target, moved -> inFlight.remove(moving.uniqueId()));
        }
      }
      return;
    }
    // Out of attempts. Deleting now would be the single worst thing this class can do: the world layer
    // evacuates a doomed world by teleporting whoever is left TO THE LOBBY SPAWN, which is the one
    // outcome this whole design exists to prevent — a player loses their run and lands in the hub with no
    // explanation. A leftover world folder is wasted disk; a lobbied player is a broken game. Give up on
    // the delete instead, and say so loudly enough to be actionable.
    if (!stragglers.isEmpty()) {
      cancelTeardown();
      context().server().logger().warning("[deathresets] gave up deleting '" + worldName + "': "
          + stragglers.size() + " player(s) are still in it after " + teardownAttempts
          + " attempts. Leaving the folder on disk — deleting it would evacuate them to the lobby.");
      return;
    }
    cancelTeardown();
    deleteOldWorld(worldName);
  }

  private void deleteOldWorld(String worldName) {
    com.sexidium.core.world.WorldKey key = com.sexidium.core.world.WorldKey
        .fromRuntime(worldName, context().server().worlds().experiencesSubdirName()).orElse(null);
    if (key == null || !context().server().worlds().deletePersistent(key)) {
      // The world layer refuses to delete a world it could not unload, and it is right to: the players
      // are safely elsewhere, so a leftover world is wasted disk, not a broken run. Boot cleanup and the
      // generation probe both cope with it being there.
      context().server().logger().warning("The regenerated experience's previous world '" + worldName
          + "' could not be deleted yet; it will be left on disk.");
    }
  }

  /**
   * Makes a freshly-wiped player briefly unkillable so they are not killed on arrival in a hard world and
   * loop straight back into another reset.
   *
   * <p>The revoke is scheduled HERE, when the grace is granted, and never from a teleport callback. It
   * has to be: the flag is persistent NBT that nothing else expires, so any path that skipped the
   * callback — a refused teleport, a disconnect mid-move — left a player permanently invulnerable, and
   * because {@code resetStatuses} did not clear it either, that followed them into the lobby and into
   * every later match. This is the "players are not taking damage" report.</p>
   *
   * <p>One timer per player, cancelled before it is replaced: two resets a few seconds apart would
   * otherwise let the FIRST reset's revoke strip the SECOND reset's grace early, which is precisely the
   * die-on-arrival loop the grace exists to prevent.</p>
   */
  private void grantGrace(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    revokeGrace(player);
    player.setInvulnerable(true);
    UUID playerId = player.uniqueId();
    graceTasks.put(playerId, game.runLater(() -> {
      graceTasks.remove(playerId);
      if (player.online()) {
        player.setInvulnerable(false);
        player.clearDamageImmunity();
      }
    }, Math.max(1L, graceTicks())));
  }

  /** Ends a player's grace now, cancelling its timer. Safe to call for a player who never had one. */
  private void revokeGrace(PlayerAdapter player) {
    ScheduledTask pending = graceTasks.remove(player.uniqueId());
    if (pending != null) {
      pending.cancel();
    }
    player.setInvulnerable(false);
  }

  /**
   * Ends every outstanding grace immediately. Called from the paths that tear the reset down, because
   * {@code game.runLater} is a TRACKED task and {@code cleanup()} cancels it — so a match ending inside
   * the grace window would otherwise cancel the revoke and release everyone to the lobby invulnerable.
   */
  private void revokeAllGrace() {
    for (PlayerAdapter player : List.copyOf(game.online())) {
      revokeGrace(player);
    }
    for (ScheduledTask pending : List.copyOf(graceTasks.values())) {
      pending.cancel();
    }
    graceTasks.clear();
  }

  private void cancelTeardown() {
    if (teardown != null) {
      teardown.cancel();
      teardown = null;
    }
    // Nothing is watching for these to land any more; a teleport that never completed (a failed move, a
    // player who quit mid-flight) would otherwise mark them travelling for ever and silence the next
    // reset's straggler check.
    inFlight.clear();
  }

  // ===== failure + shutdown =====================================================================

  /**
   * The regeneration could not be completed, so nothing about it happens.
   *
   * <p>Everyone stays exactly where they are, in a world that is completely intact — because the old
   * world is never touched until the swap has already succeeded. That is the whole benefit of building
   * the replacement alongside it: the failure mode is "nothing happened", not "your run is gone".</p>
   */
  private void abortAndStayPut() {
    abortAndStayPut(true, "Could not regenerate the experience world '" + oldWorldName
        + "'; the match continues in the world it was already in.",
        LocalizedText.of(MessageKey.EXPERIENCE_RESET_FAILED));
  }

  /**
   * Every player had gone by the time the replacement was ready, so there is nobody for the new world to
   * be for.
   *
   * <p>This is the 03:24:40 case: a network outage took all three players out DURING the countdown, and
   * the swap went through anyway — announcing "everyone starts again from nothing" to an empty world and
   * destroying a fifty-five-day run on behalf of nobody. Abandoning here costs nothing, because the old
   * world is untouched until the swap succeeds.</p>
   *
   * <p>Abandoned, not deferred. Holding the reset open for reconnections would mean holding a suspended
   * run, a half-built folder and a frozen persistence layer for up to the reconnect window — and
   * {@code reset-timeout-ticks} would fire the watchdog and abandon it anyway, thirty seconds later,
   * down the SEVERE path. This is the same outcome, said honestly and immediately.</p>
   *
   * <p>INFO, and no announcement: this is a correct outcome rather than a failure, and
   * {@code announce} iterates the players who are online — which, by definition of this branch, is
   * nobody.</p>
   */
  private void abandonNobodyHere() {
    abortAndStayPut(false, "[deathresets] reset of '" + oldWorldName + "' abandoned: every player had"
        + " left or dropped before the replacement was ready, so there was nobody for the new world to"
        + " be for. The world is kept exactly as it is.", null);
  }

  private void abortAndStayPut(boolean failure, String logLine, LocalizedText announcement) {
    if (!running) {
      return;
    }
    if (failure) {
      context().server().logger().severe(logLine);
    } else {
      context().server().logger().info(logLine);
    }
    discardPendingWorld();
    release();
    if (announcement != null) {
      announce(announcement);
    }
    // false either way, and that matters beyond tidiness: DeathResetsChallenge takes its reset counter
    // back on a false answer, so an abandoned reset does not leave the run claiming a world it still has.
    finish(onDone, false);
  }

  /** Throws away a half-built replacement so it cannot linger as an orphan folder. */
  private void discardPendingWorld() {
    if (pendingLease != null) {
      pendingLease.close();
      pendingLease = null;
    }
    pendingWorld = null;
    if (newWorldKey != null) {
      context().server().worlds().deletePersistent(newWorldKey);
    }
    newWorldKey = null;
  }

  /**
   * Gives up on any reset in flight, for a match ending underneath one, and completes any outstanding
   * teardown synchronously.
   *
   * <p>The teardown timer is a tracked task that {@code cleanup()} cancels, so without flushing it here a
   * match that ends during Phase D would leak the old world's folder for ever.</p>
   */
  void abandon() {
    String outstanding = teardown != null ? oldWorldName : null;
    // Before anything else: the grace revokes are TRACKED tasks, and the cleanup that follows a match
    // ending cancels tracked tasks. Cancelling a revoke without revoking releases the player to the
    // lobby permanently invulnerable.
    revokeAllGrace();
    cancelTeardown();
    // A match ending mid-reset leaves a replacement world that will never be swapped in. Give it back
    // for the same reason the failed swap does: an unused `_r<n+1>` folder beside the live one is what
    // makes the stale-generation guard refuse the next entry into this experience.
    if (running) {
      discardPendingWorld();
    }
    if (running || oldWorldName != null) {
      release();
      running = false;
    }
    if (outstanding != null) {
      deleteOldWorld(outstanding);
    }
  }

  /**
   * Releases everything the reset put on hold: the persistence suspension, the countdown bar and the
   * watchdog. Idempotent, and called from every exit path (success, abort, watchdog, match-end) so a
   * reset can never leave the experience with its state-writing switched off.
   */
  private void release() {
    game.suspendPersistence(false);
    hideCountdownNumber();
    if (countdown != null) {
      countdown.stop();
      countdown = null;
    }
    if (watchdog != null) {
      watchdog.cancel();
      watchdog = null;
    }
  }

  private void finish(Consumer<Boolean> callback, boolean succeeded) {
    running = false;
    onDone = null;
    pendingLease = null;
    pendingWorld = null;
    // NOT cleared here: the teleports issued by this swap are still in the air, and the teardown poll
    // that starts after it is exactly the thing that must not act on them. The set drains as each
    // teleport lands, and cancelTeardown clears whatever never did.
    if (callback != null) {
      callback.accept(succeeded);
    }
  }

  // ===== helpers ================================================================================

  private void announce(LocalizedText message) {
    if (message == null) {
      return;
    }
    for (PlayerAdapter player : List.copyOf(game.online())) {
      context().server().messages().send(player, message);
    }
  }

  /** The allowlisted subset of the current shared state — everything else describes a world that is gone. */
  private Map<String, String> carryState(Set<String> keepStateKeys) {
    return StateCarry.select(game.persistence().state().values(), keepStateKeys);
  }

  /**
   * Parks the surviving state in the registry row as well.
   *
   * <p>Belt and braces rather than the only copy: the real store is {@code state.yml}, which lives inside
   * a world folder, and for the span of a regeneration there are two of those and neither is
   * authoritative. {@code ExperiencePersistence.loadState} rehydrates from this column when it finds no
   * file, so a server that dies mid-swap comes back with the run's counters.</p>
   */
  private void writeCrashNet(Map<String, String> values) {
    ExperienceManager experiences = context().experiences();
    String experienceId = game.persistence().experienceId();
    if (experiences == null || !experiences.available() || experienceId == null) {
      return;
    }
    experiences.updateChallengeState(experienceId,
        ExperienceState.fromValues(values).encode(), System.currentTimeMillis());
  }

  private int countdownSeconds() {
    return Math.max(1, config().getInt(
        "experiences.common.reset-countdown-seconds", DEFAULT_COUNTDOWN_SECONDS));
  }

  /** How long a freshly-arrived player is invulnerable, so a wiped player in a hard world isn't killed on
   *  arrival and looped back into another reset. In ticks. */
  private int graceTicks() {
    return Math.max(20, config().getInt(
        "experiences.common.reset-grace-ticks", DEFAULT_GRACE_TICKS));
  }

  private long timeoutTicks() {
    return Math.max(100L, config().getLong(
        "experiences.common.reset-timeout-ticks", DEFAULT_TIMEOUT_TICKS));
  }

  private int deleteAttempts() {
    return Math.max(1, config().getInt(
        "experiences.common.reset-delete-attempts", DEFAULT_DELETE_ATTEMPTS));
  }

  private GameContext context() {
    return game.gameContext();
  }

  private com.sexidium.core.platform.ConfigurationAdapter config() {
    return context().server().configuration();
  }
}
