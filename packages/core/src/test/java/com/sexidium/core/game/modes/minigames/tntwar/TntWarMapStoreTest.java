package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TntWarMapStoreTest {
  @Test
  void roundTripsCornersAndSpawns(@TempDir Path folder) throws IOException {
    TntWarMap map = new TntWarMap("classic", "tntwar/classic");
    map.setCorner("red", 1, new BlockPosition("tntwar/classic", 10, 64, -5));
    map.setCorner("red", 2, new BlockPosition("tntwar/classic", 20, 80, 5));
    map.setCorner("blue", 1, new BlockPosition("tntwar/classic", -10, 64, -5));
    map.setCorner("blue", 2, new BlockPosition("tntwar/classic", -20, 80, 5));
    map.setSpawn("red", new WorldPosition("tntwar/classic", 15.5, 65.0, 0.5, -90.0f, 0.0f));
    map.setSpawn("blue", new WorldPosition("tntwar/classic", -15.5, 65.0, 0.5, 90.0f, 0.0f));
    assertTrue(map.isReady());

    TntWarMapStore.save(folder, map);
    assertTrue(Files.isRegularFile(TntWarMapStore.fileIn(folder)));

    TntWarMap loaded = TntWarMapStore.load(folder, "classic");
    assertEquals("tntwar/classic", loaded.world());
    assertTrue(loaded.isReady());
    assertNotNull(loaded.redBase());
    assertEquals(10, loaded.redBase().minX());
    assertEquals(20, loaded.redBase().maxX());
    assertEquals(-20, loaded.blueBase().minX());
    assertEquals(15.5, loaded.redSpawn().coordinateX(), 1e-9);
    assertEquals(-90.0f, loaded.redSpawn().yaw());
    assertEquals(90.0f, loaded.blueSpawn().yaw());
  }

  @Test
  void loadOfMissingFileYieldsUnreadyMap() {
    TntWarMap map = TntWarMapStore.load(java.nio.file.Paths.get("/nonexistent-tntwar-folder-xyz"), "ghost");
    assertEquals("ghost", map.id());
    assertFalse(map.isReady());
  }

  @Test
  void partialMapIsNotReady(@TempDir Path folder) throws IOException {
    TntWarMap map = new TntWarMap("half", "tntwar/half");
    map.setCorner("red", 1, new BlockPosition("tntwar/half", 0, 64, 0));
    map.setCorner("red", 2, new BlockPosition("tntwar/half", 4, 70, 4));
    TntWarMapStore.save(folder, map);
    assertFalse(TntWarMapStore.load(folder, "half").isReady());
  }
}
