package com.sexidium.core.world.map;

import com.sexidium.core.platform.model.BlockPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuboidTest {
  @Test
  void normalisesCornersRegardlessOfOrder() {
    Cuboid forward = new Cuboid(0, 64, 0, 9, 70, 4);
    Cuboid reversed = new Cuboid(9, 70, 4, 0, 64, 0);
    for (Cuboid box : new Cuboid[] {forward, reversed}) {
      assertEquals(0, box.minX());
      assertEquals(64, box.minY());
      assertEquals(0, box.minZ());
      assertEquals(9, box.maxX());
      assertEquals(70, box.maxY());
      assertEquals(4, box.maxZ());
    }
  }

  @Test
  void volumeCountsInclusiveCells() {
    // 10 x 7 x 5 inclusive of both corners.
    assertEquals(10L * 7L * 5L, new Cuboid(0, 64, 0, 9, 70, 4).volume());
    assertEquals(1L, new Cuboid(3, 3, 3, 3, 3, 3).volume());
  }

  @Test
  void containsBoundsAreInclusive() {
    Cuboid box = new Cuboid(0, 64, 0, 4, 68, 4);
    assertTrue(box.contains(0, 64, 0));
    assertTrue(box.contains(4, 68, 4));
    assertTrue(box.contains(2, 66, 2));
    assertFalse(box.contains(5, 66, 2));
    assertFalse(box.contains(2, 63, 2));
    assertTrue(box.contains(new BlockPosition("w", 1, 65, 1)));
    assertFalse(box.contains((BlockPosition) null));
  }

  @Test
  void hasEightCornersAndTwelveEdges() {
    Cuboid box = new Cuboid(0, 0, 0, 3, 3, 3);
    assertEquals(8, box.corners().size());
    assertEquals(12, box.edges().size());
  }

  @Test
  void wireframeUsesOuterFaceOfCornerBlocks() {
    // The high vertex sits at max + 1 so the box wraps the outer faces of the corner blocks.
    Cuboid box = new Cuboid(0, 0, 0, 0, 0, 0);
    boolean sawHigh = box.corners().stream().anyMatch(v -> v.x() == 1.0 && v.y() == 1.0 && v.z() == 1.0);
    boolean sawLow = box.corners().stream().anyMatch(v -> v.x() == 0.0 && v.y() == 0.0 && v.z() == 0.0);
    assertTrue(sawHigh);
    assertTrue(sawLow);
  }

  @Test
  void betweenRequiresBothCorners() {
    assertNull(Cuboid.between(null, new BlockPosition("w", 1, 1, 1)));
    assertNull(Cuboid.between(new BlockPosition("w", 1, 1, 1), null));
    Cuboid box = Cuboid.between(new BlockPosition("w", 5, 5, 5), new BlockPosition("w", 1, 1, 1));
    assertEquals(1, box.minX());
    assertEquals(5, box.maxX());
  }
}
