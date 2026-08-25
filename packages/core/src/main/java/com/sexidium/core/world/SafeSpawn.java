package com.sexidium.core.world;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.Locale;
import java.util.Set;

/**
 * Finds a spot a player can be dropped into without dying or suffocating: standing ON TOP of a solid
 * block, with head room, never submerged in water/lava and never left hanging in mid-air. This is the
 * single implementation behind {@link WorldAdapter#safeSpawnPosition()} and the "start in the
 * Nether/End" entry teleports, so every path into a world lands the same way.
 *
 * <h2>How a column is judged</h2>
 * Scanning downwards from the top of the column, the first block that is solid ground with two free
 * (non-solid, non-liquid) blocks above it is the standing spot. A column whose scan passes THROUGH a
 * liquid before reaching that spot is rejected outright — that is the "spawned under water" case — and
 * the search moves outward, ring by ring, to the nearest column that does work. In the Nether the scan
 * starts below the bedrock roof, so a player never lands on top of the roof.
 *
 * <p>When the platform cannot describe blocks (a backend that only reports a surface height, e.g. the
 * NeoForge adapter or a test double — {@link WorldAdapter#blockTypeAt} then answers {@code air} even at
 * the reported surface, which is impossible for a real world) the search degrades to the previous
 * behaviour: one block above the reported surface, never below a deliberately raised spawn Y.</p>
 */
public final class SafeSpawn {
  /** How far out (in blocks) to look for a usable column before giving up. */
  private static final int MAX_RADIUS = 24;
  /** Spacing between probed columns in a ring — coarse on purpose, each probe may generate a chunk. */
  private static final int RING_STEP = 6;
  /**
   * How far the "find a shore" sweep reaches when the spawn column turns out to be open water, and how
   * coarsely it probes. Deliberately a last resort with a wide step: each probe can generate a chunk, so
   * this must stay a rare rescue, not the normal path. The normal path is
   * {@link com.sexidium.core.platform.WorldAdapter#locateLandSpawn}, which puts the world's spawn on land
   * in the first place using the biome source and generates nothing at all.
   */
  private static final int SHORE_RADIUS = 256;
  private static final int SHORE_STEP = 24;
  /**
   * The sweep for a world whose spawn hangs over nothing at all: a SkyBlock-shaped world whose island or
   * column was built somewhere the spawn is not.
   *
   * <p>The step has to be smaller than a chunk and it is the whole reason this is not just reused from the
   * shore sweep above. That one probes every 24 blocks, which cannot see a 16-wide column at all — the two
   * nearest probes land on either side of it. Eight guarantees at least one probe inside any island a
   * chunk wide or more, which is every generated map here. Reachable only when the anchor column is
   * genuinely empty, so a populated world never pays for it.</p>
   */
  private static final int VOID_SWEEP_RADIUS = 128;
  private static final int VOID_SWEEP_STEP = 8;
  /**
   * How far the LOCAL suffocation escape ({@link #nearestFree}) reaches, as a Chebyshev radius in
   * blocks. Eight is a 17x17x17 box — about 4900 block reads worst case, all inside chunks already
   * loaded around the player, so it generates nothing and costs nothing a rescue cannot afford. Wide
   * enough to step out of a wall, a floor or a filled room; narrow enough that the answer is still
   * recognisably WHERE THE PLAYER WAS, which is the whole point of it.
   */
  private static final int LOCAL_ESCAPE_RADIUS = 8;
  /** Hard cap on the vertical scan of one column, so a tall/void world cannot spin. */
  private static final int MAX_COLUMN_SCAN = 192;
  /** Where the Nether scan starts: below the bedrock roof, above the usual lava sea. */
  private static final int NETHER_SCAN_TOP = 118;
  /** Blocks that make a column unusable when they sit above the ground (the "underwater" case). */
  private static final Set<String> LIQUIDS = Set.of(
      "water", "flowing_water", "lava", "flowing_lava", "bubble_column");
  /**
   * Non-solid blocks a player can stand IN but not ON: the scan walks through them and they count as
   * free head room. Anything not listed here (and not a liquid) is treated as solid ground.
   */
  private static final Set<String> PASSABLE = Set.of(
      "air", "cave_air", "void_air", "grass", "short_grass", "tall_grass", "fern", "large_fern",
      "dead_bush", "seagrass", "tall_seagrass", "kelp", "kelp_plant", "vine", "cobweb", "snow",
      "fire", "soul_fire", "torch", "soul_torch", "sugar_cane", "sweet_berry_bush", "nether_sprouts",
      "warped_roots", "crimson_roots", "twisting_vines", "weeping_vines", "glow_lichen", "structure_void");

  private SafeSpawn() {
  }

  /** A safe standing position at (or near) the world's spawn; null when the world has no spawn. */
  public static WorldPosition resolve(WorldAdapter world) {
    return world == null ? null : near(world, world.spawnPosition());
  }

  /**
   * A safe standing position at (or near) {@code around} in {@code world}: the same column when it
   * works, otherwise the nearest probed column that does. Returns {@code around} unchanged when the
   * platform cannot describe the terrain at all.
   */
  public static WorldPosition near(WorldAdapter world, WorldPosition around) {
    if (world == null || around == null) {
      return around;
    }
    int originX = (int) Math.floor(around.coordinateX());
    int originZ = (int) Math.floor(around.coordinateZ());
    String worldName = around.worldName() != null ? around.worldName() : world.name();
    int surfaceY = world.highestSolidBlockY(worldName, originX, originZ);
    if (surfaceY == Integer.MIN_VALUE) {
      // The platform cannot report terrain at all — nothing better to offer than the raw position.
      return around;
    }
    // An EMPTY anchor column is not the same thing as a backend that cannot describe blocks, and reading
    // it as one is what dropped players into the void.
    //
    // The contract this code was written against says "no terrain" is Integer.MIN_VALUE. Paper does not
    // say that: getHighestBlockYAt on a column with nothing in it returns minBuildHeight() - 1, a real
    // number, and blockTypeAt below the world floor answers void_air. So a pure-void column matched the
    // heights-only branch below, which lifts to `surfaceY + 1` — for an overworld that is -64, so the
    // Math.max keeps the raw spawn and hands it straight back. The search never ran at all, and every
    // player was teleported into empty space. In a hardcore mode the fall is a death, and in Death Resets
    // that death regenerates the world and does it again.
    //
    // Below the world floor there can BE no block, so this distinguishes the two without guessing.
    boolean anchorEmpty = surfaceY < world.minBuildHeight();
    if (!anchorEmpty && isAir(blockValue(world, worldName, originX, surfaceY, originZ))) {
      // A "highest block" that is air INSIDE the build range means the backend models heights but not
      // blocks: keep the legacy lift-to-surface behaviour (and never pull a raised spawn platform down).
      // Guarded, because a REAL backend reaches this branch too when the column has not been read yet
      // and answers a height of 0 with air above it. A heights-only backend is unaffected: `buried`
      // asks the same blockTypeAt that just said air, so the lift is returned unchanged.
      return lastResort(world, around, worldName, surfaceY);
    }
    // The spawn column first, then outward in rings so the player always lands as close to the spawn
    // area as the terrain allows.
    WorldPosition found = ringSearch(world, around, worldName, originX, originZ, MAX_RADIUS, RING_STEP);
    if (found != null) {
      return found;
    }
    // An empty anchor with nothing in the near rings is a world whose only terrain is somewhere else —
    // a one-chunk column built off the origin, an island the spawn does not sit on. Spend the wide sweep
    // looking for it rather than settling for a position that is known to be thin air.
    if (anchorEmpty) {
      WorldPosition distant =
          ringSearch(world, around, worldName, originX, originZ, VOID_SWEEP_RADIUS, VOID_SWEEP_STEP);
      if (distant != null) {
        return distant;
      }
    }
    // Nothing close by works. Two very different worlds reach here: an all-void one before its island is
    // built (where the lift below is harmless) and OPEN OCEAN — and there the lift is actively wrong,
    // because the "highest block" of an ocean column is the water SURFACE, so lifting onto it drops the
    // player into the sea. That is the "spawned in the middle of the ocean" report. When the column really
    // is water, spend one coarse wide sweep looking for a shore before settling for it.
    if (LIQUIDS.contains(blockValue(world, worldName, originX, surfaceY, originZ))) {
      WorldPosition shore = ringSearch(world, around, worldName, originX, originZ, SHORE_RADIUS, SHORE_STEP);
      if (shore != null) {
        return shore;
      }
    }
    return lastResort(world, around, worldName, surfaceY);
  }

  /**
   * The final fallback, guarded so it cannot hand back a position that is visibly inside terrain.
   *
   * <p>`Math.max(anchorY, surfaceY + 1)` is one of only two expressions in this class that can answer
   * y=1, and it did: with an anchor at or below y≈1 (a world whose spawn record was never written --
   * an experience dimension folder carries no {@code level.dat}, so the pinned land spawn does not
   * survive there) and a reported surface of 0, it returns exactly (x, 1, z). In a normal overworld
   * that is deepslate. The player suffocates, and in Death Resets the death regenerates the world and
   * does it again.</p>
   *
   * <p>So the lift is still taken -- it is right for a heights-only backend and for a void world whose
   * island is not built yet -- but never when the result is provably buried. {@code buried} is the
   * same check {@code ExperienceGame.settleOne} already uses to detect this after the fact.</p>
   */
  private static WorldPosition lastResort(WorldAdapter world, WorldPosition around, String worldName,
      int surfaceY) {
    WorldPosition lifted = withY(around, Math.max(around.coordinateY(), surfaceY + 1));
    if (!buried(world, lifted)) {
      return lifted;
    }
    // Buried. The height reading is demonstrably wrong -- it pointed inside rock -- so stop deriving
    // anything from it and PROBE: walk up the column until two free blocks appear. This is the only
    // branch that does not trust the heightmap, and it exists because the heightmap is exactly what
    // lies when a column has not been read yet.
    int blockX = (int) Math.floor(around.coordinateX());
    int blockZ = (int) Math.floor(around.coordinateZ());
    int from = Math.max(surfaceY, world.minBuildHeight());
    for (int y = from, scanned = 0; scanned < MAX_COLUMN_SCAN; y++, scanned++) {
      if (isFree(world, worldName, blockX, y, blockZ)
          && isFree(world, worldName, blockX, y + 1, blockZ)) {
        return withY(around, y);
      }
    }
    // A solid column for 192 blocks straight up. Nothing sensible is left, but ABOVE it still beats
    // inside it: a fall is survivable and recoverable, suffocation in rock is neither.
    return withY(around, from + MAX_COLUMN_SCAN);
  }

  /**
   * Whether {@code position} is inside solid blocks — a player standing there is suffocating.
   *
   * <p>Exists because terrain can appear AROUND a player who is already in the world: a mode that
   * generates its own map builds it when the match starts, which is after everybody has been teleported
   * in. Lives here rather than in the caller so that what counts as "solid" is decided in exactly one
   * place, the same one the spawn search uses.</p>
   *
   * @param position the player's feet; the block above is checked too, because a player is two tall
   */
  public static boolean buried(WorldAdapter world, WorldPosition position) {
    if (world == null || position == null) {
      return false;
    }
    String worldName = position.worldName() != null ? position.worldName() : world.name();
    int blockX = (int) Math.floor(position.coordinateX());
    int blockY = (int) Math.floor(position.coordinateY());
    int blockZ = (int) Math.floor(position.coordinateZ());
    return !isFree(world, worldName, blockX, blockY, blockZ)
        || !isFree(world, worldName, blockX, blockY + 1, blockZ);
  }

  /**
   * The nearest position to {@code around} a player can occupy — feet and head both free — searched
   * LOCALLY in three dimensions, or {@code null} when the box holds nothing usable.
   *
   * <p>This is the suffocation escape, and it deliberately does NOT consult the heightmap. Consulting
   * the heightmap is exactly what teleported cave players onto rooftops: every other search in this
   * class judges a column through {@code standingY}, which scans DOWNWARDS from
   * {@link WorldAdapter#highestSolidBlockY} and therefore always resolves to the OUTDOOR SURFACE of
   * that column. Fine for "put this player into the world"; wrong for "this player is inside a wall".
   * A player suffocating in a cave was yanked out of the cave and dropped onto the terrain above it,
   * and a player sealed inside a building was put on its roof — from their point of view a rescue that
   * threw away where they were.</p>
   *
   * <p>So this walks outward by Chebyshev shell from the player's ACTUAL block, up to
   * {@link #LOCAL_ESCAPE_RADIUS}, and answers with the closest free pocket it finds: a cave player
   * stays in the cave, a walled-in player steps into the next room. Free is decided by the same
   * {@code isFree} predicate the rest of the class uses, so the answer is by construction never
   * {@link #buried}, and liquids are rejected — a lava pocket is not a rescue.</p>
   *
   * <p>Shells are Chebyshev but candidates are ranked by true EUCLIDEAN distance, and the two do not
   * agree: a candidate on shell {@code s} can be as far as {@code s * sqrt(3)}, so the first shell that
   * yields footing does not necessarily hold the nearest spot there is — a diagonal on shell 2 sits 3.46
   * away while a straight-line neighbour on shell 3 sits 3.0. There IS a lower bound, though: distance is
   * measured to the MIDDLE of a candidate block from a player who may be standing anywhere inside their
   * own — including at a fractional Y, on a slab or mid-fall — so nothing on shell {@code s} is ever
   * nearer than {@code s - 1}. The search keeps going while a later shell could still beat the best
   * footing found so far and stops the moment none can. The answer is then genuinely the nearest one,
   * and the worst case is the whole 17x17x17 box this class already budgets for.</p>
   *
   * <p>A spot with SOLID ground under it wins over one without, so the player does not simply fall out
   * of the pocket they were just placed in — and solid has to mean solid. {@code isFree} answers false
   * for liquids as well as blocks, so reading the footing through it alone seated the rescued player on
   * the surface of a lava lake. Lava underfoot is the death this whole rescue exists to prevent, so such
   * a candidate is discarded outright; water underfoot counts as NO support, so honest ground always
   * outranks a water surface.</p>
   *
   * <p>A floating spot is only answered when the whole box offers nothing better, and only once the
   * column beneath it is known to hold ground. The box is three dimensional, so in a SkyBlock-shaped
   * world every free block beside the island is open void: "nearest free" there is a fall out of the
   * world, which is the catastrophe the rest of this file is written around. When nothing holds the
   * candidate up this answers {@code null} instead. {@code null} means "nothing local to offer" and the
   * caller falls back to its own entry position, which lands on real ground.</p>
   *
   * @param around the player's feet
   */
  public static WorldPosition nearestFree(WorldAdapter world, WorldPosition around) {
    if (world == null || around == null) {
      return around;
    }
    String worldName = around.worldName() != null ? around.worldName() : world.name();
    int originX = (int) Math.floor(around.coordinateX());
    int originY = (int) Math.floor(around.coordinateY());
    int originZ = (int) Math.floor(around.coordinateZ());
    int floor = world.minBuildHeight();
    int ceiling = floor + 384;

    WorldPosition bestSupported = null;
    double bestSupportedDistSq = Double.MAX_VALUE;

    WorldPosition bestFloating = null;
    double bestFloatingDistSq = Double.MAX_VALUE;

    for (int shell = 0; shell <= LOCAL_ESCAPE_RADIUS; shell++) {
      if (bestSupported != null && shell > 1) {
        double lowerBound = (shell - 1) * (shell - 1);
        if (lowerBound >= bestSupportedDistSq) {
          break;
        }
      }

      for (int dx = -shell; dx <= shell; dx++) {
        for (int dy = -shell; dy <= shell; dy++) {
          for (int dz = -shell; dz <= shell; dz++) {
            if (shell > 0 && Math.abs(dx) != shell && Math.abs(dy) != shell && Math.abs(dz) != shell) {
              continue;
            }
            int x = originX + dx;
            int y = originY + dy;
            int z = originZ + dz;

            if (y < floor || y + 1 > ceiling) {
              continue;
            }

            if (!isFree(world, worldName, x, y, z) || !isFree(world, worldName, x, y + 1, z)) {
              continue;
            }

            String underValue = blockValue(world, worldName, x, y - 1, z);
            if ("lava".equals(underValue) || "flowing_lava".equals(underValue)) {
              continue;
            }

            boolean isSolidGround = !LIQUIDS.contains(underValue) && !isPassable(underValue);
            double targetCenterX = x + 0.5;
            double targetCenterY = y;
            double targetCenterZ = z + 0.5;
            double distSq = (targetCenterX - around.coordinateX()) * (targetCenterX - around.coordinateX())
                + (targetCenterY - around.coordinateY()) * (targetCenterY - around.coordinateY())
                + (targetCenterZ - around.coordinateZ()) * (targetCenterZ - around.coordinateZ());

            WorldPosition candidate = centred(around, worldName, x, y, z);

            if (isSolidGround) {
              if (distSq < bestSupportedDistSq) {
                bestSupportedDistSq = distSq;
                bestSupported = candidate;
              }
            } else {
              if (distSq < bestFloatingDistSq && standsOverGround(world, worldName, candidate, floor)) {
                bestFloatingDistSq = distSq;
                bestFloating = candidate;
              }
            }
          }
        }
      }
    }

    if (bestSupported != null) {
      return bestSupported;
    }
    return bestFloating;
  }

  /**
   * Whether the column under {@code candidate} contains ground to land on, scanned downwards and bounded
   * by both the world floor and {@link #MAX_COLUMN_SCAN} so an empty world cannot spin.
   *
   * <p>Liquids do not count: a lava sea is not somewhere to fall into and a water surface is not ground.
   * The scan walks THROUGH them, because what matters here is only whether the world continues below the
   * candidate at all — a fall is survivable and recoverable, leaving the world is neither.</p>
   */
  private static boolean standsOverGround(WorldAdapter world, String worldName, WorldPosition candidate,
      int floorY) {
    if (candidate == null) {
      return false;
    }
    int blockX = (int) Math.floor(candidate.coordinateX());
    int blockZ = (int) Math.floor(candidate.coordinateZ());
    int startY = (int) Math.floor(candidate.coordinateY()) - 1;
    for (int y = startY, scanned = 0; y >= floorY && scanned < MAX_COLUMN_SCAN; y--, scanned++) {
      String value = blockValue(world, worldName, blockX, y, blockZ);
      if (!LIQUIDS.contains(value) && !isPassable(value)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A candidate as a position: centred in its block, which keeps a player off the seams between blocks
   * inside a tight cave, and carrying the facing they already had.
   */
  private static WorldPosition centred(WorldPosition around, String worldName, int blockX, int blockY,
      int blockZ) {
    return new WorldPosition(worldName, blockX + 0.5, blockY, blockZ + 0.5, around.yaw(), around.pitch());
  }

  /** Probes outward in rings, returning the first column a player can stand in, or null. */
  private static WorldPosition ringSearch(WorldAdapter world, WorldPosition around, String worldName,
      int originX, int originZ, int maxRadius, int step) {
    for (int radius = 0; radius <= maxRadius; radius += step) {
      for (int offsetX = -radius; offsetX <= radius; offsetX += step) {
        for (int offsetZ = -radius; offsetZ <= radius; offsetZ += step) {
          if (radius > 0 && Math.abs(offsetX) != radius && Math.abs(offsetZ) != radius) {
            continue; // interior of the ring — already probed by a smaller radius
          }
          int blockX = originX + offsetX;
          int blockZ = originZ + offsetZ;
          int standY = standingY(world, worldName, blockX, blockZ);
          if (standY != Integer.MIN_VALUE) {
            return new WorldPosition(worldName, blockX + 0.5, standY, blockZ + 0.5,
                around.yaw(), around.pitch());
          }
        }
      }
    }
    return null;
  }

  /**
   * The Y a player would STAND at in this column (the block above solid ground), or
   * {@link Integer#MIN_VALUE} when the column has no usable spot — including when it is submerged.
   */
  private static int standingY(WorldAdapter world, String worldName, int blockX, int blockZ) {
    int top = world.highestSolidBlockY(worldName, blockX, blockZ);
    if (top == Integer.MIN_VALUE) {
      return Integer.MIN_VALUE;
    }
    // The Nether's "highest block" is the bedrock roof; start below it so nobody is seated on the roof.
    int startY = world.isNether() ? Math.min(top, NETHER_SCAN_TOP) : top;
    int floor = world.minBuildHeight();
    int scanned = 0;
    // `>= floor`, not `> floor`. The floor block is real ground a player can stand on -- it is the
    // bedrock layer -- and excluding it meant a column whose only solid block sits at the world floor
    // resolved to "no usable spot". Every such column dragged the whole search into the last-resort
    // lift below, which is one of the two expressions that can answer y=1.
    for (int y = startY; y >= floor && scanned < MAX_COLUMN_SCAN; y--, scanned++) {
      String value = blockValue(world, worldName, blockX, y, blockZ);
      if (LIQUIDS.contains(value)) {
        return Integer.MIN_VALUE; // water/lava above the ground — this column drowns the player
      }
      if (isPassable(value)) {
        continue;
      }
      // Solid ground: usable only with two free blocks of head room above it.
      return isFree(world, worldName, blockX, y + 1, blockZ) && isFree(world, worldName, blockX, y + 2, blockZ)
          ? y + 1 : Integer.MIN_VALUE;
    }
    return Integer.MIN_VALUE;
  }

  private static boolean isFree(WorldAdapter world, String worldName, int blockX, int blockY, int blockZ) {
    String value = blockValue(world, worldName, blockX, blockY, blockZ);
    return !LIQUIDS.contains(value) && isPassable(value);
  }

  private static boolean isPassable(String value) {
    return PASSABLE.contains(value)
        || value.endsWith("_sapling") || value.endsWith("_carpet") || value.endsWith("_button");
  }

  private static boolean isAir(String value) {
    return "air".equals(value) || "cave_air".equals(value) || "void_air".equals(value);
  }

  private static String blockValue(WorldAdapter world, String worldName, int blockX, int blockY, int blockZ) {
    ItemKey key = world.blockTypeAt(new BlockPosition(worldName, blockX, blockY, blockZ));
    return key == null ? "air" : key.value().toLowerCase(Locale.ROOT);
  }

  private static WorldPosition withY(WorldPosition position, double blockY) {
    return new WorldPosition(position.worldName(), position.coordinateX(), blockY, position.coordinateZ(),
        position.yaw(), position.pitch());
  }
}
