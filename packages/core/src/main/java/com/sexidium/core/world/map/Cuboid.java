package com.sexidium.core.world.map;

import com.sexidium.core.platform.model.BlockPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * An axis-aligned cuboid (box) defined by two opposite block corners, normalised to a min/max box so
 * the order the corners were captured in does not matter. The platform-agnostic geometry primitive
 * behind every "team region" in the battle-map system: one team's base/side in a {@link BattleMap},
 * the block-counting region of TNT War's destruction tracker, and the wireframe drawn by
 * {@link RegionRenderer}. Coordinates are absolute block coordinates and survive a map being cloned
 * into a temp match world (a folder copy keeps every block at the same x/y/z).
 *
 * <p>Generalises the original {@code BaseRegion} (TNT War only) into a shared model.</p>
 */
public final class Cuboid {
  private final int minX;
  private final int minY;
  private final int minZ;
  private final int maxX;
  private final int maxY;
  private final int maxZ;

  public Cuboid(int x1, int y1, int z1, int x2, int y2, int z2) {
    this.minX = Math.min(x1, x2);
    this.minY = Math.min(y1, y2);
    this.minZ = Math.min(z1, z2);
    this.maxX = Math.max(x1, x2);
    this.maxY = Math.max(y1, y2);
    this.maxZ = Math.max(z1, z2);
  }

  /** Builds a cuboid spanning the two opposite corners, or null when either corner is missing. */
  public static Cuboid between(BlockPosition first, BlockPosition second) {
    if (first == null || second == null) {
      return null;
    }
    return new Cuboid(
        first.blockX(), first.blockY(), first.blockZ(),
        second.blockX(), second.blockY(), second.blockZ());
  }

  public int minX() {
    return minX;
  }

  public int minY() {
    return minY;
  }

  public int minZ() {
    return minZ;
  }

  public int maxX() {
    return maxX;
  }

  public int maxY() {
    return maxY;
  }

  public int maxZ() {
    return maxZ;
  }

  /** Total number of block cells in the box (inclusive of both corners). */
  public long volume() {
    return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }

  public boolean contains(BlockPosition blockPosition) {
    return blockPosition != null && contains(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
  }

  /** One vertex of the box, in world coordinates. */
  public record Vertex(double x, double y, double z) {
  }

  /** One edge of the box as two world-coordinate endpoints. */
  public record Edge(Vertex from, Vertex to) {
  }

  /**
   * The 8 vertices of the box in WORLD coordinates: the low corner sits at the block min and the high
   * corner at {@code max + 1}, so the wireframe wraps the full outer faces of the corner blocks rather
   * than cutting through their centres.
   */
  public List<Vertex> corners() {
    double loX = minX;
    double loY = minY;
    double loZ = minZ;
    double hiX = maxX + 1.0;
    double hiY = maxY + 1.0;
    double hiZ = maxZ + 1.0;
    List<Vertex> vertices = new ArrayList<>(8);
    for (double x : new double[] {loX, hiX}) {
      for (double y : new double[] {loY, hiY}) {
        for (double z : new double[] {loZ, hiZ}) {
          vertices.add(new Vertex(x, y, z));
        }
      }
    }
    return vertices;
  }

  /** The 12 edges of the box in WORLD coordinates (see {@link #corners()} for the outer-face convention). */
  public List<Edge> edges() {
    double loX = minX;
    double loY = minY;
    double loZ = minZ;
    double hiX = maxX + 1.0;
    double hiY = maxY + 1.0;
    double hiZ = maxZ + 1.0;
    List<Edge> edges = new ArrayList<>(12);
    // 4 edges along X
    edges.add(edge(loX, loY, loZ, hiX, loY, loZ));
    edges.add(edge(loX, hiY, loZ, hiX, hiY, loZ));
    edges.add(edge(loX, loY, hiZ, hiX, loY, hiZ));
    edges.add(edge(loX, hiY, hiZ, hiX, hiY, hiZ));
    // 4 edges along Y
    edges.add(edge(loX, loY, loZ, loX, hiY, loZ));
    edges.add(edge(hiX, loY, loZ, hiX, hiY, loZ));
    edges.add(edge(loX, loY, hiZ, loX, hiY, hiZ));
    edges.add(edge(hiX, loY, hiZ, hiX, hiY, hiZ));
    // 4 edges along Z
    edges.add(edge(loX, loY, loZ, loX, loY, hiZ));
    edges.add(edge(hiX, loY, loZ, hiX, loY, hiZ));
    edges.add(edge(loX, hiY, loZ, loX, hiY, hiZ));
    edges.add(edge(hiX, hiY, loZ, hiX, hiY, hiZ));
    return edges;
  }

  private static Edge edge(double x1, double y1, double z1, double x2, double y2, double z2) {
    return new Edge(new Vertex(x1, y1, z1), new Vertex(x2, y2, z2));
  }
}
