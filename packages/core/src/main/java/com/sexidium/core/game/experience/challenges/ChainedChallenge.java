package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Chained Together" challenge.
 *
 * <p>Rule: the whole team is roped into a single physical chain — players {@code 0-1-2-…-(n-1)} in join
 * order, each neighbour pair joined by a short rope rendered as a sagging line of particles between them.
 * The rope is reeled in by a per-segment spring so the players stay close, and a group <em>consensus
 * drag</em> means that when most of the team walks the same way their strength adds and drags the
 * stragglers along, stretching the chain in that direction. A segment that over-stretches snaps the
 * lagging neighbour back. A player who arrives late is teleported straight to the experience's host (the
 * owner) and clipped onto the end of the chain — roped to the last player who joined, with the rope and
 * the physics applied the instant they appear.
 *
 * <p>A rope only ever joins two real players — there is no spawn stake or invisible anchor entity, so a
 * lone player (or the last one left) roams free until a second player is present. By default a chained
 * player who dies respawns right next to the player they are roped to (their chain neighbour), keeping
 * the chain together; the opt-in {@code death-link} rule instead revives the whole team in place so
 * nobody ever respawns.
 *
 * <p>The leash physics is pure (see {@link ChainSolver}); this class owns the I/O — reading positions,
 * applying velocity/teleports, and driving the chain tick. Cohesive pieces are split out: the live HUD
 * readout into {@link ChainHud}, the rope particle rendering into {@link ChainRopeRenderer}, the
 * persistent member roster into {@link ChainMemberRoster}, and the death-link damage contributor into
 * {@link ChainDeathLink}.</p>
 */
public final class ChainedChallenge extends Challenge {

  // Hard ceiling on the configured link length: a value above this would push the snap range past the
  // vanilla leash distance the spring is tuned around. Capped here.
  private static final double MAX_SEGMENT_LENGTH = 8.0;
  // Keep segmentLength × hard-snap-multiplier under this so an over-stretch snaps before it gets silly.
  private static final double SNAP_SAFE_DIST = 10.0;
  // Below this per-tick shove magnitude (blocks/tick) the nudge is imperceptible; skip it so a near-zero
  // velocity packet never jitters the client (camera-shake dead-zone).
  private static final double MIN_SHOVE = 0.012;

  private double segmentLength;
  private double pullStrength;
  private double damping;
  private double dragStrength;
  private double maxPull;
  private double moveEpsilon;
  private double hardSnapMultiplier;
  private long checkTicks;
  private boolean visibleRope;
  private boolean useLeashRope;
  private boolean deathLink;
  private boolean playSounds;

  // Chain order in join sequence (the rope topology). Offline-but-still-participant members keep their
  // slot so a reconnect rejoins at the same link instead of being yanked to the host.
  private final List<UUID> chainOrder = new ArrayList<>();
  // Last horizontal position per player, to derive the per-tick move vector that feeds the consensus.
  private final Map<UUID, double[]> lastPos = new HashMap<>();

  // Live HUD physics readouts (fed by the tick), the rope particle renderer, and the persistent member roster.
  private final ChainHud hud = new ChainHud(this::onlineChainSize, () -> segmentLength);
  private final ChainRopeRenderer rope =
      new ChainRopeRenderer(this::world, () -> visibleRope, () -> segmentLength);
  // The native-lead alternative to the particle rope (a leashed marker per link). Chosen at render time
  // when rope-style: leash AND the platform can render leads; falls back to the particle rope otherwise.
  private final ChainLeashRenderer leashRope = new ChainLeashRenderer(this::world, () -> visibleRope);
  private final ChainMemberRoster roster =
      new ChainMemberRoster(def -> stateString("members", def), value -> setStateString("members", value));

  public ChainedChallenge() {
    super("chained", "Chained Together");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.damageContributor(new ChainDeathLink(this));
    registry.hud(this::describeHud);
  }

  /**
   * The chain's HUD: the live readout ({@link ChainHud}, itself debug-gated for the physics lines)
   * followed by the chain's tuning snapshot when {@code debug} is on, so an operator can see the
   * solver inputs (segment length, spring/drag strengths, rope style, death-link) without reading the
   * config — the values that explain why the chain behaves the way it does this run.
   */
  private void describeHud(com.sexidium.core.game.hud.HudContext context) {
    hud.describeHud(context);
    if (!context.debug()) {
      return;
    }
    context.debugHeader("Chained Together");
    context.debugStat("Members", chainOrder.size() + " (" + onlineChainSize() + " online)");
    context.debugStat("Segment", String.format(java.util.Locale.ROOT, "%.1fm", segmentLength));
    context.debugStat("Pull/damp", String.format(java.util.Locale.ROOT, "%.2f/%.2f", pullStrength, damping));
    context.debugStat("Drag/maxPull", String.format(java.util.Locale.ROOT, "%.2f/%.2f", dragStrength, maxPull));
    context.debugStat("Snap mult", String.format(java.util.Locale.ROOT, "%.2fx", hardSnapMultiplier));
    context.debugStat("Rope", useLeashRope ? "leash" : "particle");
    context.debugStat("Death-link", deathLink);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // Cap the link length so the snap range below can stay sane.
    segmentLength = Math.min(MAX_SEGMENT_LENGTH, Math.max(1.5, cfg().getDouble(configPath("segment-length"), 5.0)));
    pullStrength = cfg().getDouble(configPath("pull-strength"), 0.20);
    damping = cfg().getDouble(configPath("damping"), 0.6);
    dragStrength = cfg().getDouble(configPath("drag-strength"), 0.12);
    maxPull = Math.max(0.0, cfg().getDouble(configPath("max-pull"), 0.5));
    moveEpsilon = Math.max(0.0, cfg().getDouble(configPath("move-epsilon"), 0.02));
    // Because segmentLength is capped at MAX_SEGMENT_LENGTH (8), the ceiling (10/8 = 1.25) always clears
    // the 1.1 lower bound, so the safety cap is never defeated by the floor.
    double rawMultiplier = cfg().getDouble(configPath("hard-snap-multiplier"), 1.7);
    double snapCeiling = SNAP_SAFE_DIST / Math.max(segmentLength, 1.0e-6);
    hardSnapMultiplier = Math.max(1.1, Math.min(rawMultiplier, snapCeiling));
    checkTicks = Math.max(1L, cfg().getLong(configPath("check-ticks"), 1));
    visibleRope = cfg().getBoolean(configPath("visible-rope"), true);
    // "leash" = native Minecraft lead (a leashed invisible marker per link, no view-blocking particles);
    // "particle" = the legacy sagging dust line. Defaults to leash; an unported platform falls back to
    // the particle rope at render time (see renderRope) regardless of this setting.
    useLeashRope = "leash".equalsIgnoreCase(cfg().getString(configPath("rope-style"), "leash"));
    // Default off: a dead chained player respawns next to the player they are roped to (handled in
    // onEvent). When enabled instead, an otherwise-fatal hit revives the whole team in place.
    deathLink = cfg().getBoolean(configPath("death-link"), false);
    playSounds = cfg().getBoolean(configPath("play-sounds"), true);
    if (participants != null) {
      for (PlayerAdapter participant : participants) {
        if (participant != null && !chainOrder.contains(participant.uniqueId())) {
          chainOrder.add(participant.uniqueId());
          roster.rememberMember(participant.uniqueId());
        }
      }
    }
    // Clear any leftover markers up front (an old build, or a previous run that crashed without teardown)
    // before this run starts spawning its own. Runs in BOTH modes — the leash renderer owns its markers
    // only after this point.
    WorldAdapter startWorld = world();
    if (startWorld != null) {
      startWorld.removeRopeMarkers();
    }
    runTimer(this::chainTick, checkTicks, checkTicks);
    // Safety net for the PARTICLE mode only: a leftover marker can re-leash to a player when its holder
    // reconnects, so keep sweeping periodically (cheap). In leash mode our own markers carry the same tag,
    // so the sweep would delete the live rope — it is suppressed there (the renderer manages its markers).
    runTimer(this::sweepRopeMarkers, 100L, 100L);
  }

  /** Removes any leftover invisible-marker entity an earlier leash-based build left in this world. No-op in
   * leash mode, where the live rope markers carry the same tag and the renderer owns their lifecycle. */
  private void sweepRopeMarkers() {
    if (useLeashRope) {
      return;
    }
    WorldAdapter world = world();
    if (world != null) {
      world.removeRopeMarkers();
    }
  }

  @Override
  public void onStop() {
    chainOrder.clear();
    lastPos.clear();
    hud.clear();
    // Pull every leashed marker so no invisible roped entity is left behind in the world.
    leashRope.clear();
  }

  /**
   * A chained player who actually respawns (a death the team's death-link did not absorb) reappears next
   * to the player they are tied to — their chain neighbour — instead of at the world spawn, so the chain
   * stays together. The host's respawn has already run by the time this fires, so this teleport wins.
   */
  @Override
  public void onPlayerRespawn(PlayerRespawnGameEvent respawn) {
    PlayerAdapter player = respawn.playerAdapter();
    if (player == null || !isParticipant(player)) {
      return;
    }
    // The dying player's death unleashed the rope marker they were holding (the lead drop is suppressed at
    // the platform). The leash renderer re-leashes a detached marker every tick (see driveRopeMarker), so
    // the rope self-heals onto the respawned player here — no explicit rebuild needed.
    PlayerAdapter neighbour = chainNeighbour(player.uniqueId());
    WorldPosition to = neighbour == null ? null : neighbour.position();
    if (to == null) {
      return;
    }
    WorldPosition here = player.position();
    WorldPosition dest = here == null
        ? to
        : new WorldPosition(to.worldName(), to.coordinateX(), to.coordinateY(), to.coordinateZ(),
            here.yaw(), here.pitch());
    player.teleport(dest);
    lastPos.put(player.uniqueId(), new double[] {dest.coordinateX(), dest.coordinateZ()});
  }

  /**
   * The nearest online chain neighbour of a player — the one they are roped to. Scans outward in both
   * directions skipping offline/positionless members, because {@link #chainTick} collapses those gaps
   * too (so the player's real physical neighbour may be several join-order slots away).
   */
  private PlayerAdapter chainNeighbour(UUID id) {
    int index = chainOrder.indexOf(id);
    if (index < 0 || gameContext() == null) {
      return null;
    }
    for (int distance = 1; distance < chainOrder.size(); distance++) {
      for (int dir : new int[] {-1, 1}) {
        int j = index + dir * distance;
        if (j < 0 || j >= chainOrder.size()) {
          continue;
        }
        PlayerAdapter neighbour = gameContext().server().player(chainOrder.get(j)).orElse(null);
        if (neighbour != null && neighbour.online() && neighbour.position() != null) {
          return neighbour;
        }
      }
    }
    return null;
  }

  /**
   * A player joined the running experience. A genuine late arrival (the chain is already populated and
   * this player is new to it) is teleported to the host — the experience owner if online, otherwise the
   * current tail of the chain — appended last, and immediately roped to the previous tail with their
   * physics seeded, so the rope and the calculations appear the same moment the player does. A
   * reconnecting member already known keeps their slot and is not moved.
   */
  @Override
  public void onPlayerJoin(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    // A leftover marker re-leashes to this player as they connect; sweep it the moment they arrive.
    sweepRopeMarkers();
    UUID id = player.uniqueId();
    if (chainOrder.contains(id)) {
      return;
    }
    boolean lateArrival = !chainOrder.isEmpty() && !roster.knownMember(id);
    PlayerAdapter tail = lastOnlineMember(id);
    roster.rememberMember(id);
    // The position the player will end up at after this join. For a late arrival that is the teleport
    // TARGET, captured here — NOT re-read via position() afterwards, because Paper's teleport is async and
    // position() would still report the pre-teleport spot (seeding a fake cross-world "walk" next tick and
    // drawing the rope to empty space).
    WorldPosition landing = player.position();
    if (lateArrival) {
      WorldPosition target = hostPosition();
      if (target == null && tail != null) {
        target = tail.position();
      }
      if (target != null) {
        WorldPosition here = player.position();
        WorldPosition dest = here == null
            ? target
            : new WorldPosition(target.worldName(), target.coordinateX(), target.coordinateY(),
                target.coordinateZ(), here.yaw(), here.pitch());
        player.teleport(dest);
        landing = dest;
        if (playSounds) {
          player.playSound(new SoundKey("minecraft:block.chain.place"), 0.8f, 1.0f);
        }
      }
    }
    chainOrder.add(id);
    // Seed the move vector from the landing position so the first physics tick treats the new player as
    // standing still — chained immediately, no fake "walk" jolt to the group.
    if (landing != null) {
      lastPos.put(id, new double[] {landing.coordinateX(), landing.coordinateZ()});
      // Draw the new link right now so the particle rope appears the same moment the player does. The
      // leash rope needs no priming here — the next chainTick spawns its marker for the new link.
      if (!useLeashRope && tail != null && tail.position() != null) {
        rope.drawRopeBetween(tail.position(), landing);
      }
    }
  }

  @Override
  public void onPlayerLeave(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    UUID id = player.uniqueId();
    chainOrder.remove(id);
    lastPos.remove(id);
    hud.forget(id);
    // A voluntary leave drops the player from the roster, so if they later rejoin a populated chain they
    // are correctly treated as a late arrival (teleported to the host) rather than a silent reconnect.
    roster.pruneMember(id);
  }

  private void chainTick() {
    // Resolve the live online participants once, then build the chain in join order (appending any
    // online participant the join hook missed) keeping only those with a known position.
    Map<UUID, PlayerAdapter> onlineById = new HashMap<>();
    for (PlayerAdapter player : online()) {
      if (player != null) {
        onlineById.put(player.uniqueId(), player);
      }
    }
    for (UUID id : onlineById.keySet()) {
      if (!chainOrder.contains(id)) {
        chainOrder.add(id);
      }
    }
    List<PlayerAdapter> group = new ArrayList<>();
    for (UUID id : chainOrder) {
      PlayerAdapter player = onlineById.get(id);
      if (player != null && player.position() != null) {
        group.add(player);
      }
    }

    int n = group.size();
    double[] x = new double[n];
    double[] z = new double[n];
    double[] moveX = new double[n];
    double[] moveZ = new double[n];
    for (int i = 0; i < n; i++) {
      PlayerAdapter player = group.get(i);
      WorldPosition position = player.position();
      x[i] = position.coordinateX();
      z[i] = position.coordinateZ();
      double[] previous = lastPos.get(player.uniqueId());
      if (previous != null) {
        moveX[i] = x[i] - previous[0];
        moveZ[i] = z[i] - previous[1];
      }
      lastPos.put(player.uniqueId(), new double[] {x[i], z[i]});
    }
    lastPos.keySet().retainAll(onlineById.keySet());

    if (n == 0) {
      hud.clear();
      leashRope.clear();
      return;
    }
    if (n == 1) {
      // A lone player (or the last one left) is tied to NOTHING — no spawn stake, no anchor entity, no
      // rope. They move freely until a second player is present and the player-to-player chain takes over.
      hud.clear();
      leashRope.clear();
      return;
    }

    ChainSolver.Config config = new ChainSolver.Config(
        segmentLength, pullStrength, damping, dragStrength, maxPull, moveEpsilon, hardSnapMultiplier);
    ChainSolver.Step step = ChainSolver.solve(x, z, moveX, moveZ, config);
    hud.setConsensus(step.consensusStrength);
    for (int i = 0; i < n; i++) {
      PlayerAdapter player = group.get(i);
      UUID id = player.uniqueId();
      if (step.snapTo[i] >= 0) {
        PlayerAdapter anchor = group.get(step.snapTo[i]);
        WorldPosition to = anchor.position();
        WorldPosition from = player.position();
        if (to != null && from != null) {
          // Glide to the rope's EDGE (a segment-length out from the neighbour along the current
          // direction), not on top of them — the smallest correction that brings the link back in range,
          // so the camera barely moves instead of a full jump-to-neighbour teleport.
          double dx = from.coordinateX() - to.coordinateX();
          double dz = from.coordinateZ() - to.coordinateZ();
          double d = Math.hypot(dx, dz);
          double edge = segmentLength * 0.9;
          double tx = d > 1.0e-6 ? to.coordinateX() + dx / d * edge : to.coordinateX();
          double tz = d > 1.0e-6 ? to.coordinateZ() + dz / d * edge : to.coordinateZ();
          // Resolve a SAFE landing Y at the edge column instead of reusing the neighbour's Y: the offset
          // point sits ~a segment away horizontally, so on any slope/hill the neighbour's height there is
          // inside the terrain — teleporting to it buries the player in a wall and they suffocate, and the
          // over-stretch snap re-fires every tick (constant damage, camera inside a block). Drop onto the
          // actual surface of the edge column; fall back to the neighbour's Y only when no surface is known.
          double ty = surfaceColumnY(tx, tz, to.coordinateY());
          player.teleport(new WorldPosition(from.worldName(), tx, ty, tz,
              from.yaw(), from.pitch()));
          // Re-anchor lastPos to the snap destination so next tick's move vector reflects the real
          // post-teleport position (≈0) instead of registering the whole jump as a fake "walk".
          lastPos.put(id, new double[] {tx, tz});
        }
      } else if (Math.hypot(step.shoveX[i], step.shoveZ[i]) > MIN_SHOVE) {
        // setVelocity is additive on every platform, so this shove layers on the player's own motion. A
        // dead-zone skips imperceptible nudges so the client is not jittered by sub-threshold packets.
        player.setVelocity(step.shoveX[i], 0.0, step.shoveZ[i]);
      }
      // Publish the live force readouts for this player to the HUD, SIGNED along their movement: a force
      // assisting their motion reads positive (green); one fighting it (spring-back) reads negative (red).
      double springX = step.shoveX[i] - step.dragX[i];
      double springZ = step.shoveZ[i] - step.dragZ[i];
      double pull = ChainHud.signedAlong(springX, springZ, moveX[i], moveZ[i], moveEpsilon);
      double drag = ChainHud.signedAlong(step.dragX[i], step.dragZ[i], moveX[i], moveZ[i], moveEpsilon);
      double total = ChainHud.signedAlong(step.shoveX[i], step.shoveZ[i], moveX[i], moveZ[i], moveEpsilon);
      hud.recordPlayer(id, pull, drag, total, nearestNeighbourDistance(x, z, i, n));
    }

    renderRope(group);
  }

  /**
   * Draws the rope for the current chain: the native lead (leashed marker per link) when {@code rope-style:
   * leash} and the platform supports it, else the legacy particle line. The leash markers are cleared if a
   * config/platform change flips back to particles mid-run so no roped entity is orphaned.
   */
  private void renderRope(List<PlayerAdapter> group) {
    WorldAdapter world = world();
    if (useLeashRope && world != null && world.supportsLeashRope()) {
      leashRope.render(group);
    } else {
      leashRope.clear();
      rope.drawRopes(group);
    }
  }

  /**
   * Standing Y at the surface of the {@code (x,z)} column (the first free space above the highest solid
   * block), or {@code fallbackY} when the platform cannot report a surface height. Lets a snapped player
   * land on the ground at the rope edge instead of being buried in a hillside at the neighbour's height.
   */
  private double surfaceColumnY(double x, double z, double fallbackY) {
    WorldAdapter world = world();
    if (world == null || world.name() == null) {
      return fallbackY;
    }
    int surfaceY = world.highestSolidBlockY(world.name(), (int) Math.floor(x), (int) Math.floor(z));
    return surfaceY == Integer.MIN_VALUE ? fallbackY : surfaceY;
  }

  private static double nearestNeighbourDistance(double[] x, double[] z, int i, int n) {
    double best = Double.MAX_VALUE;
    if (i > 0) {
      best = Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
    }
    if (i < n - 1) {
      best = Math.min(best, Math.hypot(x[i + 1] - x[i], z[i + 1] - z[i]));
    }
    return best == Double.MAX_VALUE ? 0.0 : best;
  }

  // ----- host / chain helpers -------------------------------------------------------------------

  /** Position of the experience's host (its owner) if online, else null. */
  private WorldPosition hostPosition() {
    if (gameContext() == null) {
      return null;
    }
    ExperienceManager experiences = gameContext().experiences();
    WorldAdapter world = world();
    if (experiences == null || !experiences.available() || world == null) {
      return null;
    }
    ExperienceManager.Experience experience = com.sexidium.core.world.WorldKey
        .fromRuntime(world.name(), gameContext().server().worlds().experiencesSubdirName())
        .map(experiences::byWorld).orElse(null);
    if (experience == null || experience.owner() == null) {
      return null;
    }
    PlayerAdapter owner = gameContext().server().player(experience.owner()).orElse(null);
    if (owner != null && owner.online() && owner.position() != null) {
      return owner.position();
    }
    return null;
  }

  /** The last online player already in the chain (excluding {@code joiner}), i.e. the current tail. */
  private PlayerAdapter lastOnlineMember(UUID joiner) {
    for (int i = chainOrder.size() - 1; i >= 0; i--) {
      UUID id = chainOrder.get(i);
      if (id.equals(joiner)) {
        continue;
      }
      PlayerAdapter player = gameContext() == null
          ? null
          : gameContext().server().player(id).orElse(null);
      if (player != null && player.online() && player.position() != null) {
        return player;
      }
    }
    return null;
  }

  private int onlineChainSize() {
    int count = 0;
    for (PlayerAdapter player : online()) {
      if (player != null && player.position() != null) {
        count++;
      }
    }
    return count;
  }

  /** Normalizes a slash-pathed world name (e.g. {@code worlds/experiences/exp_ab12}) to its last segment. */
  private static String lastSegment(String name) {
    if (name == null) {
      return null;
    }
    int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return separator >= 0 ? name.substring(separator + 1) : name;
  }

  // ----- collaborators for ChainDeathLink (same-package bridges to the protected Challenge surface) ----

  boolean deathLinkEnabled() {
    return deathLink;
  }

  boolean soundsEnabled() {
    return playSounds;
  }

  void recordDeath(PlayerAdapter player) {
    stats().recordDeath(player);
  }

  List<PlayerAdapter> onlineParticipants() {
    return online();
  }

  /** Heals and feeds a player where they stand (no teleport), so the chain stays together after a link. */
  void healInPlace(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    player.setHealth(Math.max(1.0, player.maxHealth()));
    player.setFoodLevel(20);
    player.setGameMode(GameModeType.SURVIVAL);
  }
}
