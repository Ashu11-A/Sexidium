package com.sexidium.core.world;

import com.sexidium.core.platform.model.WorldTerrain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the generation request that reaches a world backend: the vanilla terrain preset a player picked
 * must survive the trip into {@link WorldSettings}, and must never quietly override the void generation a
 * SkyBlock-style experience depends on.
 */
class WorldGenerationTest {
  private static final WorldSettings BASE =
      WorldSettings.forPersistentWorld(750.0, 8, 0.2, true, "NORMAL");

  @Test
  void aWorldIsOrdinaryUnlessSomethingAsksOtherwise() {
    assertTrue(WorldGeneration.DEFAULT.isDefault());
    assertEquals(WorldTerrain.NORMAL, WorldGeneration.DEFAULT.terrain());
    assertFalse(WorldGeneration.DEFAULT.voidWorld());
    assertEquals(WorldTerrain.NORMAL, BASE.terrain(), "settings default to normal terrain");
    // A default request must not even rebuild the settings — nothing to change.
    assertSame(BASE, BASE.withGeneration(WorldGeneration.DEFAULT));
    assertSame(BASE, BASE.withGeneration(null));
  }

  @Test
  void aTerrainPresetReachesTheWorldSettings() {
    WorldSettings flat = BASE.withGeneration(WorldGeneration.ofTerrain(WorldTerrain.SUPERFLAT));
    assertEquals(WorldTerrain.SUPERFLAT, flat.terrain());
    assertFalse(flat.voidWorld(), "a preset is normal generation, not void");
    // Everything else about the world is untouched.
    assertEquals(BASE.borderSize(), flat.borderSize());
    assertEquals(BASE.autoSave(), flat.autoSave());
    assertEquals(BASE.pvp(), flat.pvp());
  }

  @Test
  void voidAndPresetAreIndependent() {
    WorldGeneration skyblock = WorldGeneration.ofVoid(true, true);
    WorldSettings settings = BASE.withGeneration(skyblock);
    assertTrue(settings.voidWorld());
    assertTrue(settings.voidNether());
    assertEquals(WorldTerrain.NORMAL, settings.terrain(), "a void world has no terrain to shape");
    // asVoid keeps whatever preset was already chosen rather than resetting it.
    assertEquals(WorldTerrain.AMPLIFIED, BASE.withTerrain(WorldTerrain.AMPLIFIED).asVoid(false).terrain());
    assertFalse(WorldGeneration.ofTerrain(WorldTerrain.AMPLIFIED).isDefault());
  }

  @Test
  void terrainNamesParseLeniently() {
    assertEquals(WorldTerrain.SUPERFLAT, WorldTerrain.of("SUPERFLAT"));
    assertEquals(WorldTerrain.SUPERFLAT, WorldTerrain.of("flat"));
    assertEquals(WorldTerrain.LARGE_BIOMES, WorldTerrain.of("large_biomes"));
    assertEquals(WorldTerrain.LARGE_BIOMES, WorldTerrain.of("large-biomes"));
    assertEquals(WorldTerrain.AMPLIFIED, WorldTerrain.of(" amplified "));
    assertEquals(WorldTerrain.NORMAL, WorldTerrain.of("nonsense"));
    assertEquals(WorldTerrain.NORMAL, WorldTerrain.of(null));
  }
}
