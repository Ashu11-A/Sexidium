package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single loot sink for the budgeted block-sweep challenges (Break-One-Break-All, Chunk Break).
 * Both accumulate a tick's removed blocks into {@code {type -> count}} buckets and emit them THE SAME
 * way: routed through the shared {@link DropPipeline} (so Double Drops / Randomizer apply exactly as for
 * a manual break), then dropped on the ground at consistent, specific locations instead of one item
 * entity per block. Centralising it here is what makes the two chunk-breaking modes behave consistently
 * and removes the duplicated emit code they each carried.
 */
public final class SweepLootSink {
  /** Four drop points per chunk (the quadrant centres), so chunk-stacks loot spreads out instead of
   * piling at one spot. In-chunk block offsets in [0,16). */
  private static final int[][] CHUNK_DROP_POINTS = {{4, 4}, {12, 4}, {4, 12}, {12, 12}};

  /** Share of a type's swept loot that lands at the actual block the player broke; the rest is spread
   * across the chunk region points. */
  private static final double BREAK_BLOCK_SHARE = 0.25;

  private final BlockBreakService blocks;
  private final StackMergeService stackMerge;
  private final double chunkMergeRadius;

  public SweepLootSink(BlockBreakService blocks, StackMergeService stackMerge, double chunkMergeRadius) {
    this.blocks = blocks;
    this.stackMerge = stackMerge;
    this.chunkMergeRadius = Math.max(0.0, chunkMergeRadius);
  }

  /** The four quadrant drop points (world coords) of one chunk at {@code dropY}, for callers building
   * the region-point set passed to {@link #emitChunkStacks}. */
  public static List<WorldPosition> chunkRegionPoints(String worldName, int baseX, int baseZ, int dropY) {
    List<WorldPosition> points = new ArrayList<>(CHUNK_DROP_POINTS.length);
    for (int[] offset : CHUNK_DROP_POINTS) {
      points.add(new WorldPosition(worldName,
          baseX + offset[0] + 0.5, dropY + 0.5, baseZ + offset[1] + 0.5, 0.0F, 0.0F));
    }
    return points;
  }

  /**
   * Single-point mode: route the accumulated loot through the shared pipeline keyed by the original
   * block type (so the Randomizer remaps the broken block, not its drop item) and consolidate each result
   * on the ground at {@code center} via the {@link StackMergeService} — which tops up nearby same-type
   * entities and keeps tracking partial stacks for later merging — so a high-volume sweep does not litter
   * the area with hundreds of item entities. {@code byType} maps each broken block type to its accumulated
   * natural drops ({@code dropItem -> count}).
   */
  public void emitToGround(PlayerAdapter cause, WorldAdapter world, WorldPosition center,
      Map<ItemKey, Map<ItemKey, Integer>> byType) {
    if (byType == null || byType.isEmpty() || world == null || center == null) {
      return;
    }
    for (Map.Entry<ItemKey, Map<ItemKey, Integer>> entry : byType.entrySet()) {
      for (ItemStackData stack : blocks.transformRemovedLoot(
          DropSource.SWEEP, center, entry.getKey(), toStacks(entry.getValue()), cause)) {
        emitMerged(world, center, stack.itemKey(), stack.amount());
      }
    }
  }

  /** One broken type's accumulated loot within a single chunk: the position where THAT type was broken
   * in THAT chunk (where its 25% lands) plus its natural drops ({@code dropItem -> count}). */
  public record TypeDrop(WorldPosition breakBlock, Map<ItemKey, Integer> counts) {
  }

  /**
   * Chunk-stacks mode for ONE chunk: each broken type's loot is placed so the chunk looks naturally
   * looted — {@value BREAK_BLOCK_SHARE} of that type lands at {@link TypeDrop#breakBlock()} (a position
   * where that exact type was broken in this chunk) and the remainder is split evenly across this chunk's
   * {@code regionPoints} (its quadrant points). Each type's loot is kept separate and tied to its own
   * break location, so a type's break point never drops another type's items and one chunk's loot never
   * leaks to another. Each drop goes through the {@link StackMergeService} for over-cap consolidation, and
   * the pipeline is keyed by the broken block type (consistent Randomizer remap / Double Drops).
   */
  public void emitChunkStacks(PlayerAdapter cause, WorldAdapter world,
      List<WorldPosition> regionPoints, Map<ItemKey, TypeDrop> byType) {
    if (byType == null || byType.isEmpty() || world == null) {
      return;
    }
    for (Map.Entry<ItemKey, TypeDrop> entry : byType.entrySet()) {
      TypeDrop drop = entry.getValue();
      WorldPosition breakBlock = drop.breakBlock();
      if (breakBlock == null) {
        continue;
      }
      for (ItemStackData resolved : blocks.transformRemovedLoot(
          DropSource.SWEEP, breakBlock, entry.getKey(), toStacks(drop.counts()), cause)) {
        int total = resolved.amount();
        if (total <= 0) {
          continue;
        }
        ItemKey itemKey = resolved.itemKey();
        // 25% at the position this type was broken; the rest split evenly across this chunk's region
        // points (or back at the break block when there is nowhere else to spread).
        int breakShare = Math.min(total, (int) Math.round(total * BREAK_BLOCK_SHARE));
        emitMerged(world, breakBlock, itemKey, breakShare);
        int remainder = total - breakShare;
        if (remainder <= 0) {
          continue;
        }
        if (regionPoints == null || regionPoints.isEmpty()) {
          emitMerged(world, breakBlock, itemKey, remainder);
          continue;
        }
        int points = regionPoints.size();
        int perPoint = remainder / points;
        int extra = remainder % points;
        for (int i = 0; i < points; i++) {
          int amount = perPoint + (i < extra ? 1 : 0);
          if (amount > 0) {
            emitMerged(world, regionPoints.get(i), itemKey, amount);
          }
        }
      }
    }
  }

  /** Region/single-point loot: consolidated with nearby same-type stacks via the {@link StackMergeService}
   * (or dropped directly when no service is wired, e.g. tests). */
  private void emitMerged(WorldAdapter world, WorldPosition at, ItemKey itemKey, int amount) {
    if (amount <= 0) {
      return;
    }
    if (stackMerge != null) {
      stackMerge.add(world, at, itemKey, amount, chunkMergeRadius);
    } else {
      world.dropItem(at, new ItemStackData(itemKey, amount, java.util.Map.of()));
    }
  }

  /** Flattens an accumulated {@code dropItem -> count} bucket into base loot stacks for the pipeline. */
  private static List<ItemStackData> toStacks(Map<ItemKey, Integer> counts) {
    if (counts == null || counts.isEmpty()) {
      return List.of();
    }
    List<ItemStackData> stacks = new ArrayList<>(counts.size());
    for (Map.Entry<ItemKey, Integer> entry : counts.entrySet()) {
      if (entry.getValue() != null && entry.getValue() > 0) {
        stacks.add(new ItemStackData(entry.getKey(), entry.getValue(), java.util.Map.of()));
      }
    }
    return stacks;
  }
}
