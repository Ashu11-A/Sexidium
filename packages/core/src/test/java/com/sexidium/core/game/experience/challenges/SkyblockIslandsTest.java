package com.sexidium.core.game.experience.challenges;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Classic-SkyBlock map geometry — the three things that were wrong in the Nether: a lava
 * source that could spill into the void, a portal that was not there before travel (so vanilla built a
 * floating one), and a missing distant supply island.
 */
class SkyblockIslandsTest {
  private static final String W = "sky_nether";
  private static final int CX = 0;
  private static final int CZ = 0;
  private static final int TOP_Y = 63;

  @Test
  void netherIslandHasTheSameFootprintAsTheOverworld() {
    RecordingWorld nether = new RecordingWorld();
    SkyblockIslands.netherMirror(nether, W, CX, CZ, TOP_Y, new Random(1));
    RecordingWorld overworld = new RecordingWorld();
    SkyblockIslands.lIsland(overworld, W, CX, CZ, TOP_Y,
        ItemKey.minecraft("grass_block"), ItemKey.minecraft("dirt"));

    // Every cell the Overworld island occupies is also solid in the Nether mirror, all four layers deep.
    for (BlockPosition position : overworld.placed.keySet()) {
      assertNotNull(nether.blockAt(position), "nether is missing the island cell " + position);
    }
    assertEquals(27 * 4, overworld.placed.size()); // 27 top cells × 4 layers
  }

  @Test
  void theLavaSourceIsWalledOnEverySideSoItCannotReachTheVoid() {
    RecordingWorld world = new RecordingWorld();
    SkyblockIslands.netherMirror(world, W, CX, CZ, TOP_Y, new Random(1));

    BlockPosition lava = onlyLava(world);
    assertNotNull(lava, "the mirror should place exactly one lava source");
    // Floor plus all four horizontal neighbours are solid, so the source has nowhere to flow.
    assertSolid(world, lava.blockX(), lava.blockY() - 1, lava.blockZ());
    assertSolid(world, lava.blockX() + 1, lava.blockY(), lava.blockZ());
    assertSolid(world, lava.blockX() - 1, lava.blockY(), lava.blockZ());
    assertSolid(world, lava.blockX(), lava.blockY(), lava.blockZ() + 1);
    assertSolid(world, lava.blockX(), lava.blockY(), lava.blockZ() - 1);
    // …and the basin sits on the island, not hanging off its edge.
    assertTrue(SkyblockIslands.onIsland(CX, CZ, lava.blockX(), lava.blockZ()));
  }

  @Test
  void aLitPortalStandsOnGroundBesideTheIsland() {
    RecordingWorld world = new RecordingWorld();
    SkyblockIslands.netherMirror(world, W, CX, CZ, TOP_Y, new Random(1));

    List<BlockPosition> portalBlocks = world.positionsOf("nether_portal");
    // A 4×5 frame encloses a 2×3 portal surface.
    assertEquals(6, portalBlocks.size());
    for (BlockPosition position : portalBlocks) {
      // Never above the island surface line by more than the frame's height, and never ON the island —
      // the whole point is that it stands on its own pad beside it.
      assertTrue(position.blockY() > TOP_Y && position.blockY() < TOP_Y + SkyblockIslands.PORTAL_HEIGHT);
      assertFalse(SkyblockIslands.onIsland(CX, CZ, position.blockX(), position.blockZ()));
    }
    // The portal is west of the island and its pad reaches the island edge, so it is walkable.
    int padEdge = CX + SkyblockIslands.PORTAL_OFFSET_X + SkyblockIslands.PORTAL_WIDTH;
    assertEquals(CX - 3, padEdge);
    assertSolid(world, padEdge, TOP_Y, CZ);
    assertSolid(world, CX - 2, TOP_Y, CZ); // the island edge right next to it
    // The frame itself is obsidian, so the portal survives as a real, re-lightable structure.
    assertEquals(ItemKey.minecraft("obsidian"),
        world.blockAt(new BlockPosition(W, CX + SkyblockIslands.PORTAL_OFFSET_X, TOP_Y, CZ)));
  }

  @Test
  void theDistantNetherIslandMirrorsTheOverworldOffsetAndCarriesTheEssentials() {
    RecordingWorld world = new RecordingWorld();
    SkyblockIslands.netherMirror(world, W, CX, CZ, TOP_Y, new Random(7));

    int islandX = CX + SkyblockIslands.DISTANT_OFFSET_X;
    assertSolid(world, islandX, TOP_Y, CZ);
    assertSolid(world, islandX + 2, TOP_Y, CZ + 1);
    assertEquals(ItemKey.minecraft("soul_sand"), world.blockAt(new BlockPosition(W, islandX, TOP_Y, CZ - 1)));

    // Two chests: the starter one on the island and the supply one on the distant island.
    assertEquals(2, world.chests.size());
    List<ItemStackData> distant = world.chests.get(new BlockPosition(W, islandX + 2, TOP_Y + 1, CZ + 1));
    assertNotNull(distant, "the distant island should carry a chest");
    Set<String> items = new HashSet<>();
    for (ItemStackData stack : distant) {
      items.add(stack.itemKey().value());
    }
    // The things a void Nether can never provide are guaranteed, not rolled.
    assertTrue(items.containsAll(Set.of("blaze_powder", "brewing_stand", "nether_wart", "soul_sand",
        "crimson_nylium", "warped_nylium", "obsidian", "flint_and_steel")), "missing essentials: " + items);
  }

  @Test
  void theLootTableAlwaysYieldsTheEssentialsAcrossSeeds() {
    for (int seed = 0; seed < 25; seed++) {
      List<ItemStackData> loot = SkyblockIslands.netherDistantLoot().roll(new Random(seed));
      Set<String> items = new HashSet<>();
      for (ItemStackData stack : loot) {
        items.add(stack.itemKey().value());
      }
      assertTrue(items.contains("blaze_powder") && items.contains("brewing_stand"), "seed " + seed);
      assertTrue(loot.size() >= 15, "seed " + seed + " rolled only " + loot.size() + " stacks");
    }
  }

  @Test
  void repairSealsTheLegacyLavaAndAddsThePortalWithoutTouchingPlayerBuilds() {
    // A world built by the OLD layout: the L island plus a bare lava source at the spot that leaked.
    RecordingWorld world = new RecordingWorld();
    SkyblockIslands.lIsland(world, W, CX, CZ, TOP_Y, ItemKey.minecraft("netherrack"), ItemKey.minecraft("netherrack"));
    world.setBlock(new BlockPosition(W, CX, TOP_Y, CZ + 1), ItemKey.minecraft("lava"));
    // …and something a player built where the distant island would go.
    BlockPosition playerBlock = new BlockPosition(W, CX + SkyblockIslands.DISTANT_OFFSET_X + 1, TOP_Y, CZ);
    world.setBlock(playerBlock, ItemKey.minecraft("gold_block"));

    assertTrue(SkyblockIslands.netherRepair(world, W, CX, CZ, TOP_Y, new Random(3)));

    // The leaking source is gone, and a properly walled basin exists instead.
    assertSolid(world, CX, TOP_Y, CZ + 1);
    assertEquals(1, world.positionsOf("lava").size());
    // The portal was added (its area was empty)…
    assertEquals(6, world.positionsOf("nether_portal").size());
    // …but the occupied distant-island area was left completely alone.
    assertEquals(ItemKey.minecraft("gold_block"), world.blockAt(playerBlock));
    assertEquals(0, world.chests.size());
  }

  @Test
  void repairIsIdempotent() {
    RecordingWorld world = new RecordingWorld();
    SkyblockIslands.netherMirror(world, W, CX, CZ, TOP_Y, new Random(1));
    int blocks = world.placed.size();
    int chests = world.chests.size();
    assertFalse(SkyblockIslands.netherRepair(world, W, CX, CZ, TOP_Y, new Random(1)),
        "a current-layout world needs no repair");
    assertEquals(blocks, world.placed.size());
    assertEquals(chests, world.chests.size());
  }

  private static BlockPosition onlyLava(RecordingWorld world) {
    List<BlockPosition> lava = world.positionsOf("lava");
    assertEquals(1, lava.size(), "expected exactly one lava source, got " + lava);
    return lava.get(0);
  }

  private static void assertSolid(RecordingWorld world, int blockX, int blockY, int blockZ) {
    ItemKey block = world.blockAt(new BlockPosition(W, blockX, blockY, blockZ));
    assertNotNull(block, "expected a solid block at " + blockX + "," + blockY + "," + blockZ);
    assertFalse("lava".equals(block.value()) || "air".equals(block.value()),
        "expected a solid block at " + blockX + "," + blockY + "," + blockZ + " but found " + block.value());
  }

  /** A {@link WorldAdapter} that just records what was built. */
  private static final class RecordingWorld implements WorldAdapter {
    final Map<BlockPosition, ItemKey> placed = new HashMap<>();
    final Map<BlockPosition, List<ItemStackData>> chests = new HashMap<>();

    ItemKey blockAt(BlockPosition position) {
      return placed.get(position);
    }

    List<BlockPosition> positionsOf(String value) {
      List<BlockPosition> found = new ArrayList<>();
      placed.forEach((position, block) -> {
        if (block.value().equals(value)) {
          found.add(position);
        }
      });
      return found;
    }

    @Override public void setBlock(BlockPosition blockPosition, ItemKey itemKey) {
      placed.put(blockPosition, itemKey);
    }

    @Override public void placeChest(BlockPosition blockPosition, List<ItemStackData> contents, String facing) {
      chests.put(blockPosition, contents);
      placed.put(blockPosition, ItemKey.minecraft("chest"));
    }

    @Override public ItemKey blockTypeAt(BlockPosition blockPosition) {
      ItemKey block = placed.get(blockPosition);
      return block == null ? ItemKey.minecraft("air") : block;
    }

    @Override public String name() { return W; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition(W, 0.5, 64, 0.5, 0f, 0f); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }
}
