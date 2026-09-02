package com.sexidium.core.world;

import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldTerrain;

/**
 * Declarative settings a backend applies to a managed world right after it is created or loaded. Lets
 * the platform-agnostic control layer express world configuration (border, PvP, difficulty, auto-save,
 * time-of-day) once; each backend translates it to its native API. This is what closes the historical
 * drift where, e.g., PvP and difficulty were applied to some temp worlds but not others.
 *
 * @param borderSize        world-border diameter in blocks (the border is centred on the world spawn);
 *                          {@code <= 0} leaves the border untouched
 * @param borderWarning     border warning distance in blocks
 * @param borderDamage      border damage per block past the edge
 * @param pvp               whether player-vs-player damage is allowed (minigames need this on)
 * @param difficulty        world difficulty name (e.g. {@code NORMAL}); {@code null}/blank keeps default
 * @param autoSave          whether the world auto-saves (off for disposable temp, on for persistent)
 * @param lockTime          freeze the day/night cycle at {@link #timeOfDay}
 * @param timeOfDay         time of day in ticks to pin when {@link #lockTime} is set
 * @param blockMobSpawn     cancel natural mob spawning (lobby keeps this on)
 * @param voidWorld         generate an empty VOID world (no natural terrain) instead of normal terrain —
 *                          the backend swaps in an empty chunk generator; used by the SkyBlock-style
 *                          experiences, which place their own island into the void
 * @param voidNether        also generate the linked NETHER sibling as void (for a SkyBlock whose Nether
 *                          mirrors its Overworld); the End sibling is unaffected
 * @param terrain           which vanilla world-generation preset the terrain uses (Superflat, Large
 *                          Biomes, Amplified, …); ignored when {@link #voidWorld} is set, since a void
 *                          world has no natural terrain to shape
 * @param dimension         which dimension the world IS. Almost everything asks for an Overworld and
 *                          reaches the Nether/End through linked siblings; the world pool is the
 *                          exception, warming each dimension in its own right
 * @param hardcore          whether the world is HARDCORE — hardened hearts on the client and a difficulty
 *                          pinned to hard. Applied when the world is created/loaded, before anyone can be
 *                          teleported in, because that is when the client is told
 */
public record WorldSettings(
    double borderSize,
    int borderWarning,
    double borderDamage,
    boolean pvp,
    String difficulty,
    boolean autoSave,
    boolean lockTime,
    long timeOfDay,
    boolean blockMobSpawn,
    boolean voidWorld,
    boolean voidNether,
    WorldTerrain terrain,
    WorldDimension dimension,
    boolean hardcore
) {
  public WorldSettings {
    terrain = terrain == null ? WorldTerrain.NORMAL : terrain;
    dimension = dimension == null ? WorldDimension.OVERWORLD : dimension;
  }

  /** Settings in a named dimension, not hardcore. */
  public WorldSettings(double borderSize, int borderWarning, double borderDamage, boolean pvp, String difficulty,
      boolean autoSave, boolean lockTime, long timeOfDay, boolean blockMobSpawn, boolean voidWorld,
      boolean voidNether, WorldTerrain terrain, WorldDimension dimension) {
    this(borderSize, borderWarning, borderDamage, pvp, difficulty, autoSave, lockTime, timeOfDay, blockMobSpawn,
        voidWorld, voidNether, terrain, dimension, false);
  }

  /** Overworld settings (the shape every caller but the pool builds). */
  public WorldSettings(double borderSize, int borderWarning, double borderDamage, boolean pvp, String difficulty,
      boolean autoSave, boolean lockTime, long timeOfDay, boolean blockMobSpawn, boolean voidWorld,
      boolean voidNether, WorldTerrain terrain) {
    this(borderSize, borderWarning, borderDamage, pvp, difficulty, autoSave, lockTime, timeOfDay, blockMobSpawn,
        voidWorld, voidNether, terrain, WorldDimension.OVERWORLD);
  }

  /** A copy of these settings with void (empty-terrain) generation turned on ({@code voidNether} too when asked). */
  public WorldSettings asVoid(boolean voidNether) {
    return new WorldSettings(borderSize, borderWarning, borderDamage, pvp, difficulty, autoSave, lockTime,
        timeOfDay, blockMobSpawn, true, voidNether, terrain, dimension, hardcore);
  }

  /** A copy of these settings generated with the given vanilla terrain preset. */
  public WorldSettings withTerrain(WorldTerrain terrain) {
    return new WorldSettings(borderSize, borderWarning, borderDamage, pvp, difficulty, autoSave, lockTime,
        timeOfDay, blockMobSpawn, voidWorld, voidNether, terrain, dimension, hardcore);
  }

  /** A copy of these settings carrying a whole {@link WorldGeneration} request (void flags + preset + dimension). */
  public WorldSettings withGeneration(WorldGeneration generation) {
    if (generation == null || generation.isDefault()) {
      return this;
    }
    return new WorldSettings(borderSize, borderWarning, borderDamage, pvp, difficulty, autoSave, lockTime,
        timeOfDay, blockMobSpawn, generation.voidWorld(), generation.voidNether(), generation.terrain(),
        generation.dimension(), generation.hardcore());
  }
  /** Settings for a disposable game world: border, PvP on, normal difficulty; auto-save off by default. */
  public static WorldSettings forGameWorld(double borderSize, int borderWarning, double borderDamage,
      boolean pvp, String difficulty) {
    return forGameWorld(borderSize, borderWarning, borderDamage, pvp, difficulty, false);
  }

  /**
   * Settings for a disposable game world with an explicit {@code autoSave}. Enabling auto-save lets the
   * platform flush the temp world's chunks to disk incrementally during the match (so its data lands in
   * {@code world/dimensions/<temp-ns>/<short>/} while play is in progress, not only on shutdown), which
   * also keeps a reconnectable match's world current on disk.
   */
  public static WorldSettings forGameWorld(double borderSize, int borderWarning, double borderDamage,
      boolean pvp, String difficulty, boolean autoSave) {
    return new WorldSettings(borderSize, borderWarning, borderDamage, pvp, difficulty, autoSave, false, 6000L, false, false, false, WorldTerrain.NORMAL);
  }

  /** Settings for a persistent experience world: same as a game world but auto-save ON. */
  public static WorldSettings forPersistentWorld(double borderSize, int borderWarning, double borderDamage,
      boolean pvp, String difficulty) {
    return new WorldSettings(borderSize, borderWarning, borderDamage, pvp, difficulty, true, false, 6000L, false, false, false, WorldTerrain.NORMAL);
  }
}
