package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.List;
import java.util.function.Supplier;

import com.sexidium.core.platform.PlayerAdapter;

/**
 * Draws the Chained-Together rope as a sagging line of particles between consecutive chained players.
 * Pure I/O: it reads the live world + tuning (visibility, segment length) through the suppliers the
 * challenge wires in, so the chain physics and roster logic stay out of the rendering path.
 */
final class ChainRopeRenderer {
  // Height (blocks) above the players' feet at which the rope is drawn, so it reads as a waist-height rope.
  private static final double ROPE_HEIGHT = 1.0;

  private final Supplier<WorldAdapter> world;
  private final Supplier<Boolean> visibleRope;
  private final Supplier<Double> segmentLength;

  ChainRopeRenderer(Supplier<WorldAdapter> world, Supplier<Boolean> visibleRope, Supplier<Double> segmentLength) {
    this.world = world;
    this.visibleRope = visibleRope;
    this.segmentLength = segmentLength;
  }

  void drawRopes(List<PlayerAdapter> group) {
    if (!visibleRope.get() || world.get() == null) {
      return;
    }
    for (int i = 0; i < group.size() - 1; i++) {
      PlayerAdapter a = group.get(i);
      PlayerAdapter b = group.get(i + 1);
      if (a.position() != null && b.position() != null) {
        drawRopeBetween(a.position(), b.position());
      }
    }
  }

  /** Renders one rope link as a sagging particle line between two world points (lifted to waist height). */
  void drawRopeBetween(WorldPosition a, WorldPosition b) {
    WorldAdapter world = this.world.get();
    if (!visibleRope.get() || world == null || a == null || b == null) {
      return;
    }
    // Tautness-aware sag: a slack rope droops, a stretched (taut) one pulls nearly straight — an elastic
    // look. Slack is how far the link is INSIDE its rest length; once taut (slack 0) only a hint remains.
    double segment = segmentLength.get();
    double dist = Math.hypot(b.coordinateX() - a.coordinateX(), b.coordinateZ() - a.coordinateZ());
    double slack = Math.max(0.0, segment - dist);
    // Cap the droop to a fraction of the span so a short link never bows into a deep vertical loop.
    double sag = Math.min(0.12 + slack * 0.35, dist * 0.4);
    world.drawRope(
        new WorldPosition(a.worldName(), a.coordinateX(), a.coordinateY() + ROPE_HEIGHT, a.coordinateZ(), 0.0f, 0.0f),
        new WorldPosition(b.worldName(), b.coordinateX(), b.coordinateY() + ROPE_HEIGHT, b.coordinateZ(), 0.0f, 0.0f),
        sag);
  }
}
