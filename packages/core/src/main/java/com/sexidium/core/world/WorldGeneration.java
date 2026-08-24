package com.sexidium.core.world;

import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldTerrain;

/**
 * How a world should be GENERATED, as one value instead of a growing tail of boolean parameters. Every
 * field here only matters the first time a world is created — a reload keeps whatever is already on disk.
 *
 * @param voidWorld  generate empty VOID (no natural terrain) so a challenge can build its own map
 * @param voidNether make the linked NETHER sibling void too (a SkyBlock whose Nether mirrors its Overworld)
 * @param terrain    which vanilla generation preset the terrain uses; ignored when {@code voidWorld}
 * @param dimension  which dimension this world IS (Overworld / Nether / End). Experiences ask for an
 *                   Overworld and get their Nether and End as linked siblings, but the world pool warms
 *                   all three independently, so the request has to be able to name one
 * @param hardcore   create the world in HARDCORE. Here rather than in the runtime settings because the
 *                   client is told a world is hardcore when it is SENT the world — so the flag has to be
 *                   on it before anyone is teleported in, not applied to a world people are already
 *                   standing in
 */
public record WorldGeneration(boolean voidWorld, boolean voidNether, WorldTerrain terrain,
    WorldDimension dimension, boolean hardcore) {
  /** Ordinary terrain, no void, no preset — what every world gets unless something asks otherwise. */
  public static final WorldGeneration DEFAULT =
      new WorldGeneration(false, false, WorldTerrain.NORMAL, WorldDimension.OVERWORLD, false);

  public WorldGeneration {
    terrain = terrain == null ? WorldTerrain.NORMAL : terrain;
    dimension = dimension == null ? WorldDimension.OVERWORLD : dimension;
  }

  /** An Overworld generation request (the common case — every experience starts from one). */
  public WorldGeneration(boolean voidWorld, boolean voidNether, WorldTerrain terrain) {
    this(voidWorld, voidNether, terrain, WorldDimension.OVERWORLD, false);
  }

  /** A request in a named dimension, not hardcore (what the world pool builds). */
  public WorldGeneration(boolean voidWorld, boolean voidNether, WorldTerrain terrain, WorldDimension dimension) {
    this(voidWorld, voidNether, terrain, dimension, false);
  }

  /** This request in HARDCORE (an experience whose owner chose it). */
  public WorldGeneration asHardcore(boolean hardcore) {
    return new WorldGeneration(voidWorld, voidNether, terrain, dimension, hardcore);
  }

  /** A void/void-nether request on ordinary terrain (the SkyBlock-style shorthand). */
  public static WorldGeneration ofVoid(boolean voidWorld, boolean voidNether) {
    return new WorldGeneration(voidWorld, voidNether, WorldTerrain.NORMAL);
  }

  /** Plain terrain generated with the given vanilla preset. */
  public static WorldGeneration ofTerrain(WorldTerrain terrain) {
    return new WorldGeneration(false, false, terrain);
  }

  /** This request pointed at another dimension (what the pool builds its Nether/End entries from). */
  public WorldGeneration inDimension(WorldDimension target) {
    return new WorldGeneration(voidWorld, voidNether, terrain, target, hardcore);
  }

  /** Whether this is just "generate a normal Overworld" (nothing for a backend to special-case). */
  public boolean isDefault() {
    return !voidWorld && !voidNether && terrain == WorldTerrain.NORMAL
        && dimension == WorldDimension.OVERWORLD && !hardcore;
  }
}
