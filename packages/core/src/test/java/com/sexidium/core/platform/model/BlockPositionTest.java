package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockPositionTest {

  @Test
  void fields_areAccessible() {
    BlockPosition pos = new BlockPosition("world", 10, 64, -5);
    assertEquals("world", pos.worldName());
    assertEquals(10, pos.blockX());
    assertEquals(64, pos.blockY());
    assertEquals(-5, pos.blockZ());
  }

  @Test
  void equality_byValue() {
    BlockPosition a = new BlockPosition("w", 1, 2, 3);
    BlockPosition b = new BlockPosition("w", 1, 2, 3);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void inequality_whenFieldsDiffer() {
    BlockPosition a = new BlockPosition("w", 1, 2, 3);
    BlockPosition b = new BlockPosition("w", 1, 2, 4);
    assertNotEquals(a, b);
  }
}
