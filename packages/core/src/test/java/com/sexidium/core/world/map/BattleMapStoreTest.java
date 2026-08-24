package com.sexidium.core.world.map;

import com.sexidium.core.game.modes.minigames.tntwar.TntWarMap;
import com.sexidium.core.game.modes.minigames.tntwar.TntWarMapStore;
import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BattleMapStoreTest {
  @Test
  void roundTripsThreeTeamsWithColoursCornersAndSpawns(@TempDir Path folder) throws IOException {
    BattleMap map = new BattleMap("trios", "minigames/trios");
    for (int team = 0; team < 3; team++) {
      map.ensureZone(team, TeamColor.values()[team]);
      map.setCorner(team, 1, new BlockPosition("minigames/trios", team * 20, 64, -5));
      map.setCorner(team, 2, new BlockPosition("minigames/trios", team * 20 + 10, 80, 5));
      map.addSpawn(team, new WorldPosition("minigames/trios", team * 20 + 5.5, 65.0, 0.5, 90.0f * team, 0.0f));
    }
    map.addSpawn(0, new WorldPosition("minigames/trios", 1.5, 65.0, 1.5, 45.0f, 10.0f));
    assertTrue(map.isReady());

    BattleMapStore.save(folder, map);
    assertTrue(Files.isRegularFile(BattleMapStore.fileIn(folder)));

    BattleMap loaded = BattleMapStore.load(folder, "trios");
    assertEquals("minigames/trios", loaded.world());
    assertEquals(3, loaded.teamCount());
    assertTrue(loaded.isReady());

    assertEquals(TeamColor.values()[2], loaded.zone(2).color());
    Cuboid region1 = loaded.teamRegion(1);
    assertNotNull(region1);
    assertEquals(20, region1.minX());
    assertEquals(30, region1.maxX());
    // Team 0 captured two spawns; the extra one survives the round trip with its yaw/pitch.
    assertEquals(2, loaded.zone(0).spawns().size());
    WorldPosition extra = loaded.zone(0).spawns().get(1);
    assertEquals(45.0f, extra.yaw());
    assertEquals(10.0f, extra.pitch());
    // Spawns are re-stamped with the map world on load.
    assertEquals("minigames/trios", extra.worldName());
  }

  @Test
  void loadOfMissingFileYieldsEmptyMap() {
    BattleMap map = BattleMapStore.load(Paths.get("/nonexistent-battlemap-folder-xyz"), "ghost");
    assertEquals("ghost", map.id());
    assertEquals(0, map.teamCount());
    assertFalse(map.isReady());
  }

  @Test
  void partialMapPersistsButIsNotReady(@TempDir Path folder) throws IOException {
    BattleMap map = new BattleMap("half", "minigames/half");
    map.setCorner(0, 1, new BlockPosition("minigames/half", 0, 64, 0));
    map.setCorner(0, 2, new BlockPosition("minigames/half", 4, 70, 4)); // region but no spawn
    map.addSpawn(1, new WorldPosition("minigames/half", 9, 64, 9, 0, 0)); // spawn but no region
    BattleMapStore.save(folder, map);

    BattleMap loaded = BattleMapStore.load(folder, "half");
    assertEquals(2, loaded.teamCount());
    assertFalse(loaded.isReady());
    assertNotNull(loaded.teamRegion(0));
    assertNull(loaded.teamRegion(1));
  }

  @Test
  void importsLegacyTntWarSidecarOnceWhenNoBattleMapExists(@TempDir Path folder) throws IOException {
    TntWarMap legacy = new TntWarMap("classic", "tntwar/classic");
    legacy.setCorner("red", 1, new BlockPosition("tntwar/classic", 10, 64, -5));
    legacy.setCorner("red", 2, new BlockPosition("tntwar/classic", 20, 80, 5));
    legacy.setCorner("blue", 1, new BlockPosition("tntwar/classic", -10, 64, -5));
    legacy.setCorner("blue", 2, new BlockPosition("tntwar/classic", -20, 80, 5));
    legacy.setSpawn("red", new WorldPosition("tntwar/classic", 15.5, 65.0, 0.5, -90.0f, 0.0f));
    legacy.setSpawn("blue", new WorldPosition("tntwar/classic", -15.5, 65.0, 0.5, 90.0f, 0.0f));
    TntWarMapStore.save(folder, legacy);
    assertFalse(BattleMapStore.exists(folder));

    BattleMap imported = BattleMapStore.loadOrImportTntWar(folder, "classic");
    assertEquals(2, imported.teamCount());
    assertEquals(TeamColor.RED, imported.zone(0).color());
    assertEquals(TeamColor.BLUE, imported.zone(1).color());
    assertEquals(10, imported.teamRegion(0).minX());
    assertEquals(-20, imported.teamRegion(1).minX());
    assertEquals(-90.0f, imported.zone(0).spawns().get(0).yaw());
    assertTrue(imported.isReady());
  }

  @Test
  void prefersExistingBattleMapOverLegacySidecar(@TempDir Path folder) throws IOException {
    // Both files present: the new battlemap wins; the legacy import is never consulted.
    TntWarMap legacy = new TntWarMap("classic", "tntwar/classic");
    legacy.setCorner("red", 1, new BlockPosition("tntwar/classic", 99, 64, 99));
    legacy.setCorner("red", 2, new BlockPosition("tntwar/classic", 100, 70, 100));
    TntWarMapStore.save(folder, legacy);

    BattleMap fresh = new BattleMap("classic", "tntwar/classic");
    fresh.setCorner(0, 1, new BlockPosition("tntwar/classic", 1, 64, 1));
    fresh.setCorner(0, 2, new BlockPosition("tntwar/classic", 2, 65, 2));
    BattleMapStore.save(folder, fresh);

    BattleMap loaded = BattleMapStore.loadOrImportTntWar(folder, "classic");
    assertEquals(1, loaded.teamCount());
    assertEquals(1, loaded.teamRegion(0).minX()); // from the battlemap, not the legacy 99
  }
}
