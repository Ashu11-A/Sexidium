package com.sexidium.core.platform;

import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the {@link WorldAdapter#safeSpawnPosition()} default — the single helper every "drop the player
 * into the world" fallback relies on to avoid spawning inside terrain or under water.
 */
class WorldAdapterSafeSpawnTest {

  @Test
  void liftsSpawnAboveTheSurfaceBlock() {
    // raw spawn buried at y=64, terrain surface at y=70 -> stand one block above the surface (71).
    FakeWorld world = new FakeWorld(new WorldPosition("exp", 10.5, 64.0, 20.5, 90.0f, 0.0f), 70);
    WorldPosition safe = world.safeSpawnPosition();
    assertEquals(71.0, safe.coordinateY(), 1e-9);
    assertEquals(10.5, safe.coordinateX(), 1e-9);
    assertEquals(20.5, safe.coordinateZ(), 1e-9);
    assertEquals(90.0f, safe.yaw());
    assertEquals("exp", safe.worldName());
  }

  @Test
  void keepsAConfiguredSpawnThatIsAlreadyAboveTheSurface() {
    // a deliberately raised spawn platform (y=100) above terrain (y=70) is preserved, not pulled down.
    FakeWorld world = new FakeWorld(new WorldPosition("exp", 0.5, 100.0, 0.5, 0.0f, 0.0f), 70);
    assertEquals(100.0, world.safeSpawnPosition().coordinateY(), 1e-9);
  }

  @Test
  void fallsBackToRawSpawnWhenSurfaceHeightUnsupported() {
    // platform that cannot report a surface height (e.g. NeoForge) returns the raw spawn unchanged.
    FakeWorld world = new FakeWorld(new WorldPosition("exp", 5.0, 63.0, 5.0, 0.0f, 0.0f), Integer.MIN_VALUE);
    assertEquals(63.0, world.safeSpawnPosition().coordinateY(), 1e-9);
  }

  @Test
  void returnsNullWhenWorldHasNoSpawn() {
    FakeWorld world = new FakeWorld(null, 70);
    assertNull(world.safeSpawnPosition());
  }

  /** Minimal {@link WorldAdapter} that only models a spawn + a reported surface height. */
  private static final class FakeWorld implements WorldAdapter {
    private final WorldPosition spawn;
    private final int surfaceY;

    FakeWorld(WorldPosition spawn, int surfaceY) {
      this.spawn = spawn;
      this.surfaceY = surfaceY;
    }

    @Override public String name() { return "exp"; }
    @Override public WorldPosition spawnPosition() { return spawn; }
    @Override public int highestSolidBlockY(String worldName, int blockX, int blockZ) { return surfaceY; }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }
}
