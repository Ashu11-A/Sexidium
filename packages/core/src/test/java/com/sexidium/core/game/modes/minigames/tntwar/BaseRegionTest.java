package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.model.BlockPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseRegionTest {
  @Test
  void normalisesCornersRegardlessOfOrder() {
    BaseRegion region = new BaseRegion(10, 70, -3, 4, 64, 5);
    assertEquals(4, region.minX());
    assertEquals(10, region.maxX());
    assertEquals(64, region.minY());
    assertEquals(70, region.maxY());
    assertEquals(-3, region.minZ());
    assertEquals(5, region.maxZ());
  }

  @Test
  void volumeIsInclusiveOfBothCorners() {
    assertEquals(1L, new BaseRegion(0, 0, 0, 0, 0, 0).volume());
    assertEquals(27L, new BaseRegion(0, 0, 0, 2, 2, 2).volume());
    assertEquals(8L, new BaseRegion(0, 0, 0, 1, 1, 1).volume());
  }

  @Test
  void containsChecksTheBox() {
    BaseRegion region = new BaseRegion(0, 0, 0, 4, 4, 4);
    assertTrue(region.contains(2, 2, 2));
    assertTrue(region.contains(0, 0, 0));
    assertTrue(region.contains(4, 4, 4));
    assertFalse(region.contains(5, 2, 2));
    assertFalse(region.contains(-1, 0, 0));
  }

  @Test
  void betweenBuildsFromBlockPositions() {
    BaseRegion region = BaseRegion.between(new BlockPosition("w", 5, 5, 5), new BlockPosition("w", 1, 1, 1));
    assertEquals(1, region.minX());
    assertEquals(5, region.maxX());
    assertEquals(125L, region.volume());
  }
}
