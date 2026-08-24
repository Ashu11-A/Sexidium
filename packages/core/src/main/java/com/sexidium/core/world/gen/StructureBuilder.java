package com.sexidium.core.world.gen;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;

import java.util.List;
import java.util.Random;

/**
 * The structure half of the world-gen engine: builds flat slabs, stacked layers, simple trees and filled
 * chests into any {@link WorldAdapter}. It is platform-agnostic (drives only {@code setBlock},
 * {@code placeChest} and {@code loadChunk}), deterministic, and free of game/challenge specifics, so the
 * SkyBlock Random-Layers experience and future challenges can share one generator.
 */
public final class StructureBuilder {
  private final WorldAdapter world;

  public StructureBuilder(WorldAdapter world) {
    if (world == null) {
      throw new IllegalArgumentException("world cannot be null");
    }
    this.world = world;
  }

  /**
   * Fills a {@code size}×{@code size} horizontal slab of {@code block} at height {@code y}, centred on
   * {@code (centerX, centerZ)}. Returns the number of blocks placed (0 for a non-positive size or a null
   * block).
   */
  public int slab(String worldName, int centerX, int centerZ, int y, int size, ItemKey block) {
    if (size <= 0 || block == null) {
      return 0;
    }
    int half = size / 2;
    int placed = 0;
    for (int dx = 0; dx < size; dx++) {
      for (int dz = 0; dz < size; dz++) {
        world.setBlock(new BlockPosition(worldName, centerX - half + dx, y, centerZ - half + dz), block);
        placed++;
      }
    }
    return placed;
  }

  /**
   * Stacks {@code layersTopDown} as consecutive slabs starting at {@code topY} and descending (index 0 is
   * the top layer). Null entries in the list are skipped but still consume a Y level. Returns the Y just
   * below the lowest layer (a null/empty list returns {@code topY}).
   */
  public int stack(String worldName, int centerX, int centerZ, int topY, int size, List<ItemKey> layersTopDown) {
    int y = topY;
    if (layersTopDown != null) {
      for (ItemKey block : layersTopDown) {
        slab(worldName, centerX, centerZ, y, size, block);
        y--;
      }
    }
    return y;
  }

  /**
   * Plants a tree at {@code (x, z)} standing on the surface block at {@code groundY}: a straight trunk of
   * {@code spec.trunkHeight()} logs from {@code groundY + 1} up, wrapped in a leaf canopy of
   * {@code spec.leafRadius()} around and one above the trunk top. Corner columns of the canopy are trimmed
   * for a rounder shape, and leaf blocks never overwrite the trunk. A null {@code spec} is a no-op.
   */
  public void tree(String worldName, int x, int groundY, int z, TreeSpec spec) {
    if (spec == null) {
      return;
    }
    int top = groundY + spec.trunkHeight();
    for (int trunkY = groundY + 1; trunkY <= top; trunkY++) {
      world.setBlock(new BlockPosition(worldName, x, trunkY, z), spec.logBlock());
    }
    int radius = spec.leafRadius();
    for (int leafY = top - radius + 1; leafY <= top + 1; leafY++) {
      for (int lx = -radius; lx <= radius; lx++) {
        for (int lz = -radius; lz <= radius; lz++) {
          if (lx == 0 && lz == 0 && leafY <= top) {
            continue; // keep the trunk column intact up to its top
          }
          if (Math.abs(lx) == radius && Math.abs(lz) == radius) {
            continue; // trim the four corners of each leaf layer
          }
          world.setBlock(new BlockPosition(worldName, x + lx, leafY, z + lz), spec.leafBlock());
        }
      }
    }
  }

  /**
   * Scatters {@code count} trees onto the {@code surfaceY} top of a {@code islandSize} island centred on
   * {@code (centerX, centerZ)}, keeping each trunk far enough from the edge that its canopy stays on the
   * island. Positions are drawn from {@code random}. Returns the number of trees planted (0 for a
   * non-positive count/size, a null spec, or a null random).
   *
   * <p>{@code keepOutRadius} fences off a square that many blocks around the CENTRE, where these islands
   * put the player; zero or less is no fence. Without it the draw can land a trunk exactly on that column
   * — and a trunk's first log sits at {@code surfaceY + 1}, which is the block the player's feet occupy,
   * so they arrive inside it. A one-in-a-hundred draw is easy to dismiss until the mode using it
   * regenerates the world on every death, at which point it is simply a matter of time.</p>
   *
   * <p>A candidate inside the fence is redrawn rather than nudged, so the scatter keeps its distribution;
   * after {@value #KEEP_OUT_ATTEMPTS} refusals that tree is skipped, which is why the return value is the
   * number actually planted and not {@code count}. Skipping is the right failure: an island small enough
   * to be all fence has nowhere legal to plant, and forcing one there would put it back on the player.</p>
   */
  public int scatterTrees(String worldName, int centerX, int centerZ, int surfaceY, int islandSize,
      int count, TreeSpec spec, Random random, int keepOutRadius) {
    if (count <= 0) {
      return 0;
    }
    if (spec == null || islandSize <= 0 || random == null) {
      return 0;
    }
    int half = islandSize / 2;
    int margin = Math.min(half, spec.leafRadius() + 1);
    int span = islandSize - 2 * margin;
    // Zero (or less) is no fence at all, so a caller with nothing to protect keeps the plain scatter.
    int fence = Math.max(0, keepOutRadius);
    int planted = 0;
    for (int index = 0; index < count; index++) {
      int x = 0;
      int z = 0;
      boolean legal = false;
      for (int attempt = 0; attempt < KEEP_OUT_ATTEMPTS && !legal; attempt++) {
        x = centerX - half + margin + (span > 0 ? random.nextInt(span) : 0);
        z = centerZ - half + margin + (span > 0 ? random.nextInt(span) : 0);
        legal = fence == 0
            || Math.abs(x - centerX) > fence || Math.abs(z - centerZ) > fence;
      }
      if (!legal) {
        continue;
      }
      tree(worldName, x, surfaceY, z, spec);
      planted++;
    }
    return planted;
  }

  /** How many times a scatter redraws a trunk that landed in the keep-out before giving up on it. */
  private static final int KEEP_OUT_ATTEMPTS = 16;

  /**
   * Makes {@code (x, groundY + 1, z)} a place a player can actually be dropped into: solid footing at
   * {@code groundY} and {@code headRoom} clear blocks above it.
   *
   * <p><b>Why this is a step of its own.</b> These generated maps compute where to build from the world
   * spawn and then trust that the player will land there — but the two are separate calculations, and the
   * builder is free to put something in the way after the ground is laid. A tree trunk is the obvious
   * case; so is a decoration, a mob-trap plate, or a second challenge composed on top of the first. This
   * asserts the thing the arithmetic only assumed, once, at the end of a build, so "there is somewhere to
   * stand at spawn" stops depending on nothing else having been placed there.</p>
   *
   * <p><b>{@code headRoom} is not "how tall a player is".</b> It is how far up the column has to be empty
   * for the SPAWN SEARCH to agree with the build, and that is a much taller number. The search takes the
   * topmost solid block in the column and stands the player on it; leaves count as solid. So a canopy
   * floating six blocks over the spawn column — nothing to walk into, and easy to think harmless — silently
   * moves the arrival point to the top of the tree, metres above the island the build laid out for them.
   * Clear past anything the build can put overhead, not just past the player's head; see
   * {@link #STANDING_CLEARANCE}.</p>
   *
   * <p>{@code groundBlock} is placed only where the footing is missing, never over what is already there:
   * a mode whose spawn block IS the mechanic (Random Skyblock's single progress block) must keep the block
   * it chose. Pass {@code null} to only clear the head room.</p>
   *
   * @return true when anything was changed — i.e. the spot had genuinely been blocked or was unfooted
   */
  public boolean reserveStandingSpot(String worldName, int x, int groundY, int z, ItemKey groundBlock,
      int headRoom) {
    // The write can land in a chunk nothing has touched yet (a freshly generated void world), where an
    // unloaded chunk silently swallows it — the same reason placeChest loads first.
    world.loadChunk(x >> 4, z >> 4, true);
    boolean changed = false;
    for (int offset = 1; offset <= Math.max(1, headRoom); offset++) {
      BlockPosition head = new BlockPosition(worldName, x, groundY + offset, z);
      if (!isAir(world.blockTypeAt(head))) {
        world.setBlock(head, AIR);
        changed = true;
      }
    }
    if (groundBlock != null) {
      BlockPosition footing = new BlockPosition(worldName, x, groundY, z);
      if (isAir(world.blockTypeAt(footing))) {
        world.setBlock(footing, groundBlock);
        changed = true;
      }
    }
    return changed;
  }

  private static final ItemKey AIR = ItemKey.minecraft("air");

  /**
   * How far above the footing {@link #reserveStandingSpot} should normally clear.
   *
   * <p>Sized to clear a whole tree, because a tree is the tallest thing these builders put over an island
   * and its canopy is what actually moves the spawn: a standard oak is a 5-block trunk with a canopy
   * reaching one block past its top, so eight covers it with room to spare. Two — a player's height — is
   * the number that looks right and is wrong, because the spawn search does not care whether the player
   * would fit; it cares where the topmost solid block is.</p>
   */
  public static final int STANDING_CLEARANCE = 8;

  /**
   * Whether a block is empty space. A null reads as air on purpose: a backend that cannot describe blocks
   * answers null, and there the safe reading is "nothing is known to be in the way" — clearing a spot that
   * was already clear costs a redundant write, whereas refusing to clear one leaves a player inside a log.
   */
  private static boolean isAir(ItemKey block) {
    if (block == null) {
      return true;
    }
    String value = block.value();
    return "air".equals(value) || "cave_air".equals(value) || "void_air".equals(value);
  }

  /**
   * Places a chest at {@code (x, y, z)} filled with {@code contents}, loading the target chunk first so
   * the write always lands even in a freshly generated (e.g. void) world where the chunk was not yet
   * resident.
   */
  public void chest(String worldName, int x, int y, int z, List<ItemStackData> contents) {
    chest(worldName, x, y, z, contents, null);
  }

  /**
   * As {@link #chest(String, int, int, int, List)} but orients the chest to {@code facing} (one of
   * north/south/east/west; null keeps the platform default).
   */
  public void chest(String worldName, int x, int y, int z, List<ItemStackData> contents, String facing) {
    world.loadChunk(x >> 4, z >> 4, true);
    world.placeChest(new BlockPosition(worldName, x, y, z), contents, facing);
  }
}
