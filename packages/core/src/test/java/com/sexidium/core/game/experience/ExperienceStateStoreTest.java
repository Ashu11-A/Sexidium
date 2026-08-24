package com.sexidium.core.game.experience;

import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.platform.model.EffectSnapshot;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-trips the per-experience .yml state/snapshot files written inside the world folder. */
class ExperienceStateStoreTest {
  private static final String WORLD = "exp_test";

  private ExperienceStateStore store(Path subdir) {
    return new ExperienceStateStore(subdir, new StdoutLoggerAdapter("Test"));
  }

  @Test
  void persistsOnlyWhenWorldFolderExists(@TempDir Path subdir) throws IOException {
    ExperienceStateStore store = store(subdir);
    assertFalse(store.persistent(WORLD), "no folder yet");

    ExperienceState state = ExperienceState.empty();
    state.setInt("doubledrops.multiplier", 8);
    store.saveSharedState(WORLD, state);
    assertTrue(store.loadSharedState(WORLD).values().isEmpty(), "transient/no-folder world does not persist");

    Files.createDirectories(subdir.resolve(WORLD));
    assertTrue(store.persistent(WORLD));
  }

  @Test
  void sharedState_roundTripsIncludingSpecialChars(@TempDir Path subdir) throws IOException {
    Files.createDirectories(subdir.resolve(WORLD));
    ExperienceStateStore store = store(subdir);

    ExperienceState state = ExperienceState.empty();
    state.setInt("doubledrops.multiplier", 6);
    state.setString("randomizer.pool", "minecraft:diamond,minecraft:emerald");
    state.setLong("growing.startTime", 1718000000000L);
    state.setString("weird", "a=b:c\"d\\e f");   // '=', ':', quote, backslash

    store.saveSharedState(WORLD, state);

    // The file is a real .yml inside the world folder.
    assertTrue(Files.isRegularFile(subdir.resolve(WORLD).resolve("sexidium").resolve("state.yml")));

    ExperienceState loaded = store.loadSharedState(WORLD);
    assertEquals(6, loaded.getInt("doubledrops.multiplier", 0));
    assertEquals("minecraft:diamond,minecraft:emerald", loaded.getString("randomizer.pool", ""));
    assertEquals(1718000000000L, loaded.getLong("growing.startTime", 0L));
    assertEquals("a=b:c\"d\\e f", loaded.getString("weird", ""));
  }

  @Test
  void playerSnapshot_roundTrips(@TempDir Path subdir) throws IOException {
    Files.createDirectories(subdir.resolve(WORLD));
    ExperienceStateStore store = store(subdir);
    UUID id = new UUID(12L, 34L);

    PlayerSnapshot snapshot = new PlayerSnapshot(id, "Steve", null);
    snapshot.worldName = WORLD;
    snapshot.coordinateX = 1.5;
    snapshot.coordinateY = 64.0;
    snapshot.coordinateZ = -2.5;
    snapshot.yaw = 90.0F;
    snapshot.pitch = -12.0F;
    snapshot.gameMode = "SURVIVAL";
    snapshot.health = 18.0;
    snapshot.foodLevel = 17;
    snapshot.xp = 4242;
    snapshot.inventoryPayload = "BASE64==with:special\"chars";
    snapshot.effects = List.of(new EffectSnapshot("speed", 1, 200), new EffectSnapshot("night_vision", 0, 6000));

    assertFalse(store.hasPlayerSnapshot(WORLD, id));
    store.savePlayerSnapshot(WORLD, snapshot);
    assertTrue(store.hasPlayerSnapshot(WORLD, id));

    PlayerSnapshot back = store.loadPlayerSnapshot(WORLD, id, "Steve");
    assertEquals(WORLD, back.worldName);
    assertEquals(1.5, back.coordinateX);
    assertEquals(64.0, back.coordinateY);
    assertEquals(-2.5, back.coordinateZ);
    assertEquals(90.0F, back.yaw);
    assertEquals(-12.0F, back.pitch);
    assertEquals("SURVIVAL", back.gameMode);
    assertEquals(18.0, back.health);
    assertEquals(17, back.foodLevel);
    assertEquals(4242, back.xp);
    assertEquals("BASE64==with:special\"chars", back.inventoryPayload);
    assertEquals(2, back.effects.size());
    assertEquals("speed", back.effects.get(0).effectKey());
    assertEquals(1, back.effects.get(0).amplifier());
    assertEquals(200, back.effects.get(0).durationTicks());
    assertEquals("night_vision", back.effects.get(1).effectKey());
    assertEquals(6000, back.effects.get(1).durationTicks());
    assertEquals(WORLD, back.savedPosition().worldName());
  }

  @Test
  void playerSnapshot_withNoEffects_roundTripsEmptyList(@TempDir Path subdir) throws IOException {
    Files.createDirectories(subdir.resolve(WORLD));
    ExperienceStateStore store = store(subdir);
    UUID id = new UUID(56L, 78L);

    PlayerSnapshot snapshot = new PlayerSnapshot(id, "Alex", null);
    snapshot.worldName = WORLD;
    store.savePlayerSnapshot(WORLD, snapshot);

    PlayerSnapshot back = store.loadPlayerSnapshot(WORLD, id, "Alex");
    assertEquals(0, back.xp);
    assertTrue(back.effects.isEmpty());
  }

  @Test
  void missingPlayerSnapshot_returnsNull(@TempDir Path subdir) throws IOException {
    Files.createDirectories(subdir.resolve(WORLD));
    assertNull(store(subdir).loadPlayerSnapshot(WORLD, new UUID(1L, 1L), "Nobody"));
  }

  @Test
  void nestedKeyResolvesToNestedFolder(@TempDir Path subdir) throws IOException {
    // The store is fed the experience KEY <nick>/<map>_<id>; it must resolve the nested folder, keeping
    // each owner's maps separated (not collapsing to the last segment).
    String key = "ashu11a/diamond_hunt_ab12cd34";
    Files.createDirectories(subdir.resolve("ashu11a").resolve("diamond_hunt_ab12cd34"));
    ExperienceStateStore store = store(subdir);

    assertTrue(store.persistent(key), "nested key must map to the nested exp folder");
    ExperienceState state = ExperienceState.empty();
    state.setInt("doubledrops.multiplier", 3);
    store.saveSharedState(key, state);
    assertTrue(Files.isRegularFile(
        subdir.resolve("ashu11a").resolve("diamond_hunt_ab12cd34").resolve("sexidium").resolve("state.yml")));
    assertEquals(3, store.loadSharedState(key).getInt("doubledrops.multiplier", 0));
  }

  @Test
  void stripsRuntimeSubdirPrefixToTheKey(@TempDir Path root) throws IOException {
    // A runtime Paper name (worlds/experience/<key>) is normalized down to the key by stripping
    // everything up to and including the experiences-subdir segment, so it maps to the same folder.
    Path subdir = root.resolve("experience");
    Files.createDirectories(subdir.resolve("ashu11a").resolve("map_ab12cd34"));
    ExperienceStateStore store = store(subdir);
    String runtimeName = "worlds/experience/ashu11a/map_ab12cd34";

    assertTrue(store.persistent(runtimeName), "runtime slash-path name must map to the key folder");
    ExperienceState state = ExperienceState.empty();
    state.setInt("doubledrops.multiplier", 7);
    store.saveSharedState(runtimeName, state);
    assertTrue(Files.isRegularFile(
        subdir.resolve("ashu11a").resolve("map_ab12cd34").resolve("sexidium").resolve("state.yml")));
    assertEquals(7, store.loadSharedState("ashu11a/map_ab12cd34").getInt("doubledrops.multiplier", 0));
  }
}
