package com.sexidium.core.world.map;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnPointStoreTest {
  private final SpawnPointStore store = new SpawnPointStore(SpawnPointStore.COMBAT);

  @Test
  void roundTripsMultipleSpawnPoints(@TempDir Path folder) throws IOException {
    SpawnPoints points = new SpawnPoints("combat_dojo");
    points.add(new WorldPosition("combat_dojo", 12.5, 64.0, -3.5, 90.0f, 0.0f));
    points.add(new WorldPosition("combat_dojo", -12.5, 64.0, 3.5, -90.0f, 5.0f));
    assertTrue(points.isReady());

    store.save(folder, points);
    assertTrue(Files.isRegularFile(store.fileIn(folder)));

    SpawnPoints loaded = store.load(folder, "combat_dojo");
    assertEquals("combat_dojo", loaded.world());
    assertEquals(2, loaded.size());
    WorldPosition first = loaded.all().get(0);
    assertEquals(12.5, first.coordinateX(), 1e-9);
    assertEquals(90.0f, first.yaw());
    assertEquals(5.0f, loaded.all().get(1).pitch());
    // Spawns are re-stamped with the map world on load.
    assertEquals("combat_dojo", first.worldName());
  }

  @Test
  void setPrimaryReplacesAllPoints(@TempDir Path folder) throws IOException {
    SpawnPoints points = new SpawnPoints("lobby");
    points.add(new WorldPosition("lobby", 1, 64, 1, 0, 0));
    points.add(new WorldPosition("lobby", 2, 64, 2, 0, 0));
    points.setPrimary(new WorldPosition("lobby", 9, 70, 9, 180.0f, 0.0f));
    assertEquals(1, points.size());

    store.save(folder, points);
    SpawnPoints loaded = store.load(folder, "lobby");
    assertEquals(1, loaded.size());
    assertEquals(9.0, loaded.primary().coordinateX(), 1e-9);
  }

  @Test
  void forIndexWrapsAcrossPoints() {
    SpawnPoints points = new SpawnPoints("arena");
    points.add(new WorldPosition("arena", 0, 64, 0, 0, 0));
    points.add(new WorldPosition("arena", 5, 64, 5, 0, 0));
    assertEquals(0.0, points.forIndex(0).coordinateX(), 1e-9);
    assertEquals(5.0, points.forIndex(1).coordinateX(), 1e-9);
    assertEquals(0.0, points.forIndex(2).coordinateX(), 1e-9);
  }

  @Test
  void loadOfMissingFileYieldsEmptyList() {
    SpawnPoints points = store.load(Paths.get("/nonexistent-spawn-folder-xyz"), "ghost");
    assertEquals("ghost", points.world());
    assertFalse(points.isReady());
    assertNotNull(points.all());
  }
}
