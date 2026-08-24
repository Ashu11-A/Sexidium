package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldBorderSpecTest {

  @Test
  void fields_areAccessible() {
    WorldBorderSpec spec = new WorldBorderSpec(0.0, 0.0, 200.0, 5, 0.2);
    assertEquals(0.0, spec.centerX());
    assertEquals(0.0, spec.centerZ());
    assertEquals(200.0, spec.size());
    assertEquals(5, spec.warningDistance());
    assertEquals(0.2, spec.damagePerBlock());
  }

  @Test
  void equality_byValue() {
    WorldBorderSpec a = new WorldBorderSpec(0, 0, 100, 5, 0.2);
    WorldBorderSpec b = new WorldBorderSpec(0, 0, 100, 5, 0.2);
    assertEquals(a, b);
  }
}
