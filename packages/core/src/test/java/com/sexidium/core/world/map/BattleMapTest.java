package com.sexidium.core.world.map;

import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleMapTest {
  @Test
  void ensureZoneIsIdempotentAndKeepsItsColour() {
    BattleMap map = new BattleMap("arena", "world");
    TeamZone red = map.ensureZone(0, TeamColor.RED);
    assertSame(red, map.ensureZone(0));
    assertEquals(TeamColor.RED, red.color());
    assertEquals(1, map.teamCount());
  }

  @Test
  void zonesUseThePaletteColourWhenUnspecified() {
    BattleMap map = new BattleMap("arena", "world");
    assertEquals(TeamColor.values()[0], map.ensureZone(0).color());
    assertEquals(TeamColor.values()[1], map.ensureZone(1).color());
  }

  @Test
  void regionIsNullUntilBothCornersSet() {
    BattleMap map = new BattleMap("arena", "world");
    map.setCorner(0, 1, new BlockPosition("world", 0, 64, 0));
    assertNull(map.teamRegion(0));
    map.setCorner(0, 2, new BlockPosition("world", 9, 70, 9));
    Cuboid region = map.teamRegion(0);
    assertNotNull(region);
    assertEquals(0, region.minX());
    assertEquals(9, region.maxX());
  }

  @Test
  void isReadyRequiresTwoZonesEachWithRegionAndSpawn() {
    BattleMap map = new BattleMap("arena", "world");
    assertFalse(map.isReady());

    configureZone(map, 0);
    assertFalse(map.isReady()); // only one team

    configureZone(map, 1);
    assertTrue(map.isReady());

    // A third, half-configured zone drags readiness back to false.
    map.setCorner(2, 1, new BlockPosition("world", 30, 64, 0));
    assertFalse(map.isReady());
  }

  @Test
  void addSpawnAccumulatesPerZone() {
    BattleMap map = new BattleMap("arena", "world");
    map.addSpawn(0, new WorldPosition("world", 1, 64, 1, 0, 0));
    map.addSpawn(0, new WorldPosition("world", 2, 64, 2, 0, 0));
    assertEquals(2, map.ensureZone(0).spawns().size());
    map.clearSpawns(0);
    assertTrue(map.ensureZone(0).spawns().isEmpty());
  }

  private static void configureZone(BattleMap map, int team) {
    map.setCorner(team, 1, new BlockPosition("world", team * 10, 64, 0));
    map.setCorner(team, 2, new BlockPosition("world", team * 10 + 9, 70, 9));
    map.addSpawn(team, new WorldPosition("world", team * 10 + 4, 65, 4, 0, 0));
  }
}
