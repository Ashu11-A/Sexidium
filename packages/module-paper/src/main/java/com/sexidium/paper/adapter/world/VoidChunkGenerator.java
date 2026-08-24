package com.sexidium.paper.adapter.world;

import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.List;
import java.util.Random;

/**
 * An empty-world chunk generator: it generates no terrain, surface, caves, decorations, structures or
 * mobs, so a world created with it is pure void. Used for the SkyBlock-style Random-Layers experience,
 * which then places its own starting island and layers into the void. A single-biome provider (plains)
 * keeps the dimension valid so {@code createWorld} succeeds instead of failing on a missing biome source.
 */
public final class VoidChunkGenerator extends ChunkGenerator {
  private final Biome biome;

  /** A void world with a plains biome (overworld default). */
  public VoidChunkGenerator() {
    this(Biome.PLAINS);
  }

  /** A void world reporting {@code biome} everywhere (e.g. {@link Biome#NETHER_WASTES} for a void Nether). */
  public VoidChunkGenerator(Biome biome) {
    this.biome = biome == null ? Biome.PLAINS : biome;
  }

  @Override
  public boolean shouldGenerateNoise() {
    return false;
  }

  @Override
  public boolean shouldGenerateSurface() {
    return false;
  }

  @Override
  public boolean shouldGenerateCaves() {
    return false;
  }

  @Override
  public boolean shouldGenerateDecorations() {
    return false;
  }

  @Override
  public boolean shouldGenerateMobs() {
    return false;
  }

  @Override
  public boolean shouldGenerateStructures() {
    return false;
  }

  @Override
  public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) {
    // No solid ground anywhere: report the world floor as the base height.
    return worldInfo.getMinHeight();
  }

  @Override
  public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
    return new SingleBiomeProvider(biome);
  }

  /** Reports the same biome for every column, so the void world still has a valid biome source. */
  private static final class SingleBiomeProvider extends BiomeProvider {
    private final Biome biome;

    SingleBiomeProvider(Biome biome) {
      this.biome = biome;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
      return biome;
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
      return List.of(biome);
    }
  }
}
