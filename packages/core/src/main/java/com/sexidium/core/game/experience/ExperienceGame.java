package com.sexidium.core.game.experience;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.AbstractGame;
import com.sexidium.core.game.EntryPolicy;
import com.sexidium.core.game.GameEvents;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.game.experience.compose.BlockBreakService;
import com.sexidium.core.game.experience.compose.ChallengeContext;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DamageContext;
import com.sexidium.core.game.experience.compose.DamagePipeline;
import com.sexidium.core.game.experience.compose.DropPipeline;
import com.sexidium.core.game.experience.compose.ExperienceStats;
import com.sexidium.core.game.experience.compose.HealthModel;
import com.sexidium.core.game.experience.compose.MobRegistry;
import com.sexidium.core.game.experience.compose.OccupancyLedger;
import com.sexidium.core.game.hardcore.HardcoreDeathOutcome;
import com.sexidium.core.game.hardcore.HardcoreDemand;
import com.sexidium.core.game.hardcore.HardcoreRule;
import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.game.presence.PlayerControlWatch;
import com.sexidium.core.platform.MobHandle;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.SafeSpawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A player-created "experience": one persistent world running any combination of {@link Challenge}
 * twists at once (the headline being e.g. xp-health + shared-inventory + shared-life together). The
 * game is OPEN-ENDED — it has no win/lose condition, never auto-ends, and a death never ejects a
 * player. The only way out is {@code /leave} (which routes through {@code GameManager.removePlayer}).
 * The one exception is HARDCORE, where a death ends the world for good: see {@link #entryPolicy}, which
 * is how this host declares that everybody plays in survival and that a lost world can only be
 * spectated, and {@code handleHardcoreDeath}, which is what ends it.
 *
 * <p>This host owns the shared participant set, the single status boss bar, persistence and the
 * never-eject respawn handling; each selected challenge contributes only its mechanic via the
 * {@link ExperienceHost} surface. The persistence (shared state + player snapshots) is delegated to
 * {@link ExperiencePersistence}, the unified HUD + toggle gestures to {@link AbstractGame} (installed
 * via {@code installHud}) and the pipeline wiring to {@link ChallengeRegistryImpl}; this class stays
 * the public facade.</p>
 */
public final class ExperienceGame extends AbstractGame implements ChallengeContext, PersistenceHost {
  public static final String MODE_ID = "experience";
  /** Watchdog passes (one a second) between registry reads of the lost flag — see enforceEntryPolicies. */
  private static final int REGISTRY_POLL_PASSES = 10;
  /** How many times the settle pass waits for an arriving player before giving up on them. */
  private static final int SETTLE_ATTEMPTS = 20;
  /** Ticks between those passes — half a second, so a normal arrival is caught on the first one. */
  private static final long SETTLE_INTERVAL_TICKS = 10L;

  // Mutable so challenges can be added/removed at runtime (live experience editor / chaos cycle).
  private final List<Challenge> challenges;
  private final List<String> challengeIds;
  // The non-challenge options (map type + keep-inventory), read from the mode-arg option tokens.
  private ExperienceSetup setup;
  // Per-challenge teardown ledgers (timers, boss bars, HUD panels, pipeline unregisters), keyed by id.
  private final Map<String, ChallengeBinding> bindings = new HashMap<>();
  // The binding currently being wired; runTimer/track/recordUndo attribute to it while non-null.
  private ChallengeBinding activeBinding;
  // True once challenges are attached/registered/started; gates event dispatch so a RUNNING-but-not-yet
  // wired experience (e.g. mid-restore) never routes an event to a challenge with a null host.
  private boolean challengesReady;
  // Everything hardcore means here: the world flag, the cached one-way "lost" fact, and what a death
  // costs. The rule owns the bookkeeping so this host only owns what is specific to an experience —
  // what to announce, and who to lock into spectator. See HardcoreRule.
  private final HardcoreRule hardcore = new HardcoreRule(new RegistryLedger());
  // Regenerates this experience's world under its players; published to challenges as a service.
  private final ExperienceWorldReset worldReset = new ExperienceWorldReset(this);
  // True for the length of a world reset: nothing may write state into a folder that is being deleted.
  private boolean persistenceSuspended;
  // Players already told why they are locked into spectator, so the watchdog explains itself once a visit.
  private final java.util.Set<java.util.UUID> lockExplained = new java.util.HashSet<>();

  // Resolved world key / experience id, shared challenge state (load + debounced flush) and per-player
  // position/inventory snapshots (load + capture, with the foreign-world teleport guards).
  private final ExperiencePersistence persistence = new ExperiencePersistence(this, gameContext);

  // Composition layer: the shared pipelines challenges contribute to, the typed capability registry
  // siblings publish into, the shared stat counters, the unified HUD and the per-tick driver. See
  // docs/gameplay/experiences.md.
  private final DropPipeline dropPipeline = new DropPipeline(this);
  private final BlockBreakService blockBreakService = new BlockBreakService(dropPipeline);
  private final DamagePipeline damagePipeline = new DamagePipeline();
  private final HealthModel healthModel = new HealthModel(this);
  private final MobRegistry mobRegistry = new MobRegistry(this);
  private final ExperienceStats experienceStats = new ExperienceStats(this);
  // How long this run has actually been PLAYED, buffered in memory and committed on a slower timer. It
  // lives on the game (which survives a world regeneration) rather than in the shared state, so seconds
  // accrued across a swap are not written into the folder the swap is deleting. See accrueOccupancy.
  private final OccupancyLedger occupancy = new OccupancyLedger();
  private long ticksSinceStatsCommit;
  // The unified per-player HUD is owned by AbstractGame; installed in initChallenges() and reached via
  // hud(). Challenges register their HudContributors through the ChallengeRegistry as before.
  private final Map<Class<?>, Object> services = new HashMap<>();
  // ---- players nobody is driving (see PlayerControlWatch) ----
  // Read once in start(), clamped there, so a tick-path question is never a config lookup.
  private boolean downedProtection = true;
  private long downedIdleMillis = 8_000L;
  private long downedTailMillis = 2_000L;
  private double downedDeaggroRadius = 32.0;

  public ExperienceGame(GameContext gameContext, List<String> modeArgs) {
    super(gameContext, MODE_ID, "Experience", 1);
    // The mode args carry the challenge ids plus (optionally) the experience's map type as a
    // "world:<id>" token — see ExperienceWorldType. Carrying it in the args is what makes the chosen
    // starting dimension survive a server restart along with the rest of the persisted match state.
    this.challengeIds = List.copyOf(ExperienceSetup.stripArgs(modeArgs));
    // forChallenges: a challenge whose whole point is the stakes (Death Resets) forces hardcore on here,
    // exactly as it did when the world was generated, so the host and the world can never disagree.
    this.setup = ExperienceSetup.fromArgs(modeArgs).forChallenges(this.challengeIds);
    this.challenges = new ArrayList<>(ChallengeCatalog.create(this.challengeIds));
  }

  /** The resolved, deduplicated challenge ids actually running (used for persistence / display). */
  public List<String> activeChallengeIds() {
    return challenges.stream().map(Challenge::id).toList();
  }

  /**
   * The {@code experiences.updated_at} this running world was last reconciled against.
   *
   * <p>A live match composes its challenge set and its setup ONCE, from the mode args captured when
   * it started, and used to never look at the registry row again — so an owner editing the map from
   * the lobby depended entirely on one unacknowledged bus message reaching this node. When it did not,
   * everyone who walked in afterwards played the old challenge set for as long as the world stayed up,
   * and nothing ever healed it. Comparing this stamp against the row is what makes the DB the truth
   * for the live world too, at the cost of one primary-key read per entry.</p>
   */
  private long appliedRegistryStamp;

  long appliedRegistryStamp() {
    return appliedRegistryStamp;
  }

  void markRegistryApplied(long stamp) {
    this.appliedRegistryStamp = stamp;
  }

  // ----- seams for the world-reset collaborator (same package) --------------------------------

  /** Whether the match is live. Widened from AbstractGame's protected form for the reset collaborator. */
  boolean running() {
    return isRunning();
  }

  ExperiencePersistence persistence() {
    return persistence;
  }

  ExperienceSetup setup() {
    return setup;
  }

  /** The stored challenge ids, in the order the owner chose them (what the world shape is derived from). */
  List<String> storedChallengeIds() {
    return challengeIds;
  }

  List<Challenge> liveChallenges() {
    return challenges;
  }

  /**
   * Stops every periodic and event-driven WRITE of player/shared state.
   *
   * <p>Set for the length of a world reset. Everything that persists here writes into the world folder,
   * and for those few seconds that folder is either about to be deleted or does not exist — an autosave
   * landing in the middle would either be thrown away with the old world or, worse, restore a player
   * into coordinates from terrain that no longer exists.</p>
   */
  void suspendPersistence(boolean suspended) {
    this.persistenceSuspended = suspended;
  }

  /** Re-applies the owner's per-world settings to the match world, which a fresh one does not carry. */
  void applyWorldSettings() {
    applyWorldSettings(world());
  }

  /**
   * Re-applies the owner's per-world settings to a NAMED world, which may not be the match world yet.
   *
   * <p>The regeneration path needs this: it prepares the replacement world — keep-inventory, hardcore,
   * whatever a challenge builds into it — while the match is still pointing at the old one, so that the
   * moment players are moved across, the world they land in is already correct. Aiming at {@code world()}
   * there would configure the world about to be deleted.</p>
   */
  void applyWorldSettings(WorldAdapter target) {
    applyKeepInventory(target);
    applyHardcore(target);
  }

  /** The world-reset collaborator, published to challenges as a service and used by the entry guards. */
  public ExperienceWorldReset worldReset() {
    return worldReset;
  }

  /**
   * The clock a world regeneration counts down on while it prepares the replacement world. Widened
   * from {@code AbstractGame}'s protected timers so the reset collaborator can drive it without
   * reaching through the host.
   *
   * <p><b>Bar-less on purpose.</b> The reset draws the count itself, as one big red number in the
   * middle of the screen (see {@link ExperienceWorldReset#countdownSpec()}) — on BetterHud where the
   * player has it, as a vanilla title where they do not. A boss bar counting the same five seconds
   * underneath it was a second countdown for the same event, and read as one: two clocks, disagreeing
   * by the width of a tick. The number is the surface; nothing else needs to say it.</p>
   */
  com.sexidium.core.lib.Countdown startResetCountdown(
      int seconds, java.util.function.IntConsumer onSecond, Runnable onComplete) {
    return timerHidden(seconds, onSecond, onComplete);
  }

  /** The map type this experience runs — decides which dimension a first-time player starts in. */
  public ExperienceWorldType worldType() {
    return setup.worldType();
  }

  /** Whether death keeps items + XP here. Applied to every dimension of the experience. */
  public boolean keepInventory() {
    return setup.keepInventory();
  }

  /**
   * Flips the keep-inventory rule on a RUNNING experience (the owner toggled it in the GUI): the new
   * value is applied to every dimension immediately, so it takes effect on the very next death.
   */
  public void setKeepInventoryLive(boolean keepInventory) {
    setup = setup.withKeepInventory(keepInventory);
    applyKeepInventory();
  }

  /**
   * Pushes the experience's keep-inventory choice onto its Overworld AND its linked Nether/End, so the
   * rule follows the player through every world this experience owns rather than only the one they
   * started in. Re-applied on every start/restore because the world layer sets its own default when a
   * world is (re)acquired.
   */
  private void applyKeepInventory() {
    applyKeepInventory(world());
  }

  private void applyKeepInventory(WorldAdapter target) {
    if (target != null) {
      target.setKeepInventoryEverywhere(setup.keepInventory());
    }
  }

  /** Whether this experience is HARDCORE: one death and, by default, the world is lost for good. */
  public boolean hardcore() {
    return setup.hardcore();
  }

  /** What a death costs here — losing the world, or (Death Resets) replacing it. */
  public HardcoreDeathOutcome hardcoreDeathOutcome() {
    return hardcore.outcome();
  }

  /** Pushes the hardcore flag onto the experience's Overworld AND its linked dimensions. */
  private void applyHardcore() {
    applyHardcore(world());
  }

  private void applyHardcore(WorldAdapter target) {
    hardcore.setEnabled(setup.hardcore());
    hardcore.applyToWorld(target);
  }

  /**
   * Flips hardcore on a RUNNING experience (the owner toggled it): applied to every dimension at once, so
   * it takes effect on the very next death.
   *
   * <p>Everyone inside is told, because the stakes of the world they are standing in have just changed
   * and only one of the two halves of hardcore can follow them there. The world half (hard difficulty, a
   * death that ends the run) is immediate; the hardened hearts are not, because a client is only told
   * what a world is when it is sent one — so the message says so rather than leaving a player to read
   * ordinary hearts as ordinary stakes.</p>
   */
  public void setHardcoreLive(boolean hardcore) {
    if (setup.hardcore() == hardcore) {
      return;
    }
    // A challenge that exists because of the stakes (Death Resets) cannot have them switched off under
    // it: the world was generated hardcore and the mode's death handling assumes it still is.
    if (!hardcore && ChallengeCatalog.anyRequiresHardcore(activeChallengeIds())) {
      return;
    }
    setup = setup.withHardcore(hardcore);
    applyHardcore();
    LocalizedText notice = LocalizedText.of(
        hardcore ? MessageKey.EXPERIENCE_HARDCORE_ON : MessageKey.EXPERIENCE_HARDCORE_OFF);
    for (PlayerAdapter player : online()) {
      gameContext().server().messages().send(player, notice);
    }
  }

  /**
   * A death in a hardcore experience: the world is LOST.
   *
   * <p>The death screen is vanilla and deliberately untouched — seeing it is the whole moment. What
   * changes is what comes after it: the world is marked dead (one-way; hardcore means the death cannot
   * be taken back), and from this moment on the world can only be spectated.</p>
   *
   * <p>NOBODY is moved anywhere. The player who died stays in the match and comes back from their
   * death screen as a SPECTATOR of the world they lost, right where it happened — the respawn flow
   * applies the (now spectator) entry policy like any other respawn. Everyone else is flipped to
   * spectator on the spot. Leaving is what {@code /leave} is for, and deleting the world is the owner's
   * decision to make from the lobby whenever they are ready.</p>
   */
  private void handleHardcoreDeath(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    // The rule decides: only the FIRST death in a lose-the-world experience takes it (a second death in
    // the same moment must not re-announce it), and an experience whose deaths RESET the world never
    // takes it at all — announcing an ending and marking the row dead would end a run that continues.
    if (!hardcore.recordDeath()) {
      return;
    }
    for (PlayerAdapter participant : List.copyOf(online())) {
      gameContext().server().messages().send(participant,
          LocalizedText.of(MessageKey.EXPERIENCE_HARDCORE_LOST));
      // Living bystanders become spectators immediately (the watchdog would catch them within a second
      // anyway; this just spares them a second of a playable-looking dead world). The player on the
      // death screen is left alone — their respawn applies the same policy the moment they are back.
      if (!participant.dead()) {
        entryPolicy(participant).applyTo(participant);
        lockExplained.add(participant.uniqueId());
      }
    }
  }

  /**
   * Where this experience's hardcore rule reads and writes the one-way "the world is lost" fact: the
   * experience registry row. Both halves are best-effort by design — a transient experience has no row
   * at all, and {@link HardcoreRule} keeps the in-memory flag either way, so a database that refuses the
   * write can never hand a hardcore run back to the player who just lost it.
   */
  private final class RegistryLedger implements HardcoreRule.Ledger {
    @Override
    public boolean lost() {
      ExperienceManager experiences = gameContext().experiences();
      String experienceId = persistence.experienceId();
      if (experiences == null || !experiences.available() || experienceId == null) {
        return false;
      }
      ExperienceManager.Experience experience = experiences.get(experienceId);
      return experience != null && experience.isLost();
    }

    @Override
    public void markLost() {
      ExperienceManager experiences = gameContext().experiences();
      String experienceId = persistence.experienceId();
      if (experiences != null && experiences.available() && experienceId != null) {
        experiences.markDead(experienceId, System.currentTimeMillis());
      }
    }
  }

  /**
   * The stable experience world key derived from a runtime world name; delegates to
   * {@link ExperiencePersistence#experienceKey}. Kept here so existing callers/tests resolve unchanged.
   */
  static String experienceKey(String name, String subdirName) {
    return ExperiencePersistence.experienceKey(name, subdirName);
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    beginRunning();
    // Resolve the experience id + shared state first so each starting participant can be restored to
    // their saved position/inventory in this experience.
    persistence.loadState();
    // …and only then can the registry be asked whether this world was already lost, which decides how
    // every one of those participants is about to arrive.
    refreshLostWorld();
    if (participants != null) {
      for (PlayerAdapter participant : participants) {
        startParticipant(participant);
      }
    }
    announce(MessageKey.EXPERIENCE_START,
        MessageArg.text("challenges", challengeSummary()),
        MessageArg.text("count", online().size()));
    initChallenges();
    // Only after the challenges have built. See settleParticipants for why this cannot be done earlier.
    settleParticipants();
  }

  /**
   * Moves any participant the challenges have just built terrain on top of.
   *
   * <p><b>The order this exists to survive.</b> Players are teleported into the world by the launcher and
   * are already standing in it when {@code start} runs; the challenges build their map afterwards, from
   * {@link Challenge#onStart}. For a mode that GENERATES its world that is backwards — the map appears
   * around a player who is already somewhere — and the only reason it went unnoticed for so long is that
   * the SkyBlock modes happen to lay their island one block under the world spawn, so a player waiting at
   * the raw spawn ends up standing exactly on the new grass. Layered Dimensions fills its chunk from y 312
   * down to y 13, and the same raw spawn at y 64 is then two hundred and fifty blocks INSIDE the ground:
   * pitch black, suffocating, six seconds into the run.</p>
   *
   * <p>The world-regeneration path never had this problem, and that asymmetry is the tell: it builds in
   * phase B and teleports in phase C, so the terrain is always there first. That is why a reset landed
   * correctly on top of the column while the very first spawn did not.</p>
   *
   * <p>Deliberately conditional on being BURIED rather than unconditional. A returning player is standing
   * wherever they logged out, which is theirs — re-seating them to spawn because the match just started
   * would be a worse bug than the one this fixes. Somebody encased in blocks cannot be anywhere they chose
   * to be.</p>
   */
  private void settleParticipants() {
    scheduleSettle(SETTLE_ATTEMPTS);
  }

  /**
   * Polls until every participant has actually ARRIVED, then checks each one where they landed.
   *
   * <p>The first version of this ran inline at the end of {@code start()} and did nothing at all, for a
   * reason worth keeping written down: a platform teleport is asynchronous, so at that moment the players
   * are still in the LOBBY. Worse, it did not fail loudly — {@code SafeSpawn.buried} takes the world from
   * the POSITION it is handed, so it dutifully inspected the lobby floor, found air, and concluded
   * everybody was fine. No exception, no log, a green design test, and a player encased in rock.</p>
   *
   * <p>So arrival is waited for rather than assumed, and a player who is not in this experience's world
   * yet is skipped rather than judged. Bounded, because a teleport that never lands must not leave a
   * timer running for the life of the match.</p>
   */
  private void scheduleSettle(int attemptsLeft) {
    runLater(() -> {
      WorldAdapter world = world();
      if (world == null) {
        return;
      }
      boolean stillArriving = false;
      for (PlayerAdapter player : List.copyOf(online())) {
        if (!insideThisExperience(player)) {
          stillArriving = true;
          continue;
        }
        settleOne(world, player);
      }
      if (stillArriving && attemptsLeft > 1) {
        scheduleSettle(attemptsLeft - 1);
      }
    }, SETTLE_INTERVAL_TICKS);
  }

  /** Whether the player is really in this experience — its Overworld or one of its linked dimensions. */
  private boolean insideThisExperience(PlayerAdapter player) {
    WorldAdapter world = world();
    WorldAdapter current = player == null ? null : player.world();
    return world != null && current != null
        && com.sexidium.core.world.WorldNaming.belongsToExperience(current.name(), world.name());
  }

  private void settleOne(WorldAdapter world, PlayerAdapter player) {
    if (!SafeSpawn.buried(world, player.position())) {
      return;
    }
    // Nearest free block first. The entry position below resolves through the heightmap, which always
    // answers with the OUTDOOR SURFACE of the column -- so rescuing someone suffocating in a cave put
    // them on the hill above it, and someone sealed in a building on its roof. Local search keeps them
    // where they were.
    WorldPosition nearby = SafeSpawn.nearestFree(world, player.position());
    if (nearby != null) {
      player.teleport(nearby);
      return;
    }
    WorldPosition safe = entrySpawn(player, world);
    if (safe == null || SafeSpawn.buried(world, safe)) {
      // Nothing better to offer. Say so rather than teleporting them from one wall into another.
      gameContext().server().logger().warning("[" + persistence.experienceId() + "] " + player.name()
          + " started inside terrain a challenge built around them and no safe spot could be found.");
      return;
    }
    player.teleport(safe);
  }

  /**
   * Re-initialises a running experience after a server restart: a persisted experience match is
   * rehydrated (its world reloaded) then {@code restore()}d, which marks it RUNNING. Without wiring
   * the challenges here they would receive events with a null host (NPE). The reloaded world is already
   * attached to the match by the time this runs, so {@link ExperiencePersistence#loadState} resolves correctly.
   */
  @Override
  public void restore(MatchSnapshot matchSnapshot) {
    super.restore(matchSnapshot);
    persistence.loadState();
    refreshLostWorld();
    initChallenges();
  }

  /**
   * Wires every challenge into the host (attach → register → onStart) and starts the shared timers.
   * Shared by {@link #start} (fresh) and {@link #restore} (after a restart), so a restored experience
   * is fully live — its challenges are never left unattached.
   */
  private void initChallenges() {
    for (Challenge challenge : challenges) {
      challenge.attach(this);
      bindings.put(challenge.id(), new ChallengeBinding(challenge));
    }
    // Composition pass: each challenge declares its pipeline contributions and published
    // capabilities before any onStart runs, so a challenge's onStart can already fetch a sibling's
    // service. Drops emit at the configured max stack size.
    // The owner's keep-inventory choice, applied across every dimension before anyone can die.
    applyKeepInventory();
    // Regenerating this experience's world is a host capability, not a challenge's own business — the
    // world, the lease, the persistence and the players all belong to the host. Published so a challenge
    // that wants a reset asks for one instead of trying to perform it.
    publish(ExperienceWorldReset.class, worldReset);
    // What a death costs, decided before any of it can happen — through the same resolution the menu tile
    // and the setup use, so the screen that says "a death replaces the world" and the code that performs
    // the death can never mean different things. Read from the LIVE instances (unlike the world-shape
    // flags, this decides nothing about the world); the first challenge with an opinion wins, so two of
    // them cannot fight over one death.
    HardcoreDemand demand = HardcoreDemand.of(challenges.stream()
        .map(challenge -> (HardcoreDemand.Source) new HardcoreDemand.Source() {
          @Override
          public boolean requiresHardcore() {
            return challenge.requiresHardcore();
          }

          @Override
          public HardcoreDeathOutcome deathOutcome() {
            return challenge.hardcoreDeathOutcome();
          }

          @Override
          public String displayName() {
            return challenge.displayName();
          }
        })
        .toList());
    if (demand.outcome() != null) {
      hardcore.outcome(demand.outcome());
    }
    // …and the hardcore flag, which the world layer resets to its own default on every (re)acquire.
    applyHardcore();
    // The world-integrity guard, installed before any challenge registers: the blocks no mode may ever
    // destroy (the End portal and its frames, bedrock, admin blocks) are decided once, here, for all.
    blockBreakService.guard(com.sexidium.core.game.experience.compose.BlockGuard.fromConfig(cfg()));
    dropPipeline.maxStackSize(Math.max(1, cfg().getInt("experiences.common.max-item-stack-size", 64)));
    // Install the unified HUD (owned by AbstractGame) before any challenge registers its HudContributor.
    long hudPeriod = HudCadence.ticks(cfg(), "experiences.common.hud-refresh-ticks");
    GameHud hud = installHud(LocalizedText.of(MessageKey.EXPERIENCE_HUD_TITLE), hudPeriod, null);
    ChallengeRegistry registry =
        new ChallengeRegistryImpl(this, dropPipeline, blockBreakService, damagePipeline, healthModel, hud);
    for (Challenge challenge : challenges) {
      activeBinding = bindings.get(challenge.id());
      challenge.register(registry);
    }
    activeBinding = null;
    for (Challenge challenge : challenges) {
      activeBinding = bindings.get(challenge.id());
      challenge.onStart(online());
    }
    activeBinding = null;
    // Unified HUD: a shared section (the active challenge NAMES + deaths + blocks broken) the same for
    // every challenge, then each challenge's own live section underneath. Listing the names — not just a
    // count — is the player's primary way to see which twists are active, since the in-world scoreboard
    // is the only feedback channel a mobile player reliably reads.
    //
    // Runs AFTER onStart, because whether the sidebar is wanted at all depends on whether a challenge
    // that owns the screen managed to open its own surface — and a challenge opens that in onStart.
    refreshActiveNames();
    for (PlayerAdapter player : online()) {
      hud.show(player);
    }
    // Per-tick driver for the shared model (mob scan window + merged health). The HUD repaints less
    // often (its own timer, started by installHud) — a scoreboard rebuild every tick would flicker.
    runTimer(this::composeTick, 1L, 1L);
    // Half-second dimension sampling: cheap, and it is what lets a Nether death respawn in the Nether.
    runTimer(this::trackDimensions, 10L, 10L);
    // Periodic player snapshot autosave so a hard crash loses at most one window of position/inventory.
    long autosave = Math.max(100L, cfg().getLong("experiences.common.autosave-ticks", 600L));
    runTimer(this::autosavePlayers, autosave, autosave);
    // One real second: how long this run has been PLAYED, and by whom. Kept on its own timer rather than
    // folded into an existing one because the period IS the unit — the ledger counts ticks of this timer
    // as seconds, so it must not share a cadence that another concern might want to change.
    runTimer(this::accrueOccupancy, 20L, 20L);
    // The standing guard over how players are allowed to be in this world (the spectator lock on a lost
    // hardcore run). Once a second: cheap, and it is what makes the rule true rather than merely applied.
    runTimer(this::enforceEntryPolicies, 20L, 20L);
    // Who is still driving their own character. Twice a second by default: the threshold is in seconds,
    // so anything finer buys nothing, and the loop is one adapter read per online player.
    long sampleTicks = readDownedSettings();
    if (downedProtection) {
      runTimer(this::sampleControl, 20L, sampleTicks);
    }
    // Open-ended mode: points come from time-in-world, not wins/kills.
    startExperiencePlaytimeAwards();
    challengesReady = true;
  }

  /** Per-tick driver for the host-owned shared model. */
  private void composeTick() {
    if (!isRunning()) {
      return;
    }
    mobRegistry.tick();
    healthModel.writeAll();
  }

  /**
   * Samples which dimension each living participant is in, so a death can return them to it. Sampled on a
   * short timer (and on every damage event) because once the respawn fires vanilla has already moved the
   * player out of the dimension they died in.
   */
  private void trackDimensions() {
    if (!isRunning() || persistenceSuspended) {
      return;
    }
    WorldAdapter world = world();
    for (PlayerAdapter player : online()) {
      persistence.recordDimension(player, world);
    }
  }

  private void autosavePlayers() {
    if (!isRunning() || persistenceSuspended || !persistence.persistentWorld()) {
      return;
    }
    for (PlayerAdapter player : online()) {
      persistence.capturePlayerState(player);
    }
  }

  /**
   * Accrues one second of played time, then commits the buffer to the shared state every so often.
   *
   * <p>Two things are deliberately separate here. <b>Accrual</b> happens every second regardless of what
   * else is going on, because time passes for the players whatever the server is doing — including in
   * the middle of a world regeneration, which they spend standing in the world they lost. <b>Commit</b>
   * is skipped while persistence is suspended, because the state being written to during a regeneration
   * belongs to a folder on its way out; the seconds simply wait in the ledger and land in the new world's
   * file once the swap has installed it.</p>
   *
   * <p>The commit window is also what bounds the file writes: a write per second, for the entire life of
   * an open-ended run, for a counter nobody reads that precisely, is not a trade worth making. A hard
   * crash loses at most one window — a graceful stop does not, because {@link #stop} drains first.</p>
   */
  private void accrueOccupancy() {
    if (!isRunning()) {
      return;
    }
    // online() is the participants actually present. An empty list pauses the clock, which is the whole
    // rule — an experience nobody is in is not being played, however long its world sits on disk.
    List<java.util.UUID> present = new ArrayList<>();
    for (PlayerAdapter player : online()) {
      present.add(player.uniqueId());
    }
    occupancy.tick(present);
    ticksSinceStatsCommit += 20L;
    if (ticksSinceStatsCommit < statsCommitTicks()) {
      return;
    }
    ticksSinceStatsCommit = 0L;
    commitOccupancy();
  }

  /**
   * Played time as it stands right now: what has been committed, plus what is still buffered.
   *
   * <p>Reads the buffer without draining it, so asking costs nothing and cannot lose a second to a
   * reader. The commit cadence is untouched — this exists precisely so it does not have to change.</p>
   */
  @Override
  public long liveRunSeconds() {
    return experienceStats.runSeconds() + occupancy.peekSeconds();
  }

  @Override
  public long liveRunSeconds(java.util.UUID playerId) {
    return experienceStats.runSeconds(playerId) + occupancy.peekSeconds(playerId);
  }

  /** Writes the buffered played time into the shared state, unless the state is mid-regeneration. */
  private void commitOccupancy() {
    if (persistenceSuspended || !persistence.persistentWorld() || !occupancy.pending()) {
      return;
    }
    experienceStats.addRunSeconds(occupancy.drainSeconds(), occupancy.drainPlayerSeconds());
  }

  /** How long (ticks) played time may sit in memory before it is written to the experience's state. */
  private long statsCommitTicks() {
    return Math.max(20L, cfg().getLong("experiences.common.stats-commit-ticks", 100L));
  }

  private void startParticipant(PlayerAdapter player) {
    addParticipant(player);
    enterClean(player);
    prepareEntry(player);
    awardParticipation(player);
    persistence.restorePlayerState(player);
    enforceLostWorldMode(player);
  }

  /**
   * Host-owned entry hygiene: a player entering an experience must not bring lobby items in. Cleared
   * BEFORE any saved inventory is restored, so a first entry arrives empty and a return entry is fully
   * overwritten by the saved snapshot. This is the single fix for the lobby-item leak — challenges
   * never manage entry inventory.
   */
  private void enterClean(PlayerAdapter player) {
    if (player != null && cfg().getBoolean("experiences.common.clear-inventory", true)) {
      player.clearInventory();
    }
  }

  /** Saved position used as the entry teleport target, retrieved BEFORE the player is teleported. */
  @Override
  public WorldPosition entrySpawn(PlayerAdapter playerAdapter, WorldAdapter worldAdapter) {
    // The one hook that runs BEFORE the entry teleport, on every path into the world — which is exactly
    // when the world's hardcore flag has to already be set, so nobody can be standing in an experience
    // whose difficulty and death rules have not caught up with what the owner chose. Cheap and
    // idempotent, so re-applying it on every entry is fine.
    applyHardcore();
    return persistence.entrySpawn(playerAdapter, worldAdapter, setup.worldType());
  }

  /**
   * Whether this world has already been LOST to a hardcore death: one death in a hardcore experience and
   * it can never be played again.
   *
   * <p>Answered from the cached flag rather than the registry, because this is asked on every entry path
   * and once a second by the watchdog, and a SQL round trip per player per second is not a thing to put
   * in a tick loop. The cache is refreshed from the registry at every point where the answer can have
   * changed under us ({@link #refreshLostWorld}) and set directly by the death that loses the world, so
   * it is never behind at a moment that matters.</p>
   */
  public boolean lost() {
    return hardcore.lost();
  }

  /**
   * Re-reads the lost flag from the registry — the source of truth across restarts and, on a shared
   * database, across servers. Never clears the in-memory flag: losing a hardcore world is one-way, and a
   * registry that is unavailable or has forgotten the row must not be able to revive one.
   */
  private void refreshLostWorld() {
    hardcore.refreshLost();
  }

  /**
   * How a player arrives in this experience, and how they stay: survival with hardcore hearts when the
   * experience is hardcore, and a locked SPECTATOR in a world that has been lost to a hardcore death —
   * it can be looked at and never played again.
   *
   * <p>Declaring it rather than applying it is the point. The framework applies this last, after the
   * saved snapshot has been restored, so the game mode a player had when they left cannot quietly hand
   * a dead world back to them; and because the spectator policy is {@link EntryPolicy#enforced()}, it
   * keeps being applied for as long as they stay, which no single-shot entry check can promise.</p>
   */
  @Override
  public EntryPolicy entryPolicy(PlayerAdapter playerAdapter) {
    return setup.entryPolicy(lost());
  }

  /** Applies this experience's arrival rules to a player the host is admitting itself. */
  private void prepareEntry(PlayerAdapter player) {
    entryPolicy(player).applyTo(player);
  }

  /** Re-asserts the arrival rules after saved state has been restored over them. */
  private void enforceLostWorldMode(PlayerAdapter player) {
    if (player != null) {
      entryPolicy(player).applyTo(player);
    }
  }

  /**
   * The standing guard behind the entry rules: once a second, every player inside is put back into the
   * mode this experience says they must be in, if something has moved them out of it.
   *
   * <p>Entry-time enforcement alone was not enough, and the bug it left is exactly the one worth a timer:
   * a player who died in a hardcore world could end up back in it in survival, because <em>something</em>
   * — a respawn, a plugin, an operator, a restored snapshot on a path nobody thought about — wrote a game
   * mode after the entry did. This does not care which. It only costs a comparison per player, and writes
   * nothing at all while the answer is already right.</p>
   */
  private void enforceEntryPolicies() {
    if (!isRunning()) {
      return;
    }
    // The registry can only ever say "lost" about a hardcore world, and only another server sharing the
    // database could say it before this one does — so it is polled rarely, and not at all for a world
    // that cannot be lost. Everything else here is in-memory.
    hardcore.pollLost(REGISTRY_POLL_PASSES);
    for (PlayerAdapter player : online()) {
      // Never touch a player who is on their own death screen: what happens next for them is the respawn,
      // and that is where they are dealt with.
      if (player.dead()) {
        continue;
      }
      // Told once per visit, not once per correction: if something keeps handing the mode back, the
      // player does not need to be told about the argument every second — they need it to stop, which
      // the correction itself does.
      if (entryPolicy(player).enforce(player) && lockExplained.add(player.uniqueId())) {
        gameContext().server().messages().send(player,
            LocalizedText.of(MessageKey.EXPERIENCE_HARDCORE_SPECTATING));
      }
    }
  }

  /**
   * Cancels a participant's gameplay event while their world is being replaced, and reports whether the
   * event was consumed.
   *
   * <p>Deliberately a whitelist of the things a player DOES, not a blanket "cancel everything". Movement
   * is left alone so a suppressed world does not feel frozen or rubber-band, and the respawn and death
   * events must keep flowing or the reset could never take anyone off a death screen. Damage is included
   * because the point is that nobody can die twice into the same reset.</p>
   */
  /**
   * Reads the downed-protection knobs once and clamps them, returning the sampling period in ticks.
   *
   * <p>The idle threshold has a ceiling that is not arbitrary: a proxy drops a silent client after its
   * own read timeout (Velocity's default is 30s), and a threshold anywhere near that turns this feature
   * back into the disconnect-only guard that was three seconds too late to save the world it was written
   * for. So a high value is clamped AND said out loud.</p>
   */
  private long readDownedSettings() {
    downedProtection = cfg().getBoolean("experiences.common.downed-protection", true);
    long idleSeconds = cfg().getLong("experiences.common.downed-idle-seconds", 8L);
    long clampedIdle = Math.min(300L, Math.max(3L, idleSeconds));
    if (clampedIdle != idleSeconds) {
      gameContext().server().logger().warning("[downed] downed-idle-seconds is " + idleSeconds
          + ", which is outside the usable range; using " + clampedIdle + ".");
    }
    if (clampedIdle >= 25L) {
      gameContext().server().logger().warning("[downed] downed-idle-seconds is " + clampedIdle
          + "s, which is close to a proxy's 30s read timeout: a dropping client will be disconnected at"
          + " about the same moment it is noticed, so the protection may arrive too late to matter.");
    }
    downedIdleMillis = clampedIdle * 1000L;
    long tailTicks = Math.min(200L, Math.max(0L, cfg().getLong("experiences.common.downed-recovery-tail-ticks", 40L)));
    downedTailMillis = tailTicks * 50L;
    downedDeaggroRadius = Math.min(128.0, Math.max(0.0, cfg().getDouble("experiences.common.downed-deaggro-radius", 32.0)));
    return Math.min(40L, Math.max(1L, cfg().getLong("experiences.common.downed-sample-ticks", 10L)));
  }

  /**
   * One pass over everyone still online, asking the platform how long since each of them last did
   * anything. Shaped after {@link #enforceEntryPolicies}: a standing guard is what makes a rule true
   * rather than merely applied once.
   */
  private void sampleControl() {
    if (!isRunning() || !downedProtection) {
      return;
    }
    PlayerControlWatch watch = gameContext().playerControl();
    for (PlayerAdapter player : List.copyOf(online())) {
      boolean justWentDown = watch.sample(player, downedIdleMillis);
      if (justWentDown) {
        gameContext().server().logger().info("[downed] " + player.name()
            + " has sent no input for " + (downedIdleMillis / 1000L) + "s and is being treated as not in"
            + " control: they cannot be hurt and mobs will lose interest until they act again.");
      }
      // Every pass, not only on the edge: a mob that wanders in, or re-acquires between the target event
      // and this sweep, would otherwise keep chewing on somebody who cannot run.
      if (justWentDown || watch.downed(player.uniqueId())) {
        deAggro(player);
      }
    }
  }

  /**
   * Makes every hostile within reach forget a player who cannot run away.
   *
   * <p>Behavioural polish, NOT the safety net — it cannot stop a primed creeper, an arrow already in the
   * air, or contact damage from a mob standing on top of them. {@link #shieldedWhileDowned} catches all
   * of those. This is what stops the pile-up that would otherwise be waiting when they come back.</p>
   */
  private void deAggro(PlayerAdapter player) {
    if (downedDeaggroRadius <= 0.0) {
      return;
    }
    WorldPosition at = player.position();
    if (at == null) {
      return;
    }
    UUID playerId = player.uniqueId();
    // Through the registry, not WorldAdapter.nearbyMobs directly, so this shares the per-tick scan cache
    // composeTick already pays for instead of walking the entity list again.
    for (MobHandle mob : mobRegistry.scan(at, downedDeaggroRadius, false)) {
      if (playerId.equals(mob.targetId())) {
        mob.clearTarget();
      }
    }
  }

  /**
   * A player nobody is driving cannot be hurt.
   *
   * <p>Cancelling the event is the whole mechanism, and deliberately so. The obvious alternative,
   * {@code setInvulnerable(true)}, is persistent NBT with no expiry that is only safe when paired with a
   * revoke scheduled at grant time — and this state has no known duration to schedule against, because
   * it ends when the player acts. There would be nothing guaranteed to clear the flag. Answering per hit
   * from a map instead means the worst a wrong entry can do is one extra sampling interval of
   * protection; it cannot follow anybody into the lobby, into a later match, or across a restart.</p>
   *
   * <p>A core cancel IS a native cancel: {@code PaperEventBridge.onDamage} runs at HIGHEST and writes
   * the answer back onto the Bukkit event, so this covers every cause — mob, fall, lava, drowning,
   * starvation, the void.</p>
   */
  private boolean shieldedWhileDowned(GameEvent gameEvent) {
    if (!downedProtection || !(gameEvent instanceof GameEvents.PlayerDamageGameEvent damage)) {
      return false;
    }
    PlayerAdapter victim = damage.victim();
    if (victim == null || !isParticipant(victim)
        || !gameContext().playerControl().downed(victim.uniqueId(), downedTailMillis)) {
      return false;
    }
    damage.setCancelled(true);
    return true;
  }

  private boolean suppressedDuringReset(GameEvent gameEvent) {
    PlayerAdapter actor = switch (gameEvent) {
      case GameEvents.BlockBreakGameEvent event -> event.playerAdapter();
      case GameEvents.BlockPlaceGameEvent event -> event.playerAdapter();
      case GameEvents.PlayerDropItemGameEvent event -> event.playerAdapter();
      case GameEvents.PlayerInteractGameEvent event -> event.playerAdapter();
      case GameEvents.PlayerDamageGameEvent event -> event.victim();
      // A jump during a reset must not fire Jump Multiplies: the sweep would spawn hundreds of entities
      // into a world that is seconds away from being deleted, and pay for every one of them.
      case GameEvents.PlayerJumpGameEvent event -> event.playerAdapter();
      default -> null;
    };
    if (actor == null || !isParticipant(actor)) {
      return false;
    }
    if (gameEvent instanceof GameEvents.AbstractCancellableGameEvent cancellable) {
      cancellable.setCancelled(true);
    }
    return true;
  }

  @Override
  public void handle(GameEvent gameEvent) {
    if (!isRunning() || !challengesReady) {
      return;
    }
    // A world being replaced is INERT for its participants: from the death that triggers the reset until
    // the swap lands, nothing can be broken, placed, dropped, hit or damaged in it. That is the mode's
    // contract — a death ends this world, so the seconds after it are not playable time.
    //
    // This is a rebuilt version of a suppression that was removed once, and the reason it was removed is
    // designed out rather than argued away. The old one was open-ended: a platform-level gate that could
    // be switched on and then fail to switch off, leaving a world nobody could ever mine again. This one
    // cannot outlive its cause, because it does not have a switch — it asks the reset whether it is
    // running, and the reset's `running` flag is cleared in a `finally` on every exit path there is
    // (success, abort, watchdog timeout, match end). If the reset dies in any way at all, suppression
    // ends with it. Bounded above by reset-timeout-ticks regardless.
    if (worldReset.running() && suppressedDuringReset(gameEvent)) {
      return;
    }
    // Evidence of life, taken before any guard below can act on a stale mark: these are all things the
    // player DID, so any one of them proves somebody is at the controls. Cheaper and far faster than
    // waiting for the next sampling pass.
    //
    // PlayerDamageGameEvent is pointedly NOT in this list. Being hit is something done TO a player, and
    // counting it as input would lift the protection at the exact moment it is needed.
    if (downedProtection) {
      PlayerAdapter actor = switch (gameEvent) {
        case GameEvents.BlockBreakGameEvent event -> event.playerAdapter();
        case GameEvents.BlockPlaceGameEvent event -> event.playerAdapter();
        case GameEvents.PlayerDropItemGameEvent event -> event.playerAdapter();
        case GameEvents.PlayerInteractGameEvent event -> event.playerAdapter();
        case GameEvents.PlayerJumpGameEvent event -> event.playerAdapter();
        default -> null;
      };
      if (actor != null) {
        gameContext().playerControl().sawInput(actor.uniqueId());
      }
    }
    if (shieldedWhileDowned(gameEvent)) {
      return;
    }
    // Death never ejects: a respawning participant is kept in the experience — and in the DIMENSION they
    // died in — in survival, with this match's overlays restored, instead of being released to the lobby.
    // A hardcore world ends at the DEATH, not at the respawn: a hardcore client's death screen offers to
    // spectate rather than to respawn, so a mode that waited for a respawn to notice the death could
    // never mark the world lost — and everything downstream of that (the spectator lock, the frozen
    // settings, the red tiles) never engaged either.
    if (gameEvent instanceof GameEvents.PlayerDeathGameEvent death && isParticipant(death.playerAdapter())) {
      // The last line of defence, and it should be unreachable: shieldedWhileDowned cancels the lethal
      // hit before it lands. The way here is the one-sample race where a fatal blow arrives in the window
      // before the sampler notices the silence. WARNING, with the real idle time, is how that window gets
      // tuned against reality rather than guessed at.
      //
      // Returning HERE is what gives the rule its whole reach: challenges are dispatched at the END of
      // this method, so DeathResetsChallenge.onPlayerDeath never runs, never bumps its counter and never
      // asks for a reset -- and handleHardcoreDeath is skipped, so a LOSE_WORLD mode is covered by the
      // same lines without either file knowing this exists.
      if (downedProtection
          && gameContext().playerControl().downed(death.playerAdapter().uniqueId(), downedTailMillis)) {
        PlayerAdapter victim = death.playerAdapter();
        gameContext().server().logger().warning("[downed] " + victim.name()
            + " died while nobody was in control of them (idle " + victim.idleMillis() + "ms, "
            + gameContext().playerControl().reason(victim.uniqueId())
            + "). The death does not count and this world is NOT being reset.");
        // Deferred by a tick, never inline: the server is still unwinding its own death handling, and
        // respawning from inside a death handler is what produces the "world never finishes loading,
        // cured only by relogging" symptom this codebase has already been bitten by twice.
        UUID victimId = victim.uniqueId();
        runLater(() -> gameContext().server().player(victimId)
            .filter(PlayerAdapter::online)
            .ifPresent(PlayerAdapter::forceRespawn), 1L);
        return;
      }
      // Counted HERE, at the death, and not in reviveParticipant beside the world-scoped tally. In a mode
      // where a death replaces the world, the respawn is deferred by a tick on purpose and the
      // regeneration can have snapshotted what it carries forward before then — so a run-scoped death
      // counted at the respawn is precisely the death the next world never hears about. Challenges are
      // dispatched at the END of this method, so the reset this may trigger has already seen it.
      experienceStats.recordRunDeath(death.playerAdapter());
      handleHardcoreDeath(death.playerAdapter());
    }
    if (gameEvent instanceof PlayerRespawnGameEvent respawn && isParticipant(respawn.playerAdapter())) {
      // In a LOST world this is not a revival: reviveParticipant applies the entry policy, which is
      // spectator once the world is lost, so the player comes back from their death screen as a
      // spectator of the world they lost — still in the match, still in the world, never bounced
      // through the lobby. The dimension redirect below still runs, so they spectate from where they
      // died rather than from the Overworld spawn vanilla defaults to.
      reviveParticipant(respawn.playerAdapter());
      // A world reset force-respawns the dead ON PURPOSE, to get them off a hardcore death
      // screen that has no Respawn button. It then decides where everybody goes. So the usual redirect —
      // and especially its next-tick backstop teleport — must not run here: it would put the player back
      // into a world the reset is about to delete, one tick after the reset moved them out of it.
      if (worldReset.running()) {
        restorePlayerUi(respawn.playerAdapter());
        return;
      }
      // A challenge may insist on where a death sends the player (Layered Dimensions sends you back to
      // the top of the Overworld column, whichever dimension killed you). The first with an opinion wins,
      // so two of them cannot fight over one respawn.
      WorldPosition challengeTarget = challengeRespawn(respawn.playerAdapter());
      WorldPosition target = challengeTarget != null ? challengeTarget : persistence.respawnPosition(
          respawn.playerAdapter(), world(), setup.worldType(), respawn.vanillaPosition());
      if (target != null) {
        // Redirect the respawn itself, so the platform places them there with no teleport flicker…
        respawn.setRespawnPosition(target);
        // …and re-assert next tick as the backstop for a platform that cannot honour the redirect.
        PlayerAdapter player = respawn.playerAdapter();
        WorldPosition landing = target;
        runLater(() -> {
          if (player.online()) {
            player.teleport(landing);
          }
        }, 1L);
      }
      restorePlayerUi(respawn.playerAdapter());
    }
    // Count every manual block break for the shared HUD stat, then run the shared loot pipeline and
    // suppress vanilla once if any drop contributor claimed the loot.
    if (gameEvent instanceof BlockBreakGameEvent breakEvent
        && isParticipant(breakEvent.playerAdapter())
        && breakEvent.blockKey() != null) {
      // Diagnostic: states definitively, at the moment a participant's break is processed, whether the
      // core is the thing cancelling it. If blocks "won't break" and this logs cancelled=false with
      // frozen=false and SURVIVAL, the core is NOT the cause and the hunt moves to the platform/client.
      if (debugEnabled()) {
        PlayerAdapter breaker = breakEvent.playerAdapter();
        gameContext.server().logger().info("[deathresets-diag] break by " + breaker.name()
            + " world=" + (breaker.world() == null ? "?" : breaker.world().name())
            + " gamemode=" + breaker.gameMode()
            + " cancelled=" + breakEvent.cancelled());
      }
      experienceStats.recordBlocksBroken(1L);
      if (!dropPipeline.isEmpty()
          && blockBreakService.onManualBreak(breakEvent.playerAdapter(), breakEvent.blockPosition(), breakEvent.blockKey())) {
        breakEvent.setDropItems(false);
      }
    }
    // Any hit is a chance the player is about to die, so sample their dimension now — this is the sample
    // that matters, since the periodic one may be up to half a second stale.
    if (gameEvent instanceof PlayerDamageGameEvent damageEvent && isParticipant(damageEvent.victim())) {
      persistence.recordDimension(damageEvent.victim(), world());
    }
    // Shared damage pipeline: resolve one hit through the ordered contributors, cancel the native hit
    // once when absorbed, and write merged health for the victim. No-op while no challenge contributes.
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
    // PC HUD toggle: a double-tap of the sneak key flips the HUD view (handled by AbstractGame).
    routeHudGesture(gameEvent);
    for (Challenge challenge : challenges) {
      challenge.onEvent(gameEvent);
    }
  }

  /** Whether the global {@code debug} flag is on — gates the diagnostic block-break log below. */
  private boolean debugEnabled() {
    return cfg().getBoolean("debug", false);
  }


  @Override
  public void stop(LocalizedText reason) {
    markEnded();
    challengesReady = false;
    // A match can end at any moment, including in the middle of a world reset (an operator stopping the
    // server, the reset itself failing). Abandoning releases the persistence suspension and completes any
    // outstanding old-world teardown, so an ended match can neither leave state-writing switched off nor
    // leak the folder it was about to remove.
    worldReset.abandon();
    for (Challenge challenge : challenges) {
      try {
        challenge.onStop();
      } catch (Exception exception) {
        gameContext.server().logger().warning("Error stopping challenge " + challenge.id(), exception);
      }
    }
    // Whatever played time is still buffered belongs in the state, and this is the last chance to put it
    // there: cleanup() below cancels the timer that would have committed it. Ordered before the flush so
    // the seconds are part of what gets written, not the next thing to be dropped.
    commitOccupancy();
    // Final synchronous flush so the latest shared challenge state is persisted before the match ends
    // (the debounced task is a tracked timer that cleanup() would otherwise cancel unfired).
    persistence.flushState();
    // Capture every player BEFORE releaseAndReset, which goes on to resetStatuses() and therefore
    // clears the live inventory (AbstractGame:446 -> PlayerAdapter.resetStatuses:335).
    //
    // The per-player leave path already does this via onParticipantLeaving; stop() never did. So a
    // match ending — an operator stopping the server, a rolling update, a time limit — wrote whatever
    // the last autosave had, and that cadence is experiences.common.autosave-ticks, default 600 ticks
    // = THIRTY SECONDS. Everything a player did in that window was cleared, not saved. It is the
    // single biggest player-visible cost of any restart, and it is now zero.
    //
    // Same guard as onParticipantLeaving:1037: clear ONLY when the snapshot was actually saved. A
    // transient experience has no persistent folder and saves nothing, and clearing there would
    // destroy the items instead of parking them.
    for (PlayerAdapter player : online()) {
      if (persistence.capturePlayerState(player) && player != null) {
        player.clearInventory();
      }
    }
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
    // Mid-reset there is no world to put them in — the old one is being deleted and the new one may not
    // exist yet. Refuse rather than teleport somebody into nothing; they can try again in a moment.
    if (worldReset.running()) {
      gameContext().server().messages().send(player,
          LocalizedText.of(MessageKey.EXPERIENCE_RESET_BUSY));
      return;
    }
    addParticipant(player);
    // Ask the registry again before deciding how they arrive: this player may be walking into a world
    // that somebody else lost while they were away.
    refreshLostWorld();
    enterClean(player);
    prepareEntry(player);
    persistence.restorePlayerState(player);
    enforceLostWorldMode(player);
    for (Challenge challenge : challenges) {
      challenge.onPlayerJoin(player);
    }
    hud().show(player);
    announce(MessageKey.EXPERIENCE_JOINED, MessageArg.text("player", player.name()));
  }

  @Override
  public void onParticipantRejoin(PlayerAdapter playerAdapter) {
    // Reconnecting into a (rehydrated) experience: re-enter from the .yml snapshot — clear, restore
    // inventory/health, teleport to the saved spot, rejoin the challenges and show the HUD. We do NOT
    // call super (which would re-apply the match snapshot); the experience's own files are authoritative.
    if (playerAdapter == null) {
      return;
    }
    markReconnected(playerAdapter.uniqueId());
    if (!isParticipant(playerAdapter)) {
      addParticipant(playerAdapter);
    }
    refreshLostWorld();
    enterClean(playerAdapter);
    PlayerSnapshot snapshot = persistence.loadSnapshot(playerAdapter);
    if (snapshot != null) {
      snapshot.applyTo(playerAdapter, gameContext.server().inventorySerializer());
    } else {
      prepareEntry(playerAdapter);
    }
    enforceLostWorldMode(playerAdapter);
    // Single, validated entry teleport: the saved spot when it belongs to this experience world, else the
    // world's SAFE spawn. Never force foreign (e.g. lobby) coordinates into this world, and never strand a
    // null-snapshot reconnect at whatever world the client happened to log into.
    WorldPosition target = persistence.resolveEntryPosition(snapshot, world(), setup.worldType());
    if (target != null) {
      playerAdapter.teleport(target);
    }
    for (Challenge challenge : challenges) {
      challenge.onPlayerJoin(playerAdapter);
    }
    hud().show(playerAdapter);
  }

  @Override
  public void onParticipantDisconnect(PlayerAdapter playerAdapter) {
    // Save the exact spot + items so a disconnect (or the match later ending) returns the player here.
    // Not during a reset: the folder being written to is on its way out, and the coordinates would
    // describe terrain that will not exist when the player comes back.
    if (persistenceSuspended) {
      return;
    }
    persistence.capturePlayerState(playerAdapter);
  }

  @Override
  protected void onParticipantLeaving(java.util.UUID playerId, PlayerAdapter player, boolean voluntary) {
    // Capture before release-to-lobby teleports them away: leaving an experience saves where they were
    // and their items. Clear the live inventory ONLY when the snapshot was actually saved, so the
    // experience items don't reach the lobby (they return on the next visit). For a transient (no
    // persistent folder) experience nothing is saved, so we must NOT clear — that would destroy items.
    if (persistence.capturePlayerState(player) && player != null) {
      player.clearInventory();
    }
    // Clear any paused health governance so a player who quit mid-death does not leave a stale entry.
    healthModel.resume(playerId);
    persistence.forgetDimension(playerId);
    lockExplained.remove(playerId);
    for (Challenge challenge : challenges) {
      try {
        challenge.onPlayerLeave(player);
      } catch (Exception exception) {
        gameContext.server().logger().warning("Error removing player from challenge " + challenge.id(), exception);
      }
    }
    hud().hide(player);
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
    // Never auto-remove on a world change — the player stays in the experience until they /leave.
    return isParticipant(playerAdapter);
  }

  // ----- ExperienceHost ------------------------------------------------------------------------

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

  // The track/runTimer overrides also attribute the resource to the challenge currently being wired
  // (activeBinding), so a single live-removed challenge's timers/bars are cancelled without ending the
  // match. They are still tracked by AbstractGame too, so match-end cleanup remains the backstop
  // (cancel/close are idempotent).
  @Override
  public BossBarHandle track(BossBarHandle bossBarHandle) {
    BossBarHandle handle = super.track(bossBarHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.bars.add(handle);
    }
    return handle;
  }

  @Override
  public com.sexidium.core.platform.HudPanelHandle track(com.sexidium.core.platform.HudPanelHandle hudPanelHandle) {
    com.sexidium.core.platform.HudPanelHandle handle = super.track(hudPanelHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.panels.add(handle);
    }
    return handle;
  }

  @Override
  public com.sexidium.core.platform.HudSurfaceHandle track(
      com.sexidium.core.platform.HudSurfaceHandle hudSurfaceHandle) {
    com.sexidium.core.platform.HudSurfaceHandle handle = super.track(hudSurfaceHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.surfaces.add(handle);
    }
    return handle;
  }

  @Override
  public com.sexidium.core.platform.TabListHandle track(
      com.sexidium.core.platform.TabListHandle tabListHandle) {
    com.sexidium.core.platform.TabListHandle handle = super.track(tabListHandle);
    if (activeBinding != null && handle != null) {
      activeBinding.tabLists.add(handle);
    }
    return handle;
  }

  @Override
  public com.sexidium.core.platform.ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    com.sexidium.core.platform.ScheduledTask task = super.runTimer(runnable, delayTicks, periodTicks);
    if (activeBinding != null && task != null) {
      activeBinding.tasks.add(task);
    }
    return task;
  }

  @Override
  public com.sexidium.core.platform.ScheduledTask runLater(Runnable runnable, long delayTicks) {
    com.sexidium.core.platform.ScheduledTask task = super.runLater(runnable, delayTicks);
    if (activeBinding != null && task != null) {
      activeBinding.tasks.add(task);
    }
    return task;
  }

  // ----- live add/remove of a single challenge (used by the editor + chaos cycle) --------------

  /** Records a teardown hook on the binding currently being wired (no-op when nothing is active). */
  void recordUndo(Runnable undo) {
    if (activeBinding != null && undo != null) {
      activeBinding.undo.add(undo);
    }
  }

  /** Removes a published capability on teardown (only if the same instance is still registered). */
  void unpublish(Class<?> type, Object implementation) {
    if (type != null) {
      services.remove(type, implementation);
    }
  }

  /**
   * Wires a new challenge into the already-running experience: attach → register → onStart with the
   * current participants. Returns false when the id is unknown or already active. Mirrors the
   * per-challenge slice of {@link #initChallenges}.
   */
  public boolean addChallengeLive(String id) {
    if (!challengesReady) {
      return false;
    }
    List<Challenge> created = ChallengeCatalog.create(List.of(id == null ? "" : id));
    if (created.isEmpty()) {
      return false;
    }
    Challenge challenge = created.get(0);
    if (challenge(challenge.id()).isPresent()) {
      return false;
    }
    ChallengeBinding binding = new ChallengeBinding(challenge);
    bindings.put(challenge.id(), binding);
    challenges.add(challenge);
    challenge.attach(this);
    ChallengeRegistry registry =
        new ChallengeRegistryImpl(this, dropPipeline, blockBreakService, damagePipeline, healthModel, hud());
    activeBinding = binding;
    try {
      challenge.register(registry);
      challenge.onStart(online());
    } finally {
      activeBinding = null;
    }
    refreshActiveNames();
    hud().render();
    return true;
  }

  /**
   * Removes a running challenge: onStop → cancel its timers/bars/panels → unregister its pipeline
   * contributions → reset each online player's per-player client state. Returns false when not active.
   */
  public boolean removeChallengeLive(String id) {
    Challenge challenge = challenge(id).orElse(null);
    if (challenge == null) {
      return false;
    }
    challenges.remove(challenge);
    try {
      challenge.onStop();
    } catch (Exception exception) {
      gameContext.server().logger().warning("Error stopping challenge " + challenge.id(), exception);
    }
    ChallengeBinding binding = bindings.remove(challenge.id());
    if (binding != null) {
      binding.teardown();
    }
    for (PlayerAdapter player : online()) {
      try {
        challenge.resetPlayer(player);
      } catch (Exception exception) {
        gameContext.server().logger().warning("Error resetting player for challenge " + challenge.id(), exception);
      }
    }
    refreshActiveNames();
    hud().render();
    return true;
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
    return persistence.state();
  }

  @Override
  public void killParticipant(PlayerAdapter playerAdapter) {
    if (playerAdapter == null || !playerAdapter.online()) {
      return;
    }
    // Real death: pause the per-tick merged health governance immediately (so the HealthModel does not
    // heal the player back before the death registers) then deal the lethal blow on the next tick, once
    // the current damage handling has fully unwound. setHealth(0) fires the vanilla PlayerDeathEvent —
    // the death animation plays and the death screen appears — and keepInventory on the experience world
    // keeps the player's items + XP. The respawn flow (softRespawn) re-seats them and resumes governance.
    java.util.UUID id = playerAdapter.uniqueId();
    healthModel.suspend(id);
    runLater(() -> gameContext.server().player(id)
        .filter(PlayerAdapter::online)
        .ifPresent(player -> player.setHealth(0.0)), 1L);
  }

  /**
   * A challenge soft-reset ("death" without a real death): revive the player and put them at the spawn of
   * the dimension they are in. A REAL death does not come through here — it redirects the respawn itself
   * (see {@link #handle}) so the platform never places the player in the wrong dimension to begin with.
   */
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
    // No platform placement to respect on a soft reset, so this always resolves to a dimension spawn —
    // the one the player is in, never the Overworld by default.
    WorldPosition target = persistence.respawnPosition(playerAdapter, world, setup.worldType(), null);
    if (target != null) {
      playerAdapter.teleport(target);
    }
  }

  /**
   * The shared "you are alive again" state, applied by both a real respawn and a challenge soft-reset:
   * resume the merged-health governance that was paused while a real death registered
   * ({@code killParticipant}), count the death for the shared HUD stat, and restore health/food/survival.
   */
  /** The first challenge with an opinion on where a death should land, or null when none has one. */
  private WorldPosition challengeRespawn(PlayerAdapter playerAdapter) {
    for (Challenge challenge : challenges) {
      WorldPosition target = challenge.respawnPosition(playerAdapter);
      if (target != null) {
        return target;
      }
    }
    return null;
  }

  private void reviveParticipant(PlayerAdapter playerAdapter) {
    healthModel.resume(playerAdapter.uniqueId());
    experienceStats.recordDeath(playerAdapter);
    // Alive again on this experience's terms, not on hardcoded ones. It used to end with a plain
    // setGameMode(SURVIVAL), which is a mode putting a player back into a world it has no business
    // deciding is playable — in a lost hardcore world that is the whole bug, handed over by the one path
    // that runs after every death.
    entryPolicy(playerAdapter).applyTo(playerAdapter);
  }

  // ----- ChallengeContext: sibling discovery + capability registry + pipelines -----------------

  @Override
  public List<Challenge> challenges() {
    return List.copyOf(challenges);
  }

  @Override
  public Optional<Challenge> challenge(String id) {
    if (id == null) {
      return Optional.empty();
    }
    for (Challenge challenge : challenges) {
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
    for (Challenge challenge : challenges) {
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

  /** Comma-joined challenge names for chat (the start announce / log line), where a long line is fine. */
  private String challengeSummary() {
    if (challenges.isEmpty()) {
      return "—";
    }
    StringBuilder builder = new StringBuilder();
    for (Challenge challenge : challenges) {
      if (builder.length() > 0) {
        builder.append(", ");
      }
      builder.append(challenge.displayName());
    }
    return builder.toString();
  }

  /**
   * A challenge's own on-screen surface came up or went away, so decide again whether the sidebar is
   * needed. Cheap and idempotent — the reporting challenge is free to call it from a tick.
   */
  @Override
  public void hudSurfaceChanged() {
    refreshActiveNames();
  }

  /**
   * (Re)installs the HUD shared section from the LIVE challenge list, so adding/removing a challenge at
   * runtime updates the "Active (N)" list. One short count followed by one name per line (a single
   * comma-joined string overflowed the scoreboard width once several twists stacked).
   */
  private void refreshActiveNames() {
    // A challenge that owns the screen (Death Resets → BetterHud overlay) has the sidebar suppressed for
    // the players its surface reaches.
    //
    // Both halves of the test matter. ownsHud() is the claim; hudSurfaceActive(player) is whether the
    // claim was honoured for THAT player. The corner overlay is not a surface every viewer has — no
    // BetterHud, no layout installed, or a Bedrock client — and suppressing on the claim alone left
    // those players staring at nothing at all. Per player rather than per match because the answer
    // genuinely differs between two people standing next to each other.
    //
    // The predicate closes over the live challenge list, so it re-decides on every render pass. Re-run
    // whenever the live editor adds or removes a twist, and whenever an owning challenge reports its
    // surface came up or went away.
    hud().suppressPanel(player ->
        challenges.stream().anyMatch(challenge -> challenge.ownsHud() && challenge.hudSurfaceActive(player)));
    // Installed unconditionally: it costs nothing for a suppressed player (their panel is never built)
    // and the un-suppressed viewer beside them still needs a header.
    List<String> activeNames = challengeNames();
    hud().sharedSection(context -> {
      context.stat("Active", activeNames.size());
      for (String name : activeNames) {
        context.line("<dark_gray>·</dark_gray> <white>" + name + "</white>");
      }
      context.stat("Deaths", experienceStats.deaths(context.player()));
      context.stat("Blocks broken", experienceStats.blocksBroken());
      context.line("<dark_gray>───────</dark_gray>");
    });
  }

  /** The active challenge display names, for the HUD's one-per-line "Active" list. */
  private List<String> challengeNames() {
    List<String> names = new ArrayList<>(challenges.size());
    for (Challenge challenge : challenges) {
      names.add(challenge.displayName());
    }
    return names;
  }
}
