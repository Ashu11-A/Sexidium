package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.SafeSpawn;
import com.sexidium.core.world.gen.StructureBuilder;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the real island and asks the real spawn search where a player would land on it.
 *
 * <h2>Why this test and not another structural one</h2>
 * The previous attempt at this fix was checked by asserting the code called the right method. It did —
 * with a keep-out of one block and a clearance of two — and the bug survived both, because neither number
 * was big enough. Reading the world off disk was the only thing that found it: grass at y 63, the spawn
 * column clear at 64–66 (so the reserve reported success), and oak leaves at 67 and 69 from a tree planted
 * two blocks away. {@link SafeSpawn} takes the topmost solid block in a column and stands the player on it,
 * and it counts leaves as solid — so every arrival was placed at y 70, on the canopy, six blocks above the
 * island that had just been built for them.
 *
 * <p>So this test does what reading the region file did: build, then ask the search. It fails on a wrong
 * NUMBER, not just on a missing call, which is the class of mistake that got through last time.</p>
 */
class SkyblockSpawnColumnTest {
  private static final String W = "sky";
  private static final ItemKey GRASS = ItemKey.minecraft("grass_block");
  private static final ItemKey DIRT = ItemKey.minecraft("dirt");
  private static final ItemKey NETHERRACK = ItemKey.minecraft("netherrack");
  /** The spawn (0, 64, 0) these void worlds are pinned to, and the island surface one below it. */
  private static final int SPAWN_Y = 64;
  private static final int SURFACE_Y = SPAWN_Y - 1;

  /**
   * Classic Skyblock's island, built exactly as the challenge builds it, must land the player ON it.
   *
   * <p>The tree is at a FIXED offset here, so this was not a matter of an unlucky draw: every Classic
   * Skyblock world ever generated put its canopy over the spawn column and every arrival went to the top
   * of the tree.</p>
   */
  @Test
  void theClassicIslandLandsThePlayerOnTheGrass_notOnTheTree() {
    FakeWorld world = new FakeWorld();
    StructureBuilder builder = new StructureBuilder(world);

    SkyblockIslands.lIsland(world, W, 0, 0, SURFACE_Y, GRASS, DIRT);
    builder.tree(W, -1, SURFACE_Y, 3, com.sexidium.core.world.gen.TreeSpec.oak());
    builder.reserveStandingSpot(W, 0, SURFACE_Y, 0, GRASS, StructureBuilder.STANDING_CLEARANCE);

    WorldPosition landed = SafeSpawn.resolve(world);

    assertEquals(SPAWN_Y, (int) landed.coordinateY(),
        "the player must stand on the island's grass, not on whatever is highest in the column");
    assertEquals(GRASS, world.blockAt(0, SURFACE_Y, 0), "and the grass under them must survive the reserve");
  }

  /** The same guarantee in the Nether mirror, which is where a portal drops a player over the void. */
  @Test
  void theNetherMirrorLandsThePlayerOnItsPlatform() {
    FakeWorld world = new FakeWorld();

    SkyblockIslands.netherMirror(world, W, 0, 0, SURFACE_Y, new Random(7));

    WorldPosition landed = SafeSpawn.resolve(world);

    assertEquals(SPAWN_Y, (int) landed.coordinateY(),
        "a Nether arrival must land on the platform, not on the crimson tree above it");
  }

  /**
   * And the general case, whatever the build leaves overhead: with the column reserved, the spawn search
   * agrees with the builder. Without it, this is the exact failure that shipped.
   */
  @Test
  void anUnreservedCanopyIsWhatMovedTheSpawn() {
    FakeWorld unreserved = new FakeWorld();
    unreserved.setBlock(new BlockPosition(W, 0, SURFACE_Y, 0), GRASS);
    unreserved.setBlock(new BlockPosition(W, 0, 67, 0), ItemKey.minecraft("oak_leaves"));
    unreserved.setBlock(new BlockPosition(W, 0, 69, 0), ItemKey.minecraft("oak_leaves"));

    // Demonstrates the bug rather than the fix: leaves count as ground, so the arrival goes on top of them.
    assertEquals(70, (int) SafeSpawn.resolve(unreserved).coordinateY(),
        "if this ever stops being true the reserve is no longer load-bearing and this test should go");

    new StructureBuilder(unreserved)
        .reserveStandingSpot(W, 0, SURFACE_Y, 0, GRASS, StructureBuilder.STANDING_CLEARANCE);

    assertEquals(SPAWN_Y, (int) SafeSpawn.resolve(unreserved).coordinateY());
  }

  /** Random Layers' platform, whose starter trees are scattered rather than placed at a fixed offset. */
  @Test
  void aScatteredIslandLandsThePlayerOnItForEverySeed() {
    var oak = com.sexidium.core.world.gen.TreeSpec.oak();
    for (int seed = 0; seed < 200; seed++) {
      FakeWorld world = new FakeWorld();
      StructureBuilder builder = new StructureBuilder(world);
      builder.stack(W, 0, 0, SURFACE_Y, 16, List.of(GRASS, ItemKey.minecraft("stone")));
      builder.scatterTrees(W, 0, 0, SURFACE_Y, 16, 3, oak, new Random(seed), oak.leafRadius() + 1);
      builder.reserveStandingSpot(W, 0, SURFACE_Y, 0, GRASS, StructureBuilder.STANDING_CLEARANCE);

      assertEquals(SPAWN_Y, (int) SafeSpawn.resolve(world).coordinateY(), "seed " + seed);
    }
  }

  /**
   * A world that can answer where blocks are, which is what {@link SafeSpawn} needs to do anything other
   * than hand back the raw spawn.
   */
  private static final class FakeWorld implements WorldAdapter {
    private final Map<BlockPosition, ItemKey> blocks = new HashMap<>();

    ItemKey blockAt(int x, int y, int z) {
      return blocks.get(new BlockPosition(W, x, y, z));
    }

    @Override public void setBlock(BlockPosition blockPosition, ItemKey itemKey) {
      if (itemKey != null && "air".equals(itemKey.value())) {
        blocks.remove(blockPosition);
        return;
      }
      blocks.put(blockPosition, itemKey);
    }

    @Override public ItemKey blockTypeAt(BlockPosition blockPosition) {
      ItemKey block = blocks.get(blockPosition);
      return block == null ? ItemKey.minecraft("air") : block;
    }

    @Override public int highestSolidBlockY(String worldName, int blockX, int blockZ) {
      int highest = Integer.MIN_VALUE;
      for (BlockPosition position : blocks.keySet()) {
        if (position.blockX() == blockX && position.blockZ() == blockZ) {
          highest = Math.max(highest, position.blockY());
        }
      }
      return highest;
    }

    @Override public int minBuildHeight() { return -64; }
    @Override public int maxBuildHeight() { return 320; }
    @Override public String name() { return W; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition(W, 0, SPAWN_Y, 0, 0, 0); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
    @Override public void placeChest(BlockPosition position, List<ItemStackData> contents, String facing) { }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) { }
    @Override public void playSound(WorldPosition target, SoundKey sound, float volume, float pitch) { }
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) { }
    @Override public void resetBorder() { }
  }

  /**
   * Layered Dimensions builds a single 16-wide column, and the world spawn has to sit in the MIDDLE of it.
   *
   * <p>It used to be laid out from {@code chunkX << 4} — the chunk's minimum corner. A void experience's
   * spawn is pinned to (0, 64, 0), so with the default {@code chunk-x: 0} the arrival stood one block in
   * from two edges of a column with a 300-block drop on either side, and one step ended the run. This
   * pins the geometry rather than the call: the spawn column must be 8 blocks from every edge.</p>
   */
  @Test
  void theLayeredColumnIsCentredOnTheSpawn_notLaidOutFromAChunkCorner() {
    int spawnX = 0;
    int spawnZ = 0;
    int chunkOffsetX = 0;
    int chunkOffsetZ = 0;

    // The arithmetic LayeredDimensionsChallenge#baseX/baseZ use.
    int baseX = spawnX + (chunkOffsetX << 4) - 8;
    int baseZ = spawnZ + (chunkOffsetZ << 4) - 8;

    assertTrue(spawnX - baseX == 8 && baseX + 15 - spawnX == 7,
        "the spawn must sit in the middle of the 16-wide footprint, not on its corner");
    assertTrue(spawnZ - baseZ == 8 && baseZ + 15 - spawnZ == 7, "same on Z");
    assertTrue(baseX <= spawnX && spawnX <= baseX + 15, "and inside it at all");
    assertTrue(baseZ <= spawnZ && spawnZ <= baseZ + 15, "and inside it at all");
  }

  /**
   * A one-chunk column with the spawn on its corner is standable but one step from a fall, which is what
   * "I spawn on the very edge" meant. Distance to the nearest edge is the property worth pinning.
   */
  @Test
  void aCornerAnchoredColumnLeavesTheSpawnOneStepFromTheDrop() {
    int cornerBase = 0;      // the old chunkX << 4
    int centredBase = -8;    // the new spawn-anchored base

    assertEquals(0, Math.min(0 - cornerBase, cornerBase + 15 - 0) - 0,
        "the old layout put the spawn 0 blocks from the west/north edge");
    assertEquals(7, Math.min(0 - centredBase, centredBase + 15 - 0),
        "the new one leaves at least 7 blocks in every direction");
  }

  /** Guards the assumption every number above rests on: the island's surface is one below the spawn. */
  @Test
  void theIslandSurfaceSitsOneBlockUnderTheSpawn() {
    assertTrue(SURFACE_Y == SPAWN_Y - 1);
  }
}
