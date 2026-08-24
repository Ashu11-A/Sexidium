package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.map.Cuboid;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseTrackerTest {
  private static String key(int x, int y, int z) {
    return x + "," + y + "," + z;
  }

  /** Sampler over a mutable set of solid cells: a present cell is stone, an absent cell is air. */
  private static BaseTracker.BlockSampler samplerOf(Set<String> solid) {
    return (x, y, z) -> solid.contains(key(x, y, z)) ? ItemKey.minecraft("stone") : ItemKey.minecraft("air");
  }

  @Test
  void baselineCountsSolidBlocks() {
    Set<String> solid = new HashSet<>();
    for (int x = 0; x <= 2; x++) {
      for (int y = 0; y <= 2; y++) {
        for (int z = 0; z <= 2; z++) {
          solid.add(key(x, y, z));
        }
      }
    }
    BaseTracker tracker = new BaseTracker(new Cuboid(0, 0, 0, 2, 2, 2), samplerOf(solid), 0);
    assertEquals(27, tracker.baselineSolid());
    assertEquals(0, tracker.destructionPercent());
  }

  @Test
  void destructionRisesAsBlocksAreRemoved() {
    Set<String> solid = new HashSet<>();
    for (int x = 0; x <= 3; x++) {
      for (int z = 0; z <= 3; z++) {
        solid.add(key(x, 0, z)); // a 4x4 = 16-block floor
      }
    }
    BaseTracker tracker = new BaseTracker(new Cuboid(0, 0, 0, 3, 0, 3), samplerOf(solid), 0);
    assertEquals(16, tracker.baselineSolid());

    for (int x = 0; x <= 3; x++) {
      solid.remove(key(x, 0, 0)); // remove 4 of 16 -> 25%
    }
    assertEquals(25, tracker.destructionPercent());
    assertEquals(0.25, tracker.destruction(), 1e-9);
  }

  @Test
  void fullyDestroyedReportsHundredPercent() {
    Set<String> solid = new HashSet<>();
    solid.add(key(1, 1, 1));
    solid.add(key(1, 2, 1));
    BaseTracker tracker = new BaseTracker(new Cuboid(0, 0, 0, 2, 2, 2), samplerOf(solid), 0);
    assertEquals(2, tracker.baselineSolid());
    solid.clear();
    assertEquals(100, tracker.destructionPercent());
  }

  @Test
  void emptyBaselineNeverDividesByZero() {
    BaseTracker tracker = new BaseTracker(new Cuboid(0, 0, 0, 2, 2, 2), samplerOf(new HashSet<>()), 0);
    assertEquals(0, tracker.baselineSolid());
    assertEquals(0, tracker.destructionPercent());
  }

  @Test
  void largeRegionIsStrideSampledUnderTheCap() {
    Set<String> solid = new HashSet<>();
    for (int x = 0; x < 100; x++) {
      for (int y = 0; y < 100; y++) {
        for (int z = 0; z < 100; z++) {
          solid.add(key(x, y, z));
        }
      }
    }
    // 1,000,000 cells capped at 1,000 -> stride 10 -> ~1,000 sampled cells, never the full million.
    BaseTracker tracker = new BaseTracker(new Cuboid(0, 0, 0, 99, 99, 99), samplerOf(solid), 1000);
    assertTrue(tracker.baselineSolid() <= 2000, "stride sampling must cap the scan count");
    assertTrue(tracker.baselineSolid() > 0);
    solid.clear();
    assertEquals(100, tracker.destructionPercent());
  }
}
