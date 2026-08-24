package com.sexidium.core.world.gen;

import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the arithmetic behind Layered Dimensions. The interesting failure is not "the wrong block" — it
 * is asking a platform to build outside a world's height, which is an error rather than a no-op, and the
 * dimensions are NOT the same height.
 */
class LayeredColumnTest {
  private static final List<ItemKey> PALETTE = List.of(ItemKey.minecraft("stone"), ItemKey.minecraft("dirt"));

  @Test
  void anOverworldHasRoomForAllOneHundredLayers() {
    // −64…320 is 384 blocks, so 100 layers of 3 fit with room to spare.
    LayeredColumn.Spec spec = spec(100, 3, 312, -64, ItemKey.minecraft("bedrock"));

    assertEquals(100, spec.fittingLayers());
    assertFalse(spec.isClamped());
    assertEquals(300, LayeredColumn.depth(spec));
    assertEquals(100, LayeredColumn.plan(spec, 1L).size());
  }

  @Test
  void aNetherIsTooShortForAllOfThemAndIsClampedRatherThanOverflowing() {
    // The Nether is 0…128. A hundred three-block layers is 300 blocks and simply cannot fit, so the plan
    // must stop at the floor instead of planning blocks the world has nowhere to put.
    LayeredColumn.Spec spec = spec(100, 3, 120, 0, ItemKey.minecraft("end_portal"));

    assertTrue(spec.isClamped(), "a 100-layer Nether must be reported as clamped, not silently wrong");
    assertEquals(40, spec.fittingLayers());

    List<LayeredColumn.Layer> plan = LayeredColumn.plan(spec, 1L);
    assertEquals(40, plan.size());
    for (LayeredColumn.Layer layer : plan) {
      assertTrue(layer.bottomY() >= 0, "layer " + layer.index() + " must stay inside the world");
      assertTrue(layer.topY() <= 120);
    }
  }

  @Test
  void theDeepestLayerIsWhateverTheDimensionWasHiding() {
    // The Nether's payoff: dig through everything and the floor is a working End portal.
    LayeredColumn.Spec spec = spec(10, 3, 120, 0, ItemKey.minecraft("end_portal"));
    List<LayeredColumn.Layer> plan = LayeredColumn.plan(spec, 7L);

    LayeredColumn.Layer deepest = plan.get(plan.size() - 1);
    assertEquals(10, deepest.index());
    assertTrue(deepest.isFloor());
    assertEquals("end_portal", deepest.block().value());
    assertNull(deepest.mob(), "the floor is never a spawner layer");
    // …and only the deepest one.
    for (int index = 0; index < plan.size() - 1; index++) {
      assertFalse(plan.get(index).isFloor());
    }
  }

  @Test
  void theFirstLayerIsAlwaysSomethingYouCanStandOn() {
    // You arrive on the surface; a trap or a lava pool there would kill you before you moved.
    for (long seed = 0; seed < 40; seed++) {
      LayeredColumn.Layer first = LayeredColumn.plan(spec(20, 3, 120, 0, null), seed).get(0);
      assertEquals(LayeredColumn.Feature.PLAIN, first.feature(), "seed " + seed);
    }
  }

  @Test
  void layersStackDownwardsWithoutGapsOrOverlaps() {
    List<LayeredColumn.Layer> plan = LayeredColumn.plan(spec(20, 3, 120, 0, null), 5L);

    for (int index = 0; index < plan.size(); index++) {
      LayeredColumn.Layer layer = plan.get(index);
      assertEquals(index + 1, layer.index(), "layers are numbered from the surface down");
      assertEquals(3, layer.height());
      assertEquals(layer.topY() - 2, layer.bottomY());
      if (index > 0) {
        assertEquals(plan.get(index - 1).bottomY() - 1, layer.topY(), "no gap and no overlap");
      }
    }
  }

  @Test
  void theSameSeedGivesTheSameColumnSoARestartIsTheSameWorld() {
    LayeredColumn.Spec spec = spec(30, 3, 120, 0, null);
    List<LayeredColumn.Layer> first = LayeredColumn.plan(spec, 99L);
    List<LayeredColumn.Layer> again = LayeredColumn.plan(spec, 99L);

    assertEquals(first, again);
    // …and a different seed really does give a different one.
    assertFalse(first.equals(LayeredColumn.plan(spec, 100L)));
  }

  @Test
  void aSpawnerLayerAlwaysNamesWhatItSpawns() {
    List<LayeredColumn.Layer> plan = LayeredColumn.plan(spec(200, 3, 320, -64, null), 3L);
    boolean sawSpawner = false;
    for (LayeredColumn.Layer layer : plan) {
      if (layer.feature() == LayeredColumn.Feature.SPAWNER) {
        sawSpawner = true;
        assertNotNull(layer.mob(), "a spawner with nothing to spawn is just a decoration");
        assertTrue(List.of("zombie", "blaze").contains(layer.mob()));
      }
    }
    assertTrue(sawSpawner, "a column this long must contain at least one spawner layer");
  }

  @Test
  void aDimensionWithNoMobsNeverPlansASpawnerLayer() {
    LayeredColumn.Spec spec = new LayeredColumn.Spec(60, 3, 120, 0, PALETTE,
        ItemKey.minecraft("lava"), ItemKey.minecraft("magma_block"), null, List.of(), 1.0);
    for (LayeredColumn.Layer layer : LayeredColumn.plan(spec, 11L)) {
      assertFalse(layer.feature() == LayeredColumn.Feature.SPAWNER);
    }
  }

  @Test
  void aHazardChanceOfZeroGivesAPlainColumn() {
    LayeredColumn.Spec spec = new LayeredColumn.Spec(30, 3, 120, 0, PALETTE,
        ItemKey.minecraft("lava"), ItemKey.minecraft("magma_block"), null, List.of("zombie"), 0.0);
    for (LayeredColumn.Layer layer : LayeredColumn.plan(spec, 4L)) {
      assertTrue(layer.feature() == LayeredColumn.Feature.PLAIN || layer.isFloor());
    }
  }

  @Test
  void aColumnAlwaysHasAtLeastOneLayerHoweverCrampedTheDimension() {
    // Never plan zero layers: a world with no floor at all is worse than a short one.
    LayeredColumn.Spec spec = spec(100, 3, 1, 0, null);
    assertEquals(1, spec.fittingLayers());
    assertEquals(1, LayeredColumn.plan(spec, 1L).size());
    assertEquals(0, LayeredColumn.plan(null, 1L).size());
  }

  @Test
  void theSurfaceIsOneBlockAboveTheTopLayer() {
    assertEquals(313, LayeredColumn.surfaceY(spec(100, 3, 312, -64, null)));
  }

  private static LayeredColumn.Spec spec(int layers, int height, int topY, int minY, ItemKey floor) {
    return new LayeredColumn.Spec(layers, height, topY, minY, PALETTE, ItemKey.minecraft("water"),
        ItemKey.minecraft("magma_block"), floor, List.of("zombie", "blaze"), 0.35);
  }
}
