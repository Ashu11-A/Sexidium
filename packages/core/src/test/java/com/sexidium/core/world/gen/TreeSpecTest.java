package com.sexidium.core.world.gen;

import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TreeSpecTest {
  private static final ItemKey LOG = ItemKey.minecraft("oak_log");
  private static final ItemKey LEAF = ItemKey.minecraft("oak_leaves");

  @Test
  void oak_hasTheExpectedShape() {
    TreeSpec oak = TreeSpec.oak();
    assertEquals(ItemKey.minecraft("oak_log"), oak.logBlock());
    assertEquals(ItemKey.minecraft("oak_leaves"), oak.leafBlock());
    assertEquals(5, oak.trunkHeight());
    assertEquals(2, oak.leafRadius());
  }

  @Test
  void construction_clampsTrunkAndRadiusToSaneMinimums() {
    TreeSpec spec = new TreeSpec(LOG, 0, LEAF, -3);
    assertEquals(1, spec.trunkHeight()); // clamped up from 0
    assertEquals(0, spec.leafRadius());  // clamped up from -3
    // A already-valid pair is left untouched.
    TreeSpec valid = new TreeSpec(LOG, 4, LEAF, 2);
    assertEquals(4, valid.trunkHeight());
    assertEquals(2, valid.leafRadius());
  }

  @Test
  void construction_rejectsNullBlocks() {
    assertThrows(IllegalArgumentException.class, () -> new TreeSpec(null, 3, LEAF, 1));
    // Non-null log, null leaf: exercises the second null-check.
    assertThrows(IllegalArgumentException.class, () -> new TreeSpec(LOG, 3, null, 1));
  }
}
