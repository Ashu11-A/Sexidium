package com.sexidium.core.world.gen;

import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Plans a one-chunk world made of stacked horizontal layers — the shape behind the Layered Dimensions
 * mode, where each dimension is a single 16×16 column you dig down through.
 *
 * <h2>Why the plan is separate from the building</h2>
 * The interesting part is entirely arithmetic: how many layers actually fit between a dimension's floor
 * and ceiling, where each one starts, which are hazards, and which single layer is the bottom. That
 * arithmetic is where the mistakes live — a Nether column asked for 300 blocks of layers has only 128 to
 * put them in — so it is computed here, with no world attached, and unit-tested directly
 * ({@code LayeredColumnTest}). The caller then walks the plan and writes blocks.
 *
 * <h2>Every dimension shares the layout</h2>
 * One {@link Spec} shapes them all: same chunk, same layer height, same descent. What differs is the
 * palette and the hazards each dimension draws from, so the Overworld and the Nether feel like the same
 * world seen twice rather than two unrelated maps. Layers are laid TOP-DOWN, so layer 1 is the surface
 * you spawn on and the last layer is the deepest — which is where a dimension's payoff sits (in the
 * Nether, the End portal).
 */
public final class LayeredColumn {
  /** What a layer does to the player beyond being made of a block. */
  public enum Feature {
    /** Just a slab of its block. */
    PLAIN,
    /** A slab with monster spawners set into it. */
    SPAWNER,
    /** A slab whose top is a pool of the dimension's liquid. */
    LIQUID,
    /** A slab with TNT under pressure plates. */
    TNT,
    /** A slab of a block that hurts to stand on (magma, cactus). */
    BURNING,
    /** A slab webbed over, so the descent stalls where the mobs are. */
    TANGLED,
    /** The floor of the whole column: what the dimension is really hiding. */
    FLOOR
  }

  /**
   * One planned layer.
   *
   * @param index  1-based, counting down from the surface
   * @param topY   the Y of this layer's TOP block
   * @param height how many blocks tall it is
   * @param block  the slab material
   * @param feature what makes this layer dangerous (or the floor)
   * @param mob    the spawner's entity type for a {@link Feature#SPAWNER}, else null
   */
  public record Layer(int index, int topY, int height, ItemKey block, Feature feature, String mob) {
    /** The Y of this layer's BOTTOM block. */
    public int bottomY() {
      return topY - height + 1;
    }

    public boolean isFloor() {
      return feature == Feature.FLOOR;
    }
  }

  /**
   * What a dimension's column should look like.
   *
   * @param layers        how many layers are WANTED; the plan fits as many as the dimension has room for
   * @param layerHeight   how many blocks tall each layer is
   * @param topY          the Y of the very top block of the column
   * @param minY          the dimension's floor — nothing is planned below it
   * @param palette       the blocks ordinary layers are drawn from
   * @param liquid        the block a {@link Feature#LIQUID} layer pools (water, lava)
   * @param burning       the block a {@link Feature#BURNING} layer is made of (magma block, …)
   * @param floorBlock    the block the deepest layer is made of — a dimension's payoff
   * @param mobs          entity types a {@link Feature#SPAWNER} layer may spawn
   * @param hazardChance  the share of layers that are a hazard rather than plain, in {@code [0,1]}
   */
  public record Spec(int layers, int layerHeight, int topY, int minY, List<ItemKey> palette,
      ItemKey liquid, ItemKey burning, ItemKey floorBlock, List<String> mobs, double hazardChance) {
    public Spec {
      layers = Math.max(1, layers);
      layerHeight = Math.max(1, layerHeight);
      palette = palette == null || palette.isEmpty() ? List.of(ItemKey.minecraft("stone")) : List.copyOf(palette);
      mobs = mobs == null ? List.of() : List.copyOf(mobs);
      hazardChance = Math.max(0.0, Math.min(1.0, hazardChance));
    }

    /**
     * How many layers actually fit between {@link #topY} and {@link #minY}. This is the whole reason the
     * plan is computed rather than assumed: the dimensions are not the same height, so a column asked for
     * 100 three-block layers gets all 100 in an Overworld (384 blocks of room) and as many as fit in a
     * Nether (128). Asking a platform to build outside a world's range is an error, not a no-op.
     */
    public int fittingLayers() {
      int room = topY - minY + 1;
      return Math.max(1, Math.min(layers, room / layerHeight));
    }

    /** Whether the dimension is too short for every layer that was asked for. */
    public boolean isClamped() {
      return fittingLayers() < layers;
    }
  }

  private LayeredColumn() {
  }

  /**
   * The column's layers, surface first. Deterministic for a given {@code seed}, so a world rebuilt after a
   * restart is the same world — and so two dimensions given the same seed descend through the same
   * pattern of hazards in their own materials.
   */
  public static List<Layer> plan(Spec spec, long seed) {
    if (spec == null) {
      return List.of();
    }
    Random random = new Random(seed);
    int count = spec.fittingLayers();
    List<Layer> layers = new ArrayList<>(count);
    int topY = spec.topY();
    for (int index = 1; index <= count; index++) {
      boolean last = index == count;
      if (last) {
        // The deepest layer is never random: it is what the dimension was hiding all along.
        layers.add(new Layer(index, topY, spec.layerHeight(),
            spec.floorBlock() == null ? spec.palette().get(0) : spec.floorBlock(), Feature.FLOOR, null));
        break;
      }
      // The first layer is always plain — you have to be able to stand somewhere when you arrive.
      Feature feature = index == 1 ? Feature.PLAIN : rollFeature(spec, random);
      ItemKey block = feature == Feature.BURNING && spec.burning() != null
          ? spec.burning() : spec.palette().get(random.nextInt(spec.palette().size()));
      String mob = feature == Feature.SPAWNER && !spec.mobs().isEmpty()
          ? spec.mobs().get(random.nextInt(spec.mobs().size())) : null;
      layers.add(new Layer(index, topY, spec.layerHeight(), block, feature, mob));
      topY -= spec.layerHeight();
    }
    return List.copyOf(layers);
  }

  private static Feature rollFeature(Spec spec, Random random) {
    if (random.nextDouble() >= spec.hazardChance()) {
      return Feature.PLAIN;
    }
    List<Feature> hazards = new ArrayList<>(List.of(Feature.TNT, Feature.TANGLED));
    if (!spec.mobs().isEmpty()) {
      hazards.add(Feature.SPAWNER);
      hazards.add(Feature.SPAWNER); // spawners are the signature hazard, so weight them
    }
    if (spec.liquid() != null) {
      hazards.add(Feature.LIQUID);
    }
    if (spec.burning() != null) {
      hazards.add(Feature.BURNING);
    }
    return hazards.get(random.nextInt(hazards.size()));
  }

  /** The Y a player should stand at to be on top of a planned column. */
  public static int surfaceY(Spec spec) {
    return spec == null ? 64 : spec.topY() + 1;
  }

  /** The total depth a planned column occupies, in blocks. */
  public static int depth(Spec spec) {
    return spec == null ? 0 : spec.fittingLayers() * spec.layerHeight();
  }
}
