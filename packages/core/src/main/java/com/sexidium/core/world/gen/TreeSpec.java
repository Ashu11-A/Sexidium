package com.sexidium.core.world.gen;

import com.sexidium.core.platform.model.ItemKey;

/**
 * The shape of a simple procedural tree the {@link StructureBuilder} plants: a straight {@code logBlock}
 * trunk {@code trunkHeight} blocks tall, topped by a {@code leafBlock} canopy of radius {@code leafRadius}.
 * A pure value type so the world-gen engine stays platform-independent and fully testable.
 */
public record TreeSpec(ItemKey logBlock, int trunkHeight, ItemKey leafBlock, int leafRadius) {
  public TreeSpec {
    if (logBlock == null) {
      throw new IllegalArgumentException("logBlock cannot be null");
    }
    if (leafBlock == null) {
      throw new IllegalArgumentException("leafBlock cannot be null");
    }
    trunkHeight = Math.max(1, trunkHeight);
    leafRadius = Math.max(0, leafRadius);
  }

  /** A standard oak: a 5-block log trunk with a radius-2 oak-leaf canopy. */
  public static TreeSpec oak() {
    return new TreeSpec(ItemKey.minecraft("oak_log"), 5, ItemKey.minecraft("oak_leaves"), 2);
  }
}
