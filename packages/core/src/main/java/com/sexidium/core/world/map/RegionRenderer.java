package com.sexidium.core.world.map;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;

/**
 * Draws a {@link Cuboid} as a coloured particle wireframe and marks spawn points, by stepping points
 * along the box edges and calling {@link WorldAdapter#spawnDust}. Platform-agnostic and side-effect
 * free apart from the particle calls, so it is driven the same way on every platform and can be
 * unit-tested with a fake {@link WorldAdapter} that records the dust calls.
 *
 * <p>Used by the map editor's debug render loop to show each team's region in its team colour.</p>
 */
public final class RegionRenderer {
  /** Default spacing (in blocks) between dust points along an edge. */
  public static final double DEFAULT_STEP = 1.0;
  /** Default dust particle scale. */
  public static final float DEFAULT_SIZE = 1.0f;

  private RegionRenderer() {
  }

  /** Outlines the cuboid's 12 edges with {@code rgb} dust spaced {@code step} blocks apart. */
  public static void outline(WorldAdapter world, Cuboid box, int rgb, double step, float size) {
    if (world == null || box == null) {
      return;
    }
    double spacing = step <= 0 ? DEFAULT_STEP : step;
    String worldName = world.name();
    for (Cuboid.Edge edge : box.edges()) {
      drawEdge(world, worldName, edge, rgb, spacing, size);
    }
  }

  /** Outlines with the default step/size. */
  public static void outline(WorldAdapter world, Cuboid box, int rgb) {
    outline(world, box, rgb, DEFAULT_STEP, DEFAULT_SIZE);
  }

  /** Draws a short vertical column of dust at a spawn point so it stands out from the region edges. */
  public static void marker(WorldAdapter world, WorldPosition spawn, int rgb, float size) {
    if (world == null || spawn == null) {
      return;
    }
    for (double dy = 0.0; dy <= 2.0; dy += 0.5) {
      world.spawnDust(new WorldPosition(world.name(),
          spawn.coordinateX(), spawn.coordinateY() + dy, spawn.coordinateZ(), 0f, 0f), rgb, size);
    }
  }

  private static void drawEdge(WorldAdapter world, String worldName, Cuboid.Edge edge,
      int rgb, double spacing, float size) {
    Cuboid.Vertex from = edge.from();
    Cuboid.Vertex to = edge.to();
    double dx = to.x() - from.x();
    double dy = to.y() - from.y();
    double dz = to.z() - from.z();
    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
    int steps = Math.max(1, (int) Math.ceil(length / spacing));
    for (int i = 0; i <= steps; i++) {
      double t = (double) i / steps;
      world.spawnDust(new WorldPosition(worldName,
          from.x() + dx * t, from.y() + dy * t, from.z() + dz * t, 0f, 0f), rgb, size);
    }
  }
}
