package com.sexidium.core.world;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * Procedurally builds the structured arenas the competitive minigames need (a spread fighting platform,
 * a pair of opposing team bases) directly into a freshly generated random match world, so a mode that is
 * NOT given a hand-built map still gets a fair, playable layout instead of every player piling onto the
 * raw world spawn.
 *
 * <p>This is the minigame analogue of {@link ExperienceWorldGen}: where experiences guarantee a reachable
 * End in their generated world, minigames guarantee a usable arena in theirs. It is platform-agnostic —
 * it only drives {@link WorldAdapter#setBlock}, {@link WorldAdapter#highestSolidBlockY} and the build-height
 * bounds, so the identical generation runs on every platform. Coordinates are anchored on the world's
 * surface at/around its spawn, and every structure is levelled onto a flat platform so spawns are never
 * buried, floating, or underwater.</p>
 */
public final class ArenaGen {
  private ArenaGen() {
  }

  /** The two opposing team bases generated for a base-destruction match (TNT War). */
  public record TeamArena(
      BlockPosition redCornerA, BlockPosition redCornerB, WorldPosition redSpawn,
      BlockPosition blueCornerA, BlockPosition blueCornerB, WorldPosition blueSpawn) {
  }

  /**
   * Builds a flat circular fighting platform centred on {@code center} and returns {@code count} spawn
   * positions evenly spaced on a ring just inside its rim, each facing the centre. The platform is
   * levelled onto the surface, cleared of {@code clearHeight} blocks of headroom, and ringed by a short
   * wall so a knockback does not instantly throw a fighter off the edge.
   *
   * @param world       the (already loaded) match world
   * @param center      where to centre the arena (its X/Z are used; Y is resolved from the terrain)
   * @param count       how many spawn points to return (one per fighter; clamped to at least 1)
   * @param radius      platform radius in blocks (clamped to a sane 4..64)
   * @param floorBlock  block the platform floor is paved with
   * @param wallBlock   block the rim wall is built from ({@code null} skips the wall)
   * @param wallHeight  rim-wall height in blocks
   * @param clearHeight headroom (in blocks) cleared above the floor
   */
  public static List<WorldPosition> ringArena(WorldAdapter world, WorldPosition center, int count, int radius,
      ItemKey floorBlock, ItemKey wallBlock, int wallHeight, int clearHeight) {
    List<WorldPosition> spawns = new ArrayList<>();
    if (world == null || center == null) {
      return spawns;
    }
    int fighters = Math.max(1, count);
    int rim = clamp(radius, 4, 64);
    String worldName = world.name();
    int centerX = (int) Math.floor(center.coordinateX());
    int centerZ = (int) Math.floor(center.coordinateZ());
    int floorY = surfaceY(world, centerX, centerZ, (int) Math.floor(center.coordinateY()));
    int headroom = clamp(clearHeight, 3, 32);
    int top = Math.min(world.maxBuildHeight() - 1, floorY + headroom);
    ItemKey air = ItemKey.minecraft("air");
    long rimSquared = (long) rim * rim;
    for (int dx = -rim; dx <= rim; dx++) {
      for (int dz = -rim; dz <= rim; dz++) {
        if ((long) dx * dx + (long) dz * dz > rimSquared) {
          continue;
        }
        int x = centerX + dx;
        int z = centerZ + dz;
        place(world, worldName, x, floorY, z, floorBlock);
        for (int y = floorY + 1; y <= top; y++) {
          place(world, worldName, x, y, z, air);
        }
      }
    }
    if (wallBlock != null && wallHeight > 0) {
      buildWall(world, worldName, centerX, centerZ, rim, floorY, clamp(wallHeight, 1, 16), wallBlock);
    }
    double spawnRadius = Math.max(1.5, rim - 1.5);
    for (int i = 0; i < fighters; i++) {
      double angle = (2.0 * Math.PI * i) / fighters;
      double sx = centerX + 0.5 + spawnRadius * Math.cos(angle);
      double sz = centerZ + 0.5 + spawnRadius * Math.sin(angle);
      float yaw = facingYaw(sx, sz, centerX + 0.5, centerZ + 0.5);
      spawns.add(new WorldPosition(worldName, sx, floorY + 1, sz, yaw, 0f));
    }
    return spawns;
  }

  /**
   * Builds two symmetric, opposing team bases (red and blue) separated along the X axis from
   * {@code center} and returns their destructible-base regions and on-platform spawns. Each base is a
   * solid cuboid of {@code baseBlock} (its full volume is the destruction target) standing on a cleared,
   * levelled platform; the team's spawn sits on the platform facing the enemy.
   *
   * @param world          the (already loaded) match world
   * @param center         midpoint between the two bases (its X/Z are used)
   * @param separation     distance in blocks between the two bases' centres (clamped to 24..400)
   * @param baseWidth      side length (X/Z) of each base cuboid (clamped to 3..31, forced odd)
   * @param baseHeight     height of each base cuboid (clamped to 2..32)
   * @param baseBlock      block the destructible base is built from
   * @param platformBlock  block the levelling platform under each base is paved with
   */
  public static TeamArena teamBases(WorldAdapter world, WorldPosition center, int separation,
      int baseWidth, int baseHeight, ItemKey baseBlock, ItemKey platformBlock) {
    if (world == null || center == null) {
      return null;
    }
    int gap = clamp(separation, 24, 400);
    int width = clamp(baseWidth | 1, 3, 31);
    int height = clamp(baseHeight, 2, 32);
    int half = width / 2;
    int centerX = (int) Math.floor(center.coordinateX());
    int centerZ = (int) Math.floor(center.coordinateZ());
    int fallbackY = (int) Math.floor(center.coordinateY());
    int redCenterX = centerX + gap / 2;
    int blueCenterX = centerX - gap / 2;

    BaseBuild red = buildBase(world, redCenterX, centerZ, half, height, baseBlock, platformBlock, fallbackY, 90f);
    BaseBuild blue = buildBase(world, blueCenterX, centerZ, half, height, baseBlock, platformBlock, fallbackY, -90f);
    return new TeamArena(red.cornerA, red.cornerB, red.spawn, blue.cornerA, blue.cornerB, blue.spawn);
  }

  private record BaseBuild(BlockPosition cornerA, BlockPosition cornerB, WorldPosition spawn) {
  }

  private static BaseBuild buildBase(WorldAdapter world, int cx, int cz, int half, int height,
      ItemKey baseBlock, ItemKey platformBlock, int fallbackY, float spawnYaw) {
    String worldName = world.name();
    int groundY = surfaceY(world, cx, cz, fallbackY);
    int platformPad = half + 3;
    ItemKey air = ItemKey.minecraft("air");
    // A flat platform two blocks wider than the base, cleared above so the cuboid is never half-buried.
    for (int dx = -platformPad; dx <= platformPad; dx++) {
      for (int dz = -platformPad; dz <= platformPad; dz++) {
        place(world, worldName, cx + dx, groundY, cz + dz, platformBlock);
        for (int y = groundY + 1; y <= Math.min(world.maxBuildHeight() - 1, groundY + height + 3); y++) {
          place(world, worldName, cx + dx, y, cz + dz, air);
        }
      }
    }
    int baseMinY = groundY + 1;
    int baseMaxY = Math.min(world.maxBuildHeight() - 1, groundY + height);
    for (int dx = -half; dx <= half; dx++) {
      for (int dz = -half; dz <= half; dz++) {
        for (int y = baseMinY; y <= baseMaxY; y++) {
          place(world, worldName, cx + dx, y, cz + dz, baseBlock);
        }
      }
    }
    BlockPosition cornerA = new BlockPosition(worldName, cx - half, baseMinY, cz - half);
    BlockPosition cornerB = new BlockPosition(worldName, cx + half, baseMaxY, cz + half);
    // Spawn on the platform on the enemy-facing side of the base (between the base and the centre line),
    // so a team starts in front of its own base looking toward the war.
    int spawnX = spawnYaw == 90f ? cx + half + 2 : cx - half - 2;
    WorldPosition spawn = new WorldPosition(worldName, spawnX + 0.5, groundY + 1, cz + 0.5, spawnYaw, 0f);
    return new BaseBuild(cornerA, cornerB, spawn);
  }

  private static void buildWall(WorldAdapter world, String worldName, int centerX, int centerZ, int rim,
      int floorY, int wallHeight, ItemKey wallBlock) {
    long inner = (long) (rim - 1) * (rim - 1);
    long outer = (long) rim * rim;
    int top = Math.min(world.maxBuildHeight() - 1, floorY + wallHeight);
    for (int dx = -rim; dx <= rim; dx++) {
      for (int dz = -rim; dz <= rim; dz++) {
        long distSquared = (long) dx * dx + (long) dz * dz;
        if (distSquared <= outer && distSquared > inner) {
          for (int y = floorY + 1; y <= top; y++) {
            place(world, worldName, centerX + dx, y, centerZ + dz, wallBlock);
          }
        }
      }
    }
  }

  /** Highest solid block at the column, or {@code fallbackY} when the platform cannot report a surface. */
  private static int surfaceY(WorldAdapter world, int x, int z, int fallbackY) {
    int surface = world.highestSolidBlockY(world.name(), x, z);
    if (surface == Integer.MIN_VALUE) {
      return fallbackY;
    }
    return clamp(surface, world.minBuildHeight() + 1, world.maxBuildHeight() - 8);
  }

  private static void place(WorldAdapter world, String worldName, int x, int y, int z, ItemKey block) {
    if (block == null || y < world.minBuildHeight() || y >= world.maxBuildHeight()) {
      return;
    }
    world.setBlock(new BlockPosition(worldName, x, y, z), block);
  }

  /** Yaw (degrees) that makes an entity at {@code (x,z)} look toward {@code (targetX,targetZ)}. */
  private static float facingYaw(double x, double z, double targetX, double targetZ) {
    double dx = targetX - x;
    double dz = targetZ - z;
    return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
