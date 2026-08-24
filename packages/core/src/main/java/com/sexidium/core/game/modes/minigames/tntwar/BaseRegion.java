package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.model.BlockPosition;

/**
 * An axis-aligned cuboid describing one team's base in a TNT-War map. Built from two opposite corners
 * (set by the {@code /sx admin map tntwar} config commands); the corners are normalised to a min/max box so the
 * order they were captured in does not matter. The region's coordinates are absolute and survive the
 * map being cloned into a temp match world (a folder copy keeps every block at the same x/y/z), so the
 * same region drives {@link BaseTracker} block counting in whatever world the match runs in.
 */
public final class BaseRegion {
  private final int minX;
  private final int minY;
  private final int minZ;
  private final int maxX;
  private final int maxY;
  private final int maxZ;

  public BaseRegion(int x1, int y1, int z1, int x2, int y2, int z2) {
    this.minX = Math.min(x1, x2);
    this.minY = Math.min(y1, y2);
    this.minZ = Math.min(z1, z2);
    this.maxX = Math.max(x1, x2);
    this.maxY = Math.max(y1, y2);
    this.maxZ = Math.max(z1, z2);
  }

  public static BaseRegion between(BlockPosition first, BlockPosition second) {
    return new BaseRegion(
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
}
