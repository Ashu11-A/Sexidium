package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.DoubleSupplier;

/**
 * The right-side HUD readout for Chained Together: the live, per-player physics forces (pull, drag,
 * total — signed along the player's movement) plus the chain size, link distance and team-pull
 * consensus, which together prove the chain maths is running. The physics tick pushes the per-player
 * force/distance and the consensus in here; the challenge wires the chain size + segment length via
 * suppliers. Also home to {@link #signedAlong}, shared with the tick.
 */
final class ChainHud {
  // Live per-player force readouts: {spring/pull magnitude, drag magnitude, total} in blocks/tick.
  private final Map<UUID, double[]> hudForce = new HashMap<>();
  // Distance (blocks) from each player to its nearest roped neighbour.
  private final Map<UUID, Double> hudLinkDist = new HashMap<>();
  // Strength of the group's agreed heading last tick (≈ how many walk together).
  private double hudConsensus;

  private final IntSupplier onlineChainSize;
  private final DoubleSupplier segmentLength;

  ChainHud(IntSupplier onlineChainSize, DoubleSupplier segmentLength) {
    this.onlineChainSize = onlineChainSize;
    this.segmentLength = segmentLength;
  }

  /**
   * Right-side HUD for Chained Together. The player-facing line is just the chain state (how many are
   * roped and the link length). The live per-player physics readouts (pull/drag/total force, link
   * distance, team-pull consensus) are diagnostics — they prove the chain maths is running but mean
   * nothing to a normal player — so they are gated behind the global {@code debug} flag and only shown
   * to operators debugging the experience.
   */
  void describeHud(HudContext context) {
    PlayerAdapter player = context.player();
    int chained = onlineChainSize.getAsInt();
    double segment = segmentLength.getAsDouble();
    String state = chained >= 2
        ? chained + " roped · " + (int) segment + "m links"
        : "solo · free (need 2+ to chain)";
    context.line("<gray>Chain:</gray> <white>" + state + "</white>");
    if (!context.debug()) {
      return;
    }
    double[] force = player == null ? null : hudForce.get(player.uniqueId());
    // Forces are signed along the player's movement: assisting = green +, resisting (spring-back) = red −.
    context.debugLine("<dark_gray>⚙ Pull force:</dark_gray> " + colorForce(force, 0));
    context.debugLine("<dark_gray>⚙ Drag force:</dark_gray> " + colorForce(force, 1));
    context.debugLine("<dark_gray>⚙ Total force:</dark_gray> " + colorForce(force, 2));
    Double dist = player == null ? null : hudLinkDist.get(player.uniqueId());
    if (dist != null) {
      context.debugStat("Link dist", String.format(Locale.ROOT, "%.1fm", dist));
    }
    context.debugStat("Team pull", fmt(hudConsensus));
  }

  /** Records this tick's per-player force readout (pull, drag, total) and link distance. */
  void recordPlayer(UUID id, double pull, double drag, double total, double linkDistance) {
    hudForce.put(id, new double[] {pull, drag, total});
    hudLinkDist.put(id, linkDistance);
  }

  void setConsensus(double consensus) {
    hudConsensus = consensus;
  }

  void clear() {
    hudForce.clear();
    hudLinkDist.clear();
    hudConsensus = 0.0;
  }

  /** Drops a leaving player's per-player readouts. */
  void forget(UUID id) {
    hudForce.remove(id);
    hudLinkDist.remove(id);
  }

  private static String fmt(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  /** A signed force readout: green with a {@code +} when ≥0, red with a {@code −} when negative. */
  private static String colorForce(double[] force, int index) {
    if (force == null) {
      return "<gray>0.00 b/t</gray>";
    }
    double value = force[index];
    String colour = value >= 0.0 ? "green" : "red";
    return "<" + colour + ">" + String.format(Locale.ROOT, "%+.2f", value) + " b/t</" + colour + ">";
  }

  /**
   * The component of a force vector along the player's movement direction (signed): positive when the
   * force helps where they are going, negative when it fights them. A standing player (no movement) gets
   * the force's magnitude, shown positive.
   */
  static double signedAlong(double fx, double fz, double mx, double mz, double moveEpsilon) {
    double moveLen = Math.hypot(mx, mz);
    if (moveLen <= moveEpsilon) {
      return Math.hypot(fx, fz);
    }
    return (fx * mx + fz * mz) / moveLen;
  }
}
