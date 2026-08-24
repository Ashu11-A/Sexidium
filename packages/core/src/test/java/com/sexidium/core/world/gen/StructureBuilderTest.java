package com.sexidium.core.world.gen;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureBuilderTest {
  private static final String W = "sky";
  private static final ItemKey GRASS = ItemKey.minecraft("grass_block");
  private static final ItemKey STONE = ItemKey.minecraft("stone");
  private static final ItemKey LOG = ItemKey.minecraft("oak_log");
  private static final ItemKey LEAF = ItemKey.minecraft("oak_leaves");

  @Test
  void constructor_rejectsNullWorld() {
    assertThrows(IllegalArgumentException.class, () -> new StructureBuilder(null));
  }

  @Test
  void slab_placesSizeSquaredBlocks_andGuardsBadInput() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);

    assertEquals(0, builder.slab(W, 0, 0, 10, 0, STONE));   // non-positive size
    assertEquals(0, builder.slab(W, 0, 0, 10, 3, null));    // null block
    assertTrue(world.placed.isEmpty());

    assertEquals(9, builder.slab(W, 0, 0, 10, 3, STONE));   // 3x3 centred on (0,0)
    assertEquals(9, world.placed.size());
    assertEquals(STONE, world.blockAt(new BlockPosition(W, -1, 10, -1)));
    assertEquals(STONE, world.blockAt(new BlockPosition(W, 1, 10, 1)));
  }

  @Test
  void stack_descendsThroughLayers_skipsNullEntries_andReturnsFloorY() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);

    assertEquals(10, builder.stack(W, 0, 0, 10, 1, null)); // null list is a no-op, returns topY
    assertTrue(world.placed.isEmpty());

    int floor = builder.stack(W, 0, 0, 10, 1, Arrays.asList(GRASS, null, STONE));
    assertEquals(7, floor); // three levels consumed from y=10 down to y=8, next free y=7
    assertEquals(GRASS, world.blockAt(new BlockPosition(W, 0, 10, 0)));
    assertNull(world.blockAt(new BlockPosition(W, 0, 9, 0)));  // null layer left empty
    assertEquals(STONE, world.blockAt(new BlockPosition(W, 0, 8, 0)));
  }

  @Test
  void tree_buildsTrunkAndTrimmedCanopy_withoutOverwritingTheTrunk() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);
    builder.tree(null, 0, 0, 0, null); // null spec is a no-op
    assertTrue(world.placed.isEmpty());

    // trunkHeight 2, radius 1: trunk at y6,y7 (top=7), canopy on y7 and y8.
    builder.tree(W, 0, 5, 0, new TreeSpec(LOG, 2, LEAF, 1));

    assertEquals(LOG, world.blockAt(new BlockPosition(W, 0, 6, 0)));
    assertEquals(LOG, world.blockAt(new BlockPosition(W, 0, 7, 0)));  // trunk top not overwritten by leaves
    assertEquals(LEAF, world.blockAt(new BlockPosition(W, 0, 8, 0))); // leaf directly above the trunk
    assertEquals(LEAF, world.blockAt(new BlockPosition(W, -1, 7, 0)));// side leaf on the trunk-top layer
    assertNull(world.blockAt(new BlockPosition(W, -1, 7, -1)));       // corner trimmed
  }

  @Test
  void scatterTrees_guardsBadInput() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);
    TreeSpec spec = TreeSpec.oak();

    assertEquals(0, builder.scatterTrees(W, 0, 0, 64, 8, 0, spec, new Random(1), 0));    // count <= 0
    assertEquals(0, builder.scatterTrees(W, 0, 0, 64, 8, 1, null, new Random(1), 0));    // null spec
    assertEquals(0, builder.scatterTrees(W, 0, 0, 64, 0, 1, spec, new Random(1), 0));    // island size <= 0
    assertEquals(0, builder.scatterTrees(W, 0, 0, 64, 8, 1, spec, null, 0));             // null random
    assertTrue(world.placed.isEmpty());
  }

  @Test
  void scatterTrees_plantsCount_forBothWideAndTightIslands() {
    StructureBuilder wide = new StructureBuilder(new RecordingWorld());
    // islandSize 8, radius 1 -> margin 2, span 4 (>0): each tree draws two random offsets.
    assertEquals(2, wide.scatterTrees(W, 0, 0, 64, 8, 2, new TreeSpec(LOG, 3, LEAF, 1), new Random(42), 0));

    StructureBuilder tight = new StructureBuilder(new RecordingWorld());
    // islandSize 2, radius 0 -> margin 1, span 0 (<=0): position pinned to centre, no random draw.
    assertEquals(1, tight.scatterTrees(W, 0, 0, 64, 2, 1, new TreeSpec(LOG, 1, LEAF, 0), new Random(42), 0));
  }

  @Test
  void chest_loadsTheChunkThenPlacesTheFilledChest() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);
    List<ItemStackData> contents = List.of(new ItemStackData(ItemKey.minecraft("lava_bucket"), 1, Map.of()));

    builder.chest(W, 20, 65, 36, contents);

    assertEquals(1, world.loadedChunks.size());
    assertEquals(20 >> 4, world.loadedChunks.get(0).chunkX);
    assertEquals(36 >> 4, world.loadedChunks.get(0).chunkZ);
    assertTrue(world.loadedChunks.get(0).generate);
    assertEquals(1, world.chests.size());
    assertEquals(new BlockPosition(W, 20, 65, 36), world.chests.get(0).position);
    assertEquals(contents, world.chests.get(0).contents);
    assertNull(world.chests.get(0).facing); // no facing given
  }

  @Test
  void chest_passesTheFacingThrough() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);
    builder.chest(W, 3, 64, 5, List.of(), "west");
    assertEquals(1, world.chests.size());
    assertEquals("west", world.chests.get(0).facing);
  }

  // ----- recording fake ------------------------------------------------------------------------

  private record Placement(BlockPosition position, ItemKey block) {
  }

  private record ChestPlacement(BlockPosition position, List<ItemStackData> contents, String facing) {
  }

  private record LoadedChunk(int chunkX, int chunkZ, boolean generate) {
  }

  // ----- the spawn column ------------------------------------------------------------------------

  /**
   * Nothing a scatter plants may end up over the spawn column — and the block to watch is the LEAF, not
   * the log.
   *
   * <p>This is the correction to the first attempt at this fix, which fenced off only one block and so
   * only kept trunks away. A canopy has radius 2: a trunk two blocks from the centre is nowhere near the
   * player and still drapes leaves straight over their column, and the spawn search stands them on the
   * topmost solid block — leaves included. The arrival ends up on top of the tree, metres above the island
   * that was built for them. So the fence has to be the leaf radius plus one, and this asserts the whole
   * canopy, not the trunk.</p>
   */
  @Test
  void scatterTrees_keepsTheWholeCanopyOffTheSpawnColumn() {
    TreeSpec spec = new TreeSpec(LOG, 3, LEAF, 2);
    int fence = spec.leafRadius() + 1;

    // Every seed, not a lucky one: the fence is the guarantee, so it has to hold for the whole draw space.
    for (int seed = 0; seed < 400; seed++) {
      RecordingWorld world = new RecordingWorld();
      new StructureBuilder(world).scatterTrees(W, 0, 0, 64, 16, 3, spec, new Random(seed), fence);

      for (Placement placement : world.placed) {
        boolean onSpawnColumn = placement.position().blockX() == 0 && placement.position().blockZ() == 0;
        assertTrue(!onSpawnColumn, "seed " + seed + " put " + placement.block()
            + " over the spawn column at " + placement.position());
      }
    }
  }

  /**
   * The reserve has to reach a canopy, not just a head.
   *
   * <p>Straight from a real world on disk: the island's grass was at y 63 and the spawn column was clear
   * at 64–66, so a two-block reserve reported everything fine — while oak leaves sat at 67 and 69. The
   * spawn search takes the topmost solid block in the column, which was the leaf at 69, and stood the
   * player at 70: on top of the tree, six blocks above the island.</p>
   */
  @Test
  void reserveStandingSpot_clearsHighEnoughToReachAFloatingCanopy() {
    RecordingWorld world = new RecordingWorld();
    world.setBlock(new BlockPosition(W, 0, 63, 0), GRASS);
    world.setBlock(new BlockPosition(W, 0, 67, 0), LEAF);
    world.setBlock(new BlockPosition(W, 0, 69, 0), LEAF);

    new StructureBuilder(world).reserveStandingSpot(W, 0, 63, 0, GRASS, StructureBuilder.STANDING_CLEARANCE);

    for (int y = 64; y <= 63 + StructureBuilder.STANDING_CLEARANCE; y++) {
      ItemKey block = world.blockAt(new BlockPosition(W, 0, y, 0));
      assertTrue(block == null || "air".equals(block.value()),
          "y " + y + " is still " + block + ", so the spawn search will stand the player on it");
    }
    assertEquals(GRASS, world.blockAt(new BlockPosition(W, 0, 63, 0)), "the island itself must survive");
  }

  /** A standard oak is what the clearance is sized for; anything shorter is covered by construction. */
  @Test
  void standingClearance_coversAWholeOak() {
    TreeSpec oak = TreeSpec.oak();

    // Canopy tops out one block above the trunk, measured from the ground the tree stands on.
    assertTrue(StructureBuilder.STANDING_CLEARANCE >= oak.trunkHeight() + 1,
        "a clearance shorter than the tree it has to see past is the bug this constant exists for");
  }

  /** An island that is all fence has nowhere legal to plant, and must skip rather than force one on. */
  @Test
  void scatterTrees_skipsRatherThanPlantingInsideTheFence() {
    RecordingWorld world = new RecordingWorld();

    int planted = new StructureBuilder(world)
        .scatterTrees(W, 0, 0, 64, 2, 2, new TreeSpec(LOG, 1, LEAF, 0), new Random(7), 4);

    assertEquals(0, planted);
    assertTrue(world.placed.isEmpty(), "nothing may be planted when every candidate is fenced off");
  }

  /** Head room is cleared and missing footing is laid, so the spot is standable by construction. */
  @Test
  void reserveStandingSpot_clearsHeadRoomAndLaysMissingFooting() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);
    // A trunk straight through the standing spot: a log at the feet and another at head height.
    world.setBlock(new BlockPosition(W, 0, 64, 0), LOG);
    world.setBlock(new BlockPosition(W, 0, 65, 0), LOG);

    assertTrue(builder.reserveStandingSpot(W, 0, 63, 0, GRASS, 2));

    assertEquals(GRASS, world.blockAt(new BlockPosition(W, 0, 63, 0)), "footing");
    assertEquals(ItemKey.minecraft("air"), world.blockAt(new BlockPosition(W, 0, 64, 0)), "feet");
    assertEquals(ItemKey.minecraft("air"), world.blockAt(new BlockPosition(W, 0, 65, 0)), "head");
    assertTrue(world.loadedChunks.stream().anyMatch(chunk -> chunk.chunkX() == 0 && chunk.chunkZ() == 0),
        "the write can land in a chunk nothing has touched yet, which would swallow it silently");
  }

  /**
   * Footing that already exists is never replaced. Random Skyblock's spawn block IS its mechanic, and a
   * reserve that helpfully swapped it for grass would delete the whole mode.
   */
  @Test
  void reserveStandingSpot_keepsExistingFootingAndReportsNoChangeWhenAlreadyClear() {
    RecordingWorld world = new RecordingWorld();
    StructureBuilder builder = new StructureBuilder(world);
    ItemKey progressBlock = ItemKey.minecraft("cobblestone");
    world.setBlock(new BlockPosition(W, 0, 63, 0), progressBlock);
    world.placed.clear();

    assertEquals(false, builder.reserveStandingSpot(W, 0, 63, 0, GRASS, 2));

    assertEquals(progressBlock, world.blockAt(new BlockPosition(W, 0, 63, 0)));
    assertTrue(world.placed.isEmpty(), "an already-standable spot must not be rewritten");
  }

  /** A null footing block means "only clear the head room" — the caller owns what stands there. */
  @Test
  void reserveStandingSpot_withNullFootingOnlyClearsAbove() {
    RecordingWorld world = new RecordingWorld();

    assertEquals(false, new StructureBuilder(world).reserveStandingSpot(W, 0, 63, 0, null, 2));

    assertNull(world.blockAt(new BlockPosition(W, 0, 63, 0)), "no footing may be invented");
  }

  private static final class RecordingWorld implements WorldAdapter {
    private final List<Placement> placed = new ArrayList<>();
    private final Map<BlockPosition, ItemKey> blocks = new HashMap<>();
    private final List<ChestPlacement> chests = new ArrayList<>();
    private final List<LoadedChunk> loadedChunks = new ArrayList<>();

    ItemKey blockAt(BlockPosition position) {
      return blocks.get(position);
    }

    @Override public void setBlock(BlockPosition blockPosition, ItemKey itemKey) {
      placed.add(new Placement(blockPosition, itemKey));
      blocks.put(blockPosition, itemKey);
    }

    @Override public ItemKey blockTypeAt(BlockPosition blockPosition) {
      return blocks.get(blockPosition);
    }

    @Override public void placeChest(BlockPosition blockPosition, List<ItemStackData> contents, String facing) {
      chests.add(new ChestPlacement(blockPosition, contents, facing));
    }

    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {
      loadedChunks.add(new LoadedChunk(chunkX, chunkZ, generate));
    }

    @Override public String name() { return W; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition(W, 0, 64, 0, 0, 0); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
  }
}
