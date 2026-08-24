package com.sexidium.core.world.gen;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A robust chest-loot generator: a set of <em>guaranteed</em> stacks that always appear plus a
 * <em>weighted pool</em> of optional entries that are rolled a configurable number of times. Given the
 * same {@link Random} sequence it produces the same loot, so it is fully deterministic and testable.
 *
 * <p>This is the loot half of the world-gen engine — the SkyBlock starter chest uses it (a guaranteed
 * lava/water/ice bootstrap plus a few random seeds/saplings), and future challenges can define their own
 * tables the same way.</p>
 */
public final class LootTable {
  /**
   * One weighted pool entry: {@code item} with a random stack size in {@code [minAmount, maxAmount]},
   * picked with probability proportional to {@code weight}.
   */
  public record Entry(ItemKey item, int minAmount, int maxAmount, int weight) {
    public Entry {
      if (item == null) {
        throw new IllegalArgumentException("loot entry item cannot be null");
      }
      minAmount = Math.max(1, minAmount);
      maxAmount = Math.max(minAmount, maxAmount);
      weight = Math.max(1, weight);
    }
  }

  private final List<ItemStackData> guaranteed;
  private final List<Entry> pool;
  private final int minRolls;
  private final int maxRolls;
  private final int totalWeight;

  private LootTable(Builder builder) {
    this.guaranteed = List.copyOf(builder.guaranteed);
    this.pool = List.copyOf(builder.pool);
    this.minRolls = builder.minRolls;
    this.maxRolls = builder.maxRolls;
    int weight = 0;
    for (Entry entry : this.pool) {
      weight += entry.weight();
    }
    this.totalWeight = weight;
  }

  /** True when this table would always produce an empty chest (no guaranteed items and no rolls/pool). */
  public boolean isEmpty() {
    return guaranteed.isEmpty() && (pool.isEmpty() || maxRolls <= 0);
  }

  /**
   * Rolls a concrete list of stacks: every guaranteed stack, followed by {@code [minRolls, maxRolls]}
   * weighted picks from the pool (skipped when the pool is empty).
   */
  public List<ItemStackData> roll(Random random) {
    List<ItemStackData> result = new ArrayList<>(guaranteed);
    int rolls = rollCount(random);
    for (int index = 0; index < rolls; index++) {
      Entry entry = pickWeighted(random);
      if (entry == null) {
        continue;
      }
      result.add(new ItemStackData(entry.item(), amountFor(entry, random), Map.of()));
    }
    return result;
  }

  private int rollCount(Random random) {
    int span = maxRolls - minRolls;
    return minRolls + (span > 0 ? random.nextInt(span + 1) : 0);
  }

  private Entry pickWeighted(Random random) {
    if (pool.isEmpty()) {
      return null;
    }
    int target = random.nextInt(totalWeight);
    int cumulative = 0;
    // Walk all but the last entry; whatever weight is left over belongs to the last entry, so it is the
    // unconditional fall-through — this keeps every branch reachable (no dead loop-exit edge).
    int index = 0;
    while (index < pool.size() - 1) {
      cumulative += pool.get(index).weight();
      if (target < cumulative) {
        return pool.get(index);
      }
      index++;
    }
    return pool.get(pool.size() - 1);
  }

  private static int amountFor(Entry entry, Random random) {
    int span = entry.maxAmount() - entry.minAmount();
    return entry.minAmount() + (span > 0 ? random.nextInt(span + 1) : 0);
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for a {@link LootTable}. */
  public static final class Builder {
    private final List<ItemStackData> guaranteed = new ArrayList<>();
    private final List<Entry> pool = new ArrayList<>();
    private int minRolls;
    private int maxRolls;

    /** Adds a stack that always appears in the chest (ignored when {@code item} is null). */
    public Builder guaranteed(ItemKey item, int amount) {
      if (item != null) {
        guaranteed.add(new ItemStackData(item, Math.max(1, amount), Map.of()));
      }
      return this;
    }

    /** Adds a weighted pool entry (ignored when {@code item} is null). */
    public Builder pool(ItemKey item, int minAmount, int maxAmount, int weight) {
      if (item != null) {
        pool.add(new Entry(item, minAmount, maxAmount, weight));
      }
      return this;
    }

    /** Sets how many pool picks happen per roll (clamped so {@code 0 <= min <= max}). */
    public Builder rolls(int min, int max) {
      this.minRolls = Math.max(0, min);
      this.maxRolls = Math.max(this.minRolls, max);
      return this;
    }

    public LootTable build() {
      return new LootTable(this);
    }
  }
}
