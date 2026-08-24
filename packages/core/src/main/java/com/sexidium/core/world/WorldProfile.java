package com.sexidium.core.world;

import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldTerrain;

/**
 * The <b>shape</b> of a world, independent of who is using it or what it is called: which dimension it
 * is, whether its terrain is void or natural, and which vanilla generation preset shaped it.
 *
 * <p>This is the pool's key. Two worlds with the same profile are interchangeable — a warm Nether kept
 * ready at boot is exactly as good as one generated on demand, so it can be handed to whichever
 * experience or minigame asks next. Anything that differs between two worlds of the same profile (the
 * seed, the name, the folder) is decided at hand-over, not here.</p>
 *
 * <p>Deliberately NOT part of a profile: the border, PvP, difficulty and the rest of {@link
 * WorldSettings}. Those are applied to a world after it is acquired and can be changed freely, so
 * pooling by them would fragment the pool for no benefit.</p>
 */
public record WorldProfile(WorldDimension dimension, boolean voidTerrain, WorldTerrain terrain) {
  /** The three plain profiles the pool keeps warm by default — one per dimension. */
  public static final WorldProfile OVERWORLD = new WorldProfile(WorldDimension.OVERWORLD, false, WorldTerrain.NORMAL);
  public static final WorldProfile NETHER = new WorldProfile(WorldDimension.NETHER, false, WorldTerrain.NORMAL);
  public static final WorldProfile END = new WorldProfile(WorldDimension.END, false, WorldTerrain.NORMAL);

  public WorldProfile {
    dimension = dimension == null ? WorldDimension.OVERWORLD : dimension;
    // A void world has no natural terrain for a preset to shape, so the preset is not part of its identity.
    terrain = voidTerrain || terrain == null ? WorldTerrain.NORMAL : terrain;
  }

  /** The profile a generation request produces. */
  public static WorldProfile of(WorldGeneration generation) {
    return generation == null ? OVERWORLD
        : new WorldProfile(generation.dimension(), generation.voidWorld(), generation.terrain());
  }

  /** The profile the world described by these settings has. */
  public static WorldProfile of(WorldSettings settings) {
    return settings == null ? OVERWORLD
        : new WorldProfile(settings.dimension(), settings.voidWorld(), settings.terrain());
  }

  /** The plain (natural terrain, no preset) profile for a dimension. */
  public static WorldProfile of(WorldDimension dimension) {
    return new WorldProfile(dimension, false, WorldTerrain.NORMAL);
  }

  /** The generation request that produces a world of this profile. */
  public WorldGeneration generation() {
    return new WorldGeneration(voidTerrain, false, terrain, dimension);
  }

  /**
   * A stable, human-readable id — the pool's map key and the config key its warm size is read from
   * ({@code overworld}, {@code nether}, {@code end}, {@code void}, {@code nether-void},
   * {@code overworld-superflat}, …).
   */
  public String id() {
    StringBuilder builder = new StringBuilder(dimension.name().toLowerCase(java.util.Locale.ROOT));
    if (voidTerrain) {
      builder.append("-void");
    } else if (terrain != WorldTerrain.NORMAL) {
      builder.append('-').append(terrain.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    }
    return builder.toString();
  }

  /** Whether this is a plain natural-terrain world of its dimension (what the pool warms by default). */
  public boolean isPlain() {
    return !voidTerrain && terrain == WorldTerrain.NORMAL;
  }

  @Override
  public String toString() {
    return id();
  }
}
