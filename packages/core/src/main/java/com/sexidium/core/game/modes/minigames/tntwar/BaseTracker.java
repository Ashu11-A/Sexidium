package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.map.Cuboid;

/**
 * Tracks how much of one base region has been destroyed during a TNT War. At construction it samples
 * the region once to record the baseline count of solid (non-air) blocks; {@link #destruction()} then
 * re-samples on demand and reports the fraction of that baseline that is now gone.
 *
 * <p>Counting is decoupled from the platform via {@link BlockSampler} (a tiny functional interface the
 * mode binds to {@code WorldAdapter.blockTypeAt}), so the destruction maths is fully unit-testable with
 * an in-memory block map. Large bases are sampled on a uniform stride (capped by {@code maxScan}) so a
 * per-second rescan never walks millions of cells on the main thread; the same stride is used for the
 * baseline and every rescan so the fraction stays consistent.</p>
 */
public final class BaseTracker {
  @FunctionalInterface
  public interface BlockSampler {
    /** Resolves the block at the given absolute coordinates; an air-like key counts as destroyed. */
    ItemKey blockAt(int x, int y, int z);
  }

  private final Cuboid region;
  private final BlockSampler sampler;
  private final int stride;
  private final int baselineSolid;

  public BaseTracker(Cuboid region, BlockSampler sampler, int maxScan) {
    this.region = region;
    this.sampler = sampler;
    this.stride = strideFor(region, maxScan);
    this.baselineSolid = countSolid();
  }

  /** Solid blocks present when the match started (after stride sampling). */
  public int baselineSolid() {
    return baselineSolid;
  }

  /** Solid blocks remaining right now (after stride sampling). */
  public int remainingSolid() {
    return countSolid();
  }

  /** Fraction of the baseline that has been destroyed, in {@code [0.0, 1.0]}. */
  public double destruction() {
    if (baselineSolid <= 0) {
      return 0.0;
    }
    int remaining = countSolid();
    double destroyed = (double) (baselineSolid - remaining) / baselineSolid;
    return Math.max(0.0, Math.min(1.0, destroyed));
  }

  /** Destruction as a 0-100 integer percentage, for HUD/boss-bar display. */
  public int destructionPercent() {
    return (int) Math.round(destruction() * 100.0);
  }

  private int countSolid() {
    int solid = 0;
    for (int x = region.minX(); x <= region.maxX(); x += stride) {
      for (int y = region.minY(); y <= region.maxY(); y += stride) {
        for (int z = region.minZ(); z <= region.maxZ(); z += stride) {
          if (isSolid(sampler.blockAt(x, y, z))) {
            solid++;
          }
        }
      }
    }
    return solid;
  }

  private static boolean isSolid(ItemKey blockKey) {
    if (blockKey == null) {
      return false;
    }
    String value = blockKey.value();
    return !(value.equals("air") || value.equals("cave_air") || value.equals("void_air"));
  }

  private static int strideFor(Cuboid region, int maxScan) {
    long volume = region.volume();
    if (maxScan <= 0 || volume <= maxScan) {
      return 1;
    }
    // Uniform stride so the sampled cell count drops to ~maxScan: stride = ceil(cbrt(volume / maxScan)).
    double ratio = (double) volume / maxScan;
    int stride = (int) Math.ceil(Math.cbrt(ratio));
    return Math.max(1, stride);
  }
}
