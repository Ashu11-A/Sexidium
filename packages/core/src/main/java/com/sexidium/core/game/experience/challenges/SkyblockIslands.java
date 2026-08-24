package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.world.gen.LootTable;
import com.sexidium.core.world.gen.StructureBuilder;
import com.sexidium.core.world.gen.TreeSpec;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The geometry of the Classic-SkyBlock maps, split out of {@link ClassicSkyblockChallenge} so the shapes
 * can be built and asserted without a live match. Everything here drives only the world-gen engine
 * ({@link StructureBuilder}, {@link LootTable}) and the {@code setBlock} seam, so it is deterministic and
 * platform-agnostic.
 *
 * <p>The Overworld and Nether islands are <b>mirrors</b>: the same L footprint, the same four layers, and
 * a second island at the same {@link #DISTANT_OFFSET_X} offset. The Nether additionally carries a ready,
 * lit portal on a pad off the island's west edge — see {@link #netherPortal}.</p>
 */
final class SkyblockIslands {
  private static final ItemKey NETHERRACK = ItemKey.minecraft("netherrack");
  private static final ItemKey BLACKSTONE = ItemKey.minecraft("blackstone");
  private static final ItemKey OBSIDIAN = ItemKey.minecraft("obsidian");
  private static final ItemKey PORTAL = ItemKey.minecraft("nether_portal");

  /** X offset of the second island from the main one — the same in both dimensions, so they mirror. */
  static final int DISTANT_OFFSET_X = 18;
  /** X offset of the Nether portal pad: clear of the island's west edge, which starts at −2. */
  static final int PORTAL_OFFSET_X = -7;
  /** Frame footprint: 4 wide × 5 tall, the vanilla minimum, with a 2×3 portal surface inside. */
  static final int PORTAL_WIDTH = 4;
  static final int PORTAL_HEIGHT = 5;

  private SkyblockIslands() {
  }

  /**
   * The L-shaped island centred on {@code (cx, cz)}: a 6×6 top with the {@code (+x,+z)} 3×3 corner removed
   * (27 top cells) laid with {@code top}, over three layers of {@code body} (81 blocks). The player's spawn
   * column stays inside the kept region. Both dimensions use this, which is what keeps them identical.
   */
  static void lIsland(WorldAdapter world, String worldName, int cx, int cz, int topY, ItemKey top, ItemKey body) {
    int minX = cx - 2;
    int minZ = cz - 2;
    for (int dx = 0; dx < 6; dx++) {
      for (int dz = 0; dz < 6; dz++) {
        if (dx >= 3 && dz >= 3) {
          continue; // the removed corner that makes the L
        }
        int x = minX + dx;
        int z = minZ + dz;
        world.setBlock(new BlockPosition(worldName, x, topY, z), top);
        world.setBlock(new BlockPosition(worldName, x, topY - 1, z), body);
        world.setBlock(new BlockPosition(worldName, x, topY - 2, z), body);
        world.setBlock(new BlockPosition(worldName, x, topY - 3, z), body);
      }
    }
  }

  /** Whether {@code (x, z)} is one of the island's 27 kept top cells (the L shape above). */
  static boolean onIsland(int cx, int cz, int x, int z) {
    int dx = x - (cx - 2);
    int dz = z - (cz - 2);
    return dx >= 0 && dx < 6 && dz >= 0 && dz < 6 && !(dx >= 3 && dz >= 3);
  }

  /** Loads every chunk overlapping the block AABB, so no {@code setBlock} lands in an unloaded chunk. */
  static void ensureChunks(WorldAdapter world, int minBlockX, int minBlockZ, int maxBlockX, int maxBlockZ) {
    for (int chunkX = minBlockX >> 4; chunkX <= (maxBlockX >> 4); chunkX++) {
      for (int chunkZ = minBlockZ >> 4; chunkZ <= (maxBlockZ >> 4); chunkZ++) {
        world.loadChunk(chunkX, chunkZ, true);
      }
    }
  }

  // ----- Nether mirror ---------------------------------------------------------------------------

  /**
   * Builds the whole Nether side in one pass: the mirror island, a crimson tree, a walled lava basin, the
   * starter chest, a ready-lit portal beside the island and the distant supply island.
   */
  static void netherMirror(WorldAdapter world, String worldName, int cx, int cz, int topY, Random random) {
    StructureBuilder builder = new StructureBuilder(world);
    // Everything the mirror touches: the portal pad to the west, the island, and the distant island.
    ensureChunks(world, cx + PORTAL_OFFSET_X - 2, cz - 4, cx + DISTANT_OFFSET_X + 4, cz + 4);

    lIsland(world, worldName, cx, cz, topY, NETHERRACK, NETHERRACK);
    // A crimson "nether tree": crimson-stem trunk with a nether-wart-block canopy.
    // cz + 3, for the same reason as the Overworld's oak: a radius-2 canopy at cz + 2 covers the spawn
    // column, and the spawn search stands the arrival on top of it rather than on the island.
    builder.tree(worldName, cx - 1, topY, cz + 3, new TreeSpec(
        ItemKey.minecraft("crimson_stem"), 5, ItemKey.minecraft("nether_wart_block"), 2));
    world.setBlock(new BlockPosition(worldName, cx + 1, topY, cz - 2), ItemKey.minecraft("glowstone"));
    lavaBasin(world, worldName, cx - 1, topY, cz - 1);
    builder.chest(worldName, cx + 2, topY + 1, cz - 1, List.of(
        stack("nether_wart", 4), stack("soul_sand", 8), stack("quartz", 8), stack("glowstone", 4),
        stack("gold_ingot", 4), stack("magma_cream", 2), stack("crimson_fungus", 2),
        stack("warped_fungus", 2)), "west");
    netherPortal(world, worldName, cx + PORTAL_OFFSET_X, topY, cz);
    netherDistantIsland(world, builder, worldName, cx + DISTANT_OFFSET_X, topY, cz, random);
    // The spawn column is where the void-arrival rescue drops a player who came out of a portal over
    // nothing, so it has to be standable — and the tree and the lava basin above are both placed within a
    // block or two of it. Netherrack footing, two clear blocks over it.
    builder.reserveStandingSpot(worldName, cx, topY, cz, NETHERRACK, StructureBuilder.STANDING_CLEARANCE);
  }

  /**
   * Brings a Nether mirror built by the OLD layout up to date, in place: seals the original lava source
   * (which sat beside the L's missing corner and poured into the void), then adds the portal and the
   * distant supply island. Nothing is rebuilt and nothing is overwritten — each piece is added only where
   * the world is still untouched — so an existing world keeps every block its players placed.
   *
   * @return true when anything was changed
   */
  static boolean netherRepair(WorldAdapter world, String worldName, int cx, int cz, int topY, Random random) {
    ensureChunks(world, cx + PORTAL_OFFSET_X - 2, cz - 4, cx + DISTANT_OFFSET_X + 4, cz + 4);
    boolean changed = false;
    // The old build put a bare lava source here. Replace it with netherrack (only if it is still lava, so a
    // player who already fixed or repurposed the spot is left alone) and put a proper basin on the island.
    BlockPosition legacyLava = new BlockPosition(worldName, cx, topY, cz + 1);
    if (isBlock(world, legacyLava, "lava")) {
      world.setBlock(legacyLava, NETHERRACK);
      changed = true;
    }
    if (isBlock(world, new BlockPosition(worldName, cx - 1, topY, cz - 1), "netherrack")) {
      lavaBasin(world, worldName, cx - 1, topY, cz - 1);
      changed = true;
    }
    if (isClear(world, worldName, cx + PORTAL_OFFSET_X - 1, topY, cz - 1,
        cx + PORTAL_OFFSET_X + PORTAL_WIDTH, topY + PORTAL_HEIGHT, cz + 1)) {
      netherPortal(world, worldName, cx + PORTAL_OFFSET_X, topY, cz);
      changed = true;
    }
    if (isClear(world, worldName, cx + DISTANT_OFFSET_X, topY - 1, cz - 1,
        cx + DISTANT_OFFSET_X + 2, topY + 1, cz + 1)) {
      netherDistantIsland(world, new StructureBuilder(world), worldName,
          cx + DISTANT_OFFSET_X, topY, cz, random);
      changed = true;
    }
    return changed;
  }

  /** Whether every block in the inclusive AABB is air — i.e. nothing of anyone's would be overwritten. */
  private static boolean isClear(WorldAdapter world, String worldName,
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          if (!isBlock(world, new BlockPosition(worldName, x, y, z), "air")) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean isBlock(WorldAdapter world, BlockPosition position, String value) {
    ItemKey block = world.blockTypeAt(position);
    return block != null && block.value().equals(value);
  }

  /**
   * A one-block lava source enclosed on all four sides and underneath by netherrack, so it can never flow
   * off the island. Every wall is placed explicitly rather than inherited from the island shape — that
   * assumption is exactly what broke when the source sat beside the L's missing corner and poured into the
   * void.
   */
  static void lavaBasin(WorldAdapter world, String worldName, int blockX, int topY, int blockZ) {
    world.setBlock(new BlockPosition(worldName, blockX, topY - 1, blockZ), NETHERRACK);
    world.setBlock(new BlockPosition(worldName, blockX + 1, topY, blockZ), NETHERRACK);
    world.setBlock(new BlockPosition(worldName, blockX - 1, topY, blockZ), NETHERRACK);
    world.setBlock(new BlockPosition(worldName, blockX, topY, blockZ + 1), NETHERRACK);
    world.setBlock(new BlockPosition(worldName, blockX, topY, blockZ - 1), NETHERRACK);
    world.setBlock(new BlockPosition(worldName, blockX, topY, blockZ), ItemKey.minecraft("lava"));
  }

  /**
   * A complete, already-lit nether portal standing on a netherrack pad that bridges east to the island.
   * Because it exists <em>before</em> anyone travels, vanilla's portal search finds and LINKS to it — the
   * player steps out beside the island instead of onto a portal the game improvises in the void at
   * whatever altitude they happened to enter from, which is how portals ended up floating over the island.
   *
   * <p>The frame runs along X, which matches the default {@code nether_portal} axis, so the portal blocks
   * are correct without a platform-specific block-state seam.</p>
   */
  static void netherPortal(WorldAdapter world, String worldName, int baseX, int topY, int centerZ) {
    // Pad: one block wider than the frame, reaching east to the island edge (at baseX + PORTAL_WIDTH + 1)
    // so an arriving player can walk home — and stopping there, so it never overwrites the island itself.
    for (int x = baseX - 1; x <= baseX + PORTAL_WIDTH; x++) {
      for (int z = centerZ - 1; z <= centerZ + 1; z++) {
        world.setBlock(new BlockPosition(worldName, x, topY, z), NETHERRACK);
        world.setBlock(new BlockPosition(worldName, x, topY - 1, z), NETHERRACK);
      }
    }
    for (int dx = 0; dx < PORTAL_WIDTH; dx++) {
      world.setBlock(new BlockPosition(worldName, baseX + dx, topY, centerZ), OBSIDIAN);
      world.setBlock(new BlockPosition(worldName, baseX + dx, topY + PORTAL_HEIGHT - 1, centerZ), OBSIDIAN);
    }
    for (int dy = 1; dy < PORTAL_HEIGHT - 1; dy++) {
      world.setBlock(new BlockPosition(worldName, baseX, topY + dy, centerZ), OBSIDIAN);
      world.setBlock(new BlockPosition(worldName, baseX + PORTAL_WIDTH - 1, topY + dy, centerZ), OBSIDIAN);
      for (int dx = 1; dx < PORTAL_WIDTH - 1; dx++) {
        world.setBlock(new BlockPosition(worldName, baseX + dx, topY + dy, centerZ), PORTAL);
      }
    }
  }

  /**
   * The Nether's answer to the Overworld's distant island: the same 3×3, two-layer shape at the same
   * offset, in Nether blocks, carrying a planted soul-sand wart farm and a chest rolled from
   * {@link #netherDistantLoot()}. Reaching it is the player's problem, exactly as in the Overworld.
   */
  static void netherDistantIsland(WorldAdapter world, StructureBuilder builder, String worldName,
      int islandX, int topY, int islandZ, Random random) {
    for (int dx = 0; dx <= 2; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        world.setBlock(new BlockPosition(worldName, islandX + dx, topY, islandZ + dz), BLACKSTONE);
        world.setBlock(new BlockPosition(worldName, islandX + dx, topY - 1, islandZ + dz), BLACKSTONE);
      }
    }
    world.setBlock(new BlockPosition(worldName, islandX, topY, islandZ - 1), ItemKey.minecraft("soul_sand"));
    world.setBlock(new BlockPosition(worldName, islandX, topY + 1, islandZ - 1), ItemKey.minecraft("nether_wart"));
    world.setBlock(new BlockPosition(worldName, islandX + 1, topY + 1, islandZ - 1), ItemKey.minecraft("shroomlight"));
    builder.chest(worldName, islandX + 2, topY + 1, islandZ + 1,
        netherDistantLoot().roll(random == null ? new Random() : random), "west");
  }

  /**
   * The distant Nether chest's table. The brewing / fungus-farming / portal essentials are
   * <b>guaranteed</b> — in a void Nether there are no fortresses, bastions or forests, so a run would
   * simply be dead if a roll went badly — with a weighted pool of Nether building blocks and rarities on
   * top for variety.
   */
  static LootTable netherDistantLoot() {
    return LootTable.builder()
        .guaranteed(ItemKey.minecraft("blaze_powder"), 4)
        .guaranteed(ItemKey.minecraft("brewing_stand"), 1)
        .guaranteed(ItemKey.minecraft("glass_bottle"), 3)
        .guaranteed(ItemKey.minecraft("nether_wart"), 4)
        .guaranteed(ItemKey.minecraft("soul_sand"), 12)
        .guaranteed(ItemKey.minecraft("crimson_nylium"), 4)
        .guaranteed(ItemKey.minecraft("warped_nylium"), 4)
        .guaranteed(ItemKey.minecraft("bone_meal"), 16)
        .guaranteed(ItemKey.minecraft("gold_ingot"), 8)
        .guaranteed(ItemKey.minecraft("obsidian"), 6)
        .guaranteed(ItemKey.minecraft("flint_and_steel"), 1)
        .rolls(4, 6)
        .pool(ItemKey.minecraft("blackstone"), 8, 16, 8)
        .pool(ItemKey.minecraft("basalt"), 8, 16, 8)
        .pool(ItemKey.minecraft("soul_soil"), 4, 8, 6)
        .pool(ItemKey.minecraft("glowstone"), 4, 8, 6)
        .pool(ItemKey.minecraft("magma_block"), 2, 6, 6)
        .pool(ItemKey.minecraft("nether_gold_ore"), 2, 4, 5)
        .pool(ItemKey.minecraft("quartz_block"), 4, 8, 5)
        .pool(ItemKey.minecraft("gilded_blackstone"), 2, 4, 4)
        .pool(ItemKey.minecraft("blaze_rod"), 1, 2, 3)
        .pool(ItemKey.minecraft("ghast_tear"), 1, 2, 3)
        .pool(ItemKey.minecraft("string"), 4, 8, 3)
        .pool(ItemKey.minecraft("ancient_debris"), 1, 2, 2)
        .pool(ItemKey.minecraft("wither_skeleton_skull"), 1, 1, 1)
        .build();
  }

  static ItemStackData stack(String id, int amount) {
    return new ItemStackData(ItemKey.minecraft(id), amount, Map.of());
  }
}
