package com.sexidium.core.game.experience.challenges;

import java.util.Arrays;

/**
 * Pure physics for the Chained-Together rope. Given the ordered chain of players — their horizontal
 * positions and recent per-tick move vectors — it computes a per-player horizontal shove plus any hard
 * snap-backs, with NO reference to the game host, so the behaviour is deterministically unit-testable
 * (see {@code ChainSolverTest}). {@link ChainedChallenge} owns the I/O (reading positions, applying
 * velocity/teleports, drawing the rope); this class owns only the maths.
 *
 * <p>Model — players {@code 0..n-1} form a line {@code 0-1-2-…-(n-1)}; neighbours are joined by a short
 * rope of rest length {@code segmentLength}:
 * <ul>
 *   <li><b>Slack = free:</b> a segment within its rest length exerts NO force, so players roam freely;
 *       only a genuinely stretched (taut) rope pulls, and only a taut player is ever drag-shoved.</li>
 *   <li><b>Damped, soft-saturated spring:</b> a stretched segment reels each end toward the other with
 *       {@code softSaturate(pullStrength·overshoot + damping·outwardSpeed, maxPull)}. The {@code tanh}
 *       saturation eases the force into the ceiling instead of clipping at a hard corner (elastic feel,
 *       no velocity spike → no camera shake). The {@code damping·outwardSpeed} term adds spring-back when
 *       a player fights the rope and subtracts it when they return, so the segment settles smoothly
 *       without bouncing and a passive/returning player is never flung.</li>
 *   <li><b>Consensus drag:</b> the group heading is the summed <em>unit</em> move vectors of everyone
 *       walking; its magnitude grows with how many agree (their strength adds). Each TAUT player is shoved
 *       along it, weighted toward whoever lags, so the moving majority drags the rest along.</li>
 *   <li><b>Hard snap:</b> a segment stretched past {@code segmentLength × hardSnapMultiplier} snaps the
 *       lagging neighbour straight onto the other.</li>
 * </ul>
 */
final class ChainSolver {
  // Stretch distance (blocks) over which the velocity-damping term fades 0 → full from the rest length,
  // so the spring-back never steps on abruptly at the taut-onset boundary.
  private static final double DAMP_BLEND_WIDTH = 0.5;

  private ChainSolver() {
  }

  /** Tunables, read once from config per tick by {@link ChainedChallenge}. */
  static final class Config {
    final double segmentLength;
    final double pullStrength;
    final double damping;
    final double dragStrength;
    final double maxPull;
    final double moveEpsilon;
    final double hardSnapMultiplier;

    Config(double segmentLength, double pullStrength, double damping, double dragStrength,
        double maxPull, double moveEpsilon, double hardSnapMultiplier) {
      this.segmentLength = segmentLength;
      this.pullStrength = pullStrength;
      this.damping = damping;
      this.dragStrength = dragStrength;
      this.maxPull = maxPull;
      this.moveEpsilon = moveEpsilon;
      this.hardSnapMultiplier = hardSnapMultiplier;
    }
  }

  /**
   * Per-player output: a horizontal shove to add to the player's velocity, plus an optional snap target
   * ({@code snapTo[i]} = the index of the neighbour player {@code i} must be teleported onto, or -1).
   * {@code dragX/dragZ} hold just the consensus-drag component so the HUD can split pull from drag.
   */
  static final class Step {
    final double[] shoveX;
    final double[] shoveZ;
    final double[] dragX;
    final double[] dragZ;
    final int[] snapTo;
    double consensusStrength;

    Step(int n) {
      shoveX = new double[n];
      shoveZ = new double[n];
      dragX = new double[n];
      dragZ = new double[n];
      snapTo = new int[n];
      Arrays.fill(snapTo, -1);
    }
  }

  /**
   * Solve one tick. {@code x}/{@code z} are horizontal positions in chain order; {@code moveX}/
   * {@code moveZ} are each player's displacement since the previous tick (0 on the first tick). All four
   * arrays share length {@code n}.
   */
  static Step solve(double[] x, double[] z, double[] moveX, double[] moveZ, Config config) {
    int n = x.length;
    Step step = new Step(n);
    if (n < 2) {
      return step;
    }

    // 1. Consensus heading: sum the UNIT move vectors of everyone actually walking.
    double sumX = 0.0;
    double sumZ = 0.0;
    for (int i = 0; i < n; i++) {
      double m = Math.hypot(moveX[i], moveZ[i]);
      if (m > config.moveEpsilon) {
        sumX += moveX[i] / m;
        sumZ += moveZ[i] / m;
      }
    }
    double consensusMag = Math.hypot(sumX, sumZ);
    step.consensusStrength = consensusMag;
    double cdx = 0.0;
    double cdz = 0.0;
    if (consensusMag > 1.0e-6) {
      cdx = sumX / consensusMag;
      cdz = sumZ / consensusMag;
    }

    // 2. Per-segment damped spring + hard snap. A slack segment (within its rest length) exerts NO force,
    // so players roam freely until the rope actually pulls taut. `taut[i]` then gates the consensus drag
    // below, so an un-stretched player is never dragged.
    boolean[] taut = new boolean[n];
    for (int i = 0; i < n - 1; i++) {
      int a = i;
      int b = i + 1;
      double dx = x[b] - x[a];
      double dz = z[b] - z[a];
      double dist = Math.hypot(dx, dz);
      if (dist <= config.segmentLength || dist < 1.0e-6) {
        continue;
      }
      taut[a] = true;
      taut[b] = true;
      if (dist > config.segmentLength * config.hardSnapMultiplier) {
        int lag = alignment(moveX[a], moveZ[a], cdx, cdz) <= alignment(moveX[b], moveZ[b], cdx, cdz) ? a : b;
        step.snapTo[lag] = lag == a ? b : a;
        continue;
      }
      double ux = dx / dist; // unit a → b
      double uz = dz / dist;
      double overshoot = dist - config.segmentLength;
      double base = config.pullStrength * overshoot;
      // Fade the velocity (damping) term in over the first half-block of stretch, so — like the position
      // term — it grows continuously from 0 at the rest length instead of stepping on the instant the rope
      // pulls taut. Without this a player straining at the boundary feels a repeated on/off tug.
      double dampBlend = Math.min(1.0, overshoot / DAMP_BLEND_WIDTH);
      // Outward speed of each end (component of its motion AWAY from the neighbour); positive only when it
      // is fleeing the rope. The damping term adds spring-back when fighting and SUBTRACTS when returning,
      // critically damping the segment so it settles smoothly instead of bouncing.
      double outA = -(moveX[a] * ux + moveZ[a] * uz);
      double outB = moveX[b] * ux + moveZ[b] * uz;
      double forceA = softSaturate(base + config.damping * outA * dampBlend, config.maxPull); // pull a → b
      double forceB = softSaturate(base + config.damping * outB * dampBlend, config.maxPull); // pull b → a
      step.shoveX[a] += ux * forceA;
      step.shoveZ[a] += uz * forceA;
      step.shoveX[b] -= ux * forceB;
      step.shoveZ[b] -= uz * forceB;
    }

    // 3. Consensus drag — only for TAUT players, so a player whose rope is slack keeps moving freely.
    if (consensusMag > 1.0e-6) {
      for (int i = 0; i < n; i++) {
        if (!taut[i]) {
          continue;
        }
        double align = alignment(moveX[i], moveZ[i], cdx, cdz);
        double lagFactor = (1.0 - align) * 0.5;
        double drag = softSaturate(config.dragStrength * consensusMag * lagFactor, config.maxPull);
        step.shoveX[i] += cdx * drag;
        step.shoveZ[i] += cdz * drag;
        step.dragX[i] += cdx * drag;
        step.dragZ[i] += cdz * drag;
      }
    }
    return step;
  }

  /**
   * Smoothly saturates a non-negative force toward {@code max} with a {@code tanh} curve: small forces
   * pass through almost linearly while large ones ease into the ceiling instead of hitting a hard corner.
   * This rounds off the cap so the rope feels elastic and never delivers a sudden velocity spike (the
   * source of the camera shake). Returns 0 for any non-positive input (the spring never pushes outward).
   */
  private static double softSaturate(double value, double max) {
    if (value <= 0.0 || max <= 0.0) {
      return 0.0;
    }
    return max * Math.tanh(value / max);
  }

  /** Cosine alignment of a player's heading with the group heading; 0 when the player is standing still. */
  private static double alignment(double mx, double mz, double cdx, double cdz) {
    double m = Math.hypot(mx, mz);
    if (m < 1.0e-6) {
      return 0.0;
    }
    return (mx / m) * cdx + (mz / m) * cdz;
  }
}
