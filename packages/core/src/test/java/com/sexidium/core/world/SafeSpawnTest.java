package com.sexidium.core.world;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the promise the starting-world feature rests on: a player is always put ON TOP of a block —
 * never inside terrain, never under water and never in mid-air — as close to the spawn area as the
 * terrain allows.
 */
class SafeSpawnTest {

  @Test
  void standsOnTheSurfaceBlockOfTheSpawnColumn() {
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.ground(0, 0, 70, "grass_block");
    WorldPosition safe = world.safeSpawnPosition();
    assertEquals(71.0, safe.coordinateY(), 1e-9);
    assertEquals(0.5, safe.coordinateX(), 1e-9);
    assertEquals(0.5, safe.coordinateZ(), 1e-9);
  }

  @Test
  void neverSpawnsUnderWater() {
    // The spawn column is an ocean: the surface block is water, so the search moves out to dry land.
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.ground(0, 0, 40, "sand");
    for (int y = 41; y <= 62; y++) {
      world.set(0, y, 0, "water");
    }
    world.ground(6, 0, 68, "grass_block");
    WorldPosition safe = world.safeSpawnPosition();
    assertEquals(6.5, safe.coordinateX(), 1e-9);
    assertEquals(69.0, safe.coordinateY(), 1e-9);
  }

  @Test
  void neverSpawnsInLavaAndAlwaysHasGroundUnderfootAndAirAbove() {
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.ground(0, 0, 30, "netherrack");
    world.set(0, 31, 0, "lava");
    world.ground(6, 0, 65, "grass_block");
    WorldPosition safe = world.safeSpawnPosition();
    assertEquals(6.5, safe.coordinateX(), 1e-9);
    assertEquals(66.0, safe.coordinateY(), 1e-9);
    // The invariant the whole feature rests on: solid block below, free space at head height.
    int blockX = (int) Math.floor(safe.coordinateX());
    int blockZ = (int) Math.floor(safe.coordinateZ());
    int blockY = (int) safe.coordinateY();
    assertEquals("grass_block", world.blockTypeAt(new BlockPosition("exp", blockX, blockY - 1, blockZ)).value());
    assertEquals("air", world.blockTypeAt(new BlockPosition("exp", blockX, blockY, blockZ)).value());
    assertEquals("air", world.blockTypeAt(new BlockPosition("exp", blockX, blockY + 1, blockZ)).value());
  }

  @Test
  void walksThroughGrassAndFlowersToTheGroundBeneath() {
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.ground(0, 0, 70, "dirt");
    world.set(0, 71, 0, "tall_grass"); // the reported "highest block" is the plant, not the ground
    assertEquals(71.0, world.safeSpawnPosition().coordinateY(), 1e-9);
  }

  @Test
  void avoidsTheNetherRoof() {
    // In the Nether the topmost block is the bedrock roof; the scan must start below it.
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.nether = true;
    world.ground(0, 0, 127, "bedrock");
    world.set(0, 60, 0, "netherrack");
    WorldPosition safe = world.safeSpawnPosition();
    assertEquals(61.0, safe.coordinateY(), 1e-9);
  }

  @Test
  void fallsBackToTheSurfaceLiftWhenNothingNearbyWorks() {
    // An all-void world (a SkyBlock before its island exists) still yields a position rather than null.
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.ground(0, 0, 20, "water");
    WorldPosition safe = world.safeSpawnPosition();
    assertTrue(safe.coordinateY() >= 64.0);
  }

  @Test
  void returnsNullWithoutASpawn() {
    assertNull(new BlockWorld(null).safeSpawnPosition());
  }

  @Test
  void anOceanSpawnReachesForTheShoreRatherThanTheWaterSurface() {
    // The reported bug: players appearing in the middle of the ocean. The "highest block" of an ocean
    // column is the WATER SURFACE, so the plain lift used to drop them straight into the sea. Land is
    // beyond the close-in ring search, so only the wider shore sweep can find it.
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.ground(0, 0, 45, "sand");
    for (int y = 46; y <= 62; y++) {
      world.set(0, y, 0, "water");
    }
    world.ground(96, 0, 70, "grass_block"); // a shore well past MAX_RADIUS

    WorldPosition safe = world.safeSpawnPosition();

    assertEquals(96.5, safe.coordinateX(), 1e-9, "must walk out to the shore");
    assertEquals(71.0, safe.coordinateY(), 1e-9);
    assertEquals("grass_block",
        world.blockTypeAt(new BlockPosition("exp", 96, 70, 0)).value(), "standing on dry land");
  }

  @Test
  void anEndlessOceanStillYieldsAPositionRatherThanFailing() {
    // Nothing to stand on anywhere in range. The search must still answer — a caller that teleports a
    // player cannot cope with null — even though there is no good answer to give.
    BlockWorld world = new BlockWorld(new WorldPosition("exp", 0.5, 64.0, 0.5, 0f, 0f));
    world.ground(0, 0, 45, "sand");
    for (int y = 46; y <= 62; y++) {
      world.set(0, y, 0, "water");
    }
    assertNotNull(world.safeSpawnPosition());
  }

  /** A {@link WorldAdapter} backed by a sparse block map, enough to exercise the column search. */
  private static final class BlockWorld implements WorldAdapter {
    private final WorldPosition spawn;
    private final Map<String, String> blocks = new HashMap<>();
    private final Map<String, Integer> tops = new HashMap<>();
    private boolean nether;

    BlockWorld(WorldPosition spawn) {
      this.spawn = spawn;
    }

    /** Places a solid block at the top of a column and records it as that column's highest block. */
    void ground(int blockX, int blockZ, int blockY, String value) {
      set(blockX, blockY, blockZ, value);
      tops.put(blockX + ":" + blockZ, blockY);
    }

    void set(int blockX, int blockY, int blockZ, String value) {
      blocks.put(blockX + ":" + blockY + ":" + blockZ, value);
      tops.merge(blockX + ":" + blockZ, blockY, Math::max);
    }

    @Override public String name() { return "exp"; }
    @Override public WorldPosition spawnPosition() { return spawn; }
    @Override public boolean isNether() { return nether; }

    @Override
    public int highestSolidBlockY(String worldName, int blockX, int blockZ) {
      Integer top = tops.get(blockX + ":" + blockZ);
      return top == null ? Integer.MIN_VALUE : top;
    }

    @Override
    public ItemKey blockTypeAt(BlockPosition blockPosition) {
      String value = blocks.get(blockPosition.blockX() + ":" + blockPosition.blockY() + ":" + blockPosition.blockZ());
      return ItemKey.minecraft(value == null ? "air" : value);
    }

    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }
}
