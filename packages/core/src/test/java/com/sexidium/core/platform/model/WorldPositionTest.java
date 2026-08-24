package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldPositionTest {

  @Test
  void fields_areAccessible() {
    WorldPosition pos = new WorldPosition("world", 1.0, 64.0, -3.0, 90.0f, 0.0f);
    assertEquals("world", pos.worldName());
    assertEquals(1.0, pos.coordinateX());
    assertEquals(64.0, pos.coordinateY());
    assertEquals(-3.0, pos.coordinateZ());
    assertEquals(90.0f, pos.yaw());
    assertEquals(0.0f, pos.pitch());
  }

  @Test
  void withWorldName_createsNewPositionWithDifferentWorld() {
    WorldPosition pos = new WorldPosition("world", 1.0, 2.0, 3.0, 0f, 0f);
    WorldPosition moved = pos.withWorldName("nether");
    assertEquals("nether", moved.worldName());
    assertEquals(1.0, moved.coordinateX());
    assertEquals(2.0, moved.coordinateY());
    assertEquals(3.0, moved.coordinateZ());
  }

  @Test
  void offset_addsToCoordinates() {
    WorldPosition pos = new WorldPosition("world", 10.0, 64.0, 10.0, 0f, 0f);
    WorldPosition offset = pos.offset(5.0, -10.0, 3.0);
    assertEquals(15.0, offset.coordinateX());
    assertEquals(54.0, offset.coordinateY());
    assertEquals(13.0, offset.coordinateZ());
    assertEquals("world", offset.worldName());
  }

  @Test
  void equality_byValue() {
    WorldPosition a = new WorldPosition("w", 1, 2, 3, 0f, 0f);
    WorldPosition b = new WorldPosition("w", 1, 2, 3, 0f, 0f);
    assertEquals(a, b);
  }
}
