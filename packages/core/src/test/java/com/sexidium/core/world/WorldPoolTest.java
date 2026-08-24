package com.sexidium.core.world;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldTerrain;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the warm world pool: that a world is only handed to a request of its own shape, that all three
 * dimensions are pooled independently (an experience needs an Overworld AND a Nether AND an End), and
 * that the pool keeps refilling while worlds are in use.
 */
class WorldPoolTest {

  @Test
  void aWorldIsOnlyHandedToARequestOfItsOwnShape() {
    WorldPool pool = new WorldPool();
    pool.target(WorldProfile.OVERWORLD, 1);
    pool.target(WorldProfile.NETHER, 1);
    pool.offer(WorldProfile.NETHER, handle("nether-1"));

    // A Nether is no use to something that asked for an Overworld, however warm it is.
    assertNull(pool.poll(WorldProfile.OVERWORLD));
    assertNotNull(pool.poll(WorldProfile.NETHER));
    assertNull(pool.poll(WorldProfile.NETHER), "…and it is handed out exactly once");
  }

  @Test
  void allThreeDimensionsArePooledIndependently() {
    // The whole point: an experience is an Overworld plus a Nether plus an End, so warming only
    // Overworlds still left two full world generations to pay for at start time.
    WorldPool pool = new WorldPool();
    for (WorldProfile profile : WorldPool.DEFAULT_WARM) {
      pool.target(profile, WorldPool.DEFAULT_WARM_SIZE);
    }
    assertEquals(3, pool.pooledProfiles().size());
    for (WorldProfile profile : WorldPool.DEFAULT_WARM) {
      assertEquals(3, pool.target(profile), profile + " must be kept warm");
      assertEquals(3, pool.shortfall(profile), "…and starts out entirely unfilled");
    }
    assertEquals(WorldDimension.OVERWORLD, WorldPool.DEFAULT_WARM.get(0).dimension());
    assertEquals(WorldDimension.NETHER, WorldPool.DEFAULT_WARM.get(1).dimension());
    assertEquals(WorldDimension.END, WorldPool.DEFAULT_WARM.get(2).dimension());
  }

  /**
   * The Overworld is warmed deeper than the other dimensions because it is what everything consumes: a
   * minigame takes one, an experience takes one, and a Death Resets regeneration takes another on every
   * death. Note a regeneration actually costs THREE warm worlds (an Overworld AND a Nether AND an End),
   * which is why the pool depths are worth reading as "how many hand-overs are free".
   */
  @Test
  void theOverworldIsPooledDeeperThanTheOtherDimensions() {
    assertEquals(5, WorldPool.DEFAULT_OVERWORLD_WARM_SIZE);
    assertEquals(3, WorldPool.DEFAULT_WARM_SIZE);

    WorldPool pool = new WorldPool();
    pool.target(WorldProfile.OVERWORLD, WorldPool.DEFAULT_OVERWORLD_WARM_SIZE);
    pool.target(WorldProfile.NETHER, WorldPool.DEFAULT_WARM_SIZE);

    assertEquals(5, pool.shortfall(WorldProfile.OVERWORLD));
    assertEquals(3, pool.shortfall(WorldProfile.NETHER));
  }

  @Test
  void worldsInUseDoNotStopThePoolRefilling() {
    // The regression that made the pool look ignored: it counted worlds already handed out against the
    // target, so three simultaneous matches emptied it AND stopped it refilling, and the next start paid
    // full price for a fresh world.
    WorldPool pool = new WorldPool();
    pool.target(WorldProfile.OVERWORLD, 3);
    for (int index = 0; index < 3; index++) {
      pool.offer(WorldProfile.OVERWORLD, handle("overworld-" + index));
    }
    assertEquals(0, pool.shortfall(WorldProfile.OVERWORLD), "a full pool asks for nothing");

    for (int index = 0; index < 3; index++) {
      assertNotNull(pool.poll(WorldProfile.OVERWORLD));
    }
    assertEquals(3, pool.shortfall(WorldProfile.OVERWORLD), "handing all three out must ask for three more");
  }

  @Test
  void aWorldBeingBuiltIsNotRequestedTwice() {
    WorldPool pool = new WorldPool();
    pool.target(WorldProfile.END, 2);
    pool.beginCreate(WorldProfile.END);
    assertEquals(1, pool.inFlight(WorldProfile.END));
    assertEquals(1, pool.shortfall(WorldProfile.END), "the one being built already counts");

    pool.endCreate(WorldProfile.END);
    assertEquals(0, pool.inFlight(WorldProfile.END));
    assertEquals(2, pool.shortfall(WorldProfile.END));
  }

  @Test
  void anUnpooledProfileIsNeverWarmed() {
    WorldPool pool = new WorldPool();
    pool.target(WorldProfile.OVERWORLD, 3);
    WorldProfile amplified = new WorldProfile(WorldDimension.OVERWORLD, false, WorldTerrain.AMPLIFIED);

    assertEquals(0, pool.target(amplified));
    assertTrue(pool.shortfall(amplified) <= 0, "an unpooled shape must never be built speculatively");
    assertFalse(pool.pooledProfiles().contains(amplified));
    // …and turning a profile off removes it from the warming rotation entirely.
    pool.target(WorldProfile.OVERWORLD, 0);
    assertTrue(pool.pooledProfiles().isEmpty());
  }

  @Test
  void everyWarmWorldIsVisibleToTheGarbageCollector() {
    // The GC reclaims disposable worlds nothing owns; a warm world is owned by the pool and must survive.
    WorldPool pool = new WorldPool();
    pool.offer(WorldProfile.OVERWORLD, handle("sexidium_temp/sexidium_temp_a"));
    pool.offer(WorldProfile.END, handle("sexidium_temp/sexidium_temp_b"));

    assertEquals(2, pool.all().size());
    assertEquals(2, pool.totalReady());
    assertTrue(pool.contains("sexidium_temp/sexidium_temp_a"));
    // Matched by the shared naming rules, so the platform's flattened label resolves too.
    assertTrue(pool.contains("sexidium_temp_b"));
    assertFalse(pool.contains("sexidium_temp/sexidium_temp_c"));
  }

  @Test
  void drainingEmptiesThePoolAndReturnsEverythingToDisposeOf() {
    WorldPool pool = new WorldPool();
    pool.offer(WorldProfile.OVERWORLD, handle("a"));
    pool.offer(WorldProfile.NETHER, handle("b"));

    assertEquals(2, pool.drain().size());
    assertEquals(0, pool.totalReady());
    assertNull(pool.poll(WorldProfile.OVERWORLD));
  }

  @Test
  void aProfileIdNamesTheShapeAndSurvivesARoundTrip() {
    assertEquals("overworld", WorldProfile.OVERWORLD.id());
    assertEquals("nether", WorldProfile.NETHER.id());
    assertEquals("end", WorldProfile.END.id());
    assertEquals("overworld-void", new WorldProfile(WorldDimension.OVERWORLD, true, null).id());
    assertEquals("nether-void", new WorldProfile(WorldDimension.NETHER, true, null).id());
    assertEquals("overworld-large-biomes",
        new WorldProfile(WorldDimension.OVERWORLD, false, WorldTerrain.LARGE_BIOMES).id());

    // A void world has no terrain for a preset to shape, so the preset is not part of its identity —
    // otherwise two identical empty worlds would be pooled as different shapes.
    assertEquals(new WorldProfile(WorldDimension.OVERWORLD, true, WorldTerrain.NORMAL),
        new WorldProfile(WorldDimension.OVERWORLD, true, WorldTerrain.AMPLIFIED));

    for (WorldProfile profile : WorldPool.DEFAULT_WARM) {
      assertEquals(profile, WorldProfile.of(profile.generation()), profile + " must round-trip");
      assertTrue(profile.isPlain());
    }
  }

  @Test
  void theGenerationRequestCarriesTheDimensionThroughToTheSettings() {
    // How the pool tells a backend to build a Nether rather than another Overworld.
    WorldSettings settings = WorldSettings.forGameWorld(750, 20, 0.2, true, "NORMAL")
        .withGeneration(WorldProfile.NETHER.generation());

    assertEquals(WorldDimension.NETHER, settings.dimension());
    assertFalse(settings.voidWorld());
    assertEquals(WorldProfile.NETHER, WorldProfile.of(settings));
    // …and the plain Overworld request stays the do-nothing default, so nothing else changes behaviour.
    assertTrue(WorldGeneration.DEFAULT.isDefault());
    assertFalse(WorldProfile.NETHER.generation().isDefault());
  }

  @Test
  void theReadOutNamesWhatIsWarm() {
    WorldPool pool = new WorldPool();
    assertEquals("disabled", pool.describe());
    pool.target(WorldProfile.OVERWORLD, 3);
    pool.offer(WorldProfile.OVERWORLD, handle("a"));
    assertEquals("overworld 1/3", pool.describe());
  }

  private static WorldHandle handle(String runtimeName) {
    return new WorldHandle() {
      @Override public String runtimeName() { return runtimeName; }
      @Override public WorldKind kind() { return WorldKind.TEMP; }
      @Override public WorldAdapter adapter() { return null; }
      @Override public Path canonicalFolder() { return Path.of("/tmp", runtimeName.replace('/', '_')); }
    };
  }

  @Test
  void aHandleIsTheSameObjectItWasPooledAs() {
    WorldPool pool = new WorldPool();
    WorldHandle warm = handle("x");
    pool.offer(WorldProfile.OVERWORLD, warm);
    assertSame(warm, pool.poll(WorldProfile.OVERWORLD));
  }
}
