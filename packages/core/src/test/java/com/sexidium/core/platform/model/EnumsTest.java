package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnumsTest {

  @Test
  void gameModeType_hasExpectedValues() {
    assertEquals(4, GameModeType.values().length);
    assertNotNull(GameModeType.valueOf("SURVIVAL"));
    assertNotNull(GameModeType.valueOf("CREATIVE"));
    assertNotNull(GameModeType.valueOf("ADVENTURE"));
    assertNotNull(GameModeType.valueOf("SPECTATOR"));
  }

  @Test
  void platformType_hasExpectedValues() {
    assertNotNull(PlatformType.valueOf("BUKKIT"));
    assertNotNull(PlatformType.valueOf("FABRIC"));
    assertNotNull(PlatformType.valueOf("FORGE"));
    assertNotNull(PlatformType.valueOf("NEOFORGE"));
    assertNotNull(PlatformType.valueOf("HYBRID"));
    assertNotNull(PlatformType.valueOf("UNKNOWN"));
  }

  @Test
  void damageCauseType_hasExpectedValues() {
    assertNotNull(DamageCauseType.valueOf("ENTITY_ATTACK"));
    assertNotNull(DamageCauseType.valueOf("FALL"));
    assertNotNull(DamageCauseType.valueOf("UNKNOWN"));
  }

  @Test
  void bossBarColor_hasExpectedValues() {
    assertEquals(7, BossBarColor.values().length);
    assertNotNull(BossBarColor.valueOf("PINK"));
    assertNotNull(BossBarColor.valueOf("WHITE"));
  }

  @Test
  void bossBarOverlay_hasExpectedValues() {
    assertEquals(5, BossBarOverlay.values().length);
    assertNotNull(BossBarOverlay.valueOf("PROGRESS"));
    assertNotNull(BossBarOverlay.valueOf("NOTCHED_20"));
  }
}
