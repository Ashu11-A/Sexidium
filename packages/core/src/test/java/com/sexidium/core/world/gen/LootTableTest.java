package com.sexidium.core.world.gen;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.world.gen.LootTable.Entry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LootTableTest {
  private static final ItemKey LAVA = ItemKey.minecraft("lava_bucket");
  private static final ItemKey A = ItemKey.minecraft("stone");
  private static final ItemKey B = ItemKey.minecraft("dirt");

  // ----- Entry ---------------------------------------------------------------------------------

  @Test
  void entry_clampsAmountsAndWeight() {
    Entry entry = new Entry(A, 0, 0, 0);
    assertEquals(1, entry.minAmount());
    assertEquals(1, entry.maxAmount());
    assertEquals(1, entry.weight());
    Entry ordered = new Entry(A, 3, 1, 5); // max < min gets pulled up to min
    assertEquals(3, ordered.minAmount());
    assertEquals(3, ordered.maxAmount());
    assertEquals(5, ordered.weight());
  }

  @Test
  void entry_rejectsNullItem() {
    assertThrows(IllegalArgumentException.class, () -> new Entry(null, 1, 1, 1));
  }

  // ----- Builder -------------------------------------------------------------------------------

  @Test
  void builder_ignoresNullGuaranteedAndPoolItems() {
    LootTable table = LootTable.builder()
        .guaranteed(null, 5)   // skipped
        .pool(null, 1, 1, 1)   // skipped
        .build();
    assertTrue(table.isEmpty());
    assertTrue(table.roll(new Random(1)).isEmpty());
  }

  @Test
  void builder_rollsClampsNegativeAndReordersMinMax() {
    // rolls(-5, -1) -> min 0, max 0 => never rolls the pool.
    LootTable none = LootTable.builder().pool(A, 1, 1, 1).rolls(-5, -1).build();
    assertTrue(none.roll(new Random(1)).isEmpty());
    // rolls(2, 1) -> min 2, max 2 (max pulled up) => exactly two picks from a single-entry pool.
    LootTable two = LootTable.builder().pool(A, 1, 1, 1).rolls(2, 1).build();
    assertEquals(2, two.roll(new Random(1)).size());
  }

  // ----- isEmpty (all four branch combinations) ------------------------------------------------

  @Test
  void isEmpty_coversEveryBranch() {
    assertTrue(LootTable.builder().build().isEmpty());                                  // no guaranteed, no pool
    assertTrue(LootTable.builder().pool(A, 1, 1, 1).rolls(0, 0).build().isEmpty());     // pool but 0 rolls
    assertFalse(LootTable.builder().guaranteed(LAVA, 1).build().isEmpty());             // has guaranteed
    assertFalse(LootTable.builder().pool(A, 1, 1, 1).rolls(1, 1).build().isEmpty());    // pool + rolls
  }

  // ----- roll ----------------------------------------------------------------------------------

  @Test
  void roll_alwaysIncludesGuaranteedAndNoPoolMeansNoExtraRolls() {
    LootTable table = LootTable.builder().guaranteed(LAVA, 1).rolls(0, 0).build();
    List<ItemStackData> loot = table.roll(new Random(7));
    assertEquals(1, loot.size());
    assertEquals(LAVA, loot.get(0).itemKey());
  }

  @Test
  void roll_withRollsButEmptyPool_skipsThePicks() {
    // rolls happen (min==max==1) but the pool is empty, so pickWeighted returns null and the pick is skipped.
    LootTable table = LootTable.builder().rolls(1, 1).build();
    assertTrue(table.roll(new Random(3)).isEmpty());
  }

  @Test
  void roll_picksTheWeightedEntryTheTargetLandsIn() {
    LootTable table = LootTable.builder().pool(A, 1, 1, 1).pool(B, 1, 1, 1).rolls(1, 1).build();
    // totalWeight = 2. target 0 -> first entry (A); target 1 -> second entry (B).
    assertEquals(A, table.roll(new ScriptedRandom(0)).get(0).itemKey());
    assertEquals(B, table.roll(new ScriptedRandom(1)).get(0).itemKey());
  }

  @Test
  void roll_variableRollCountAndVariableAmount() {
    LootTable table = LootTable.builder().pool(A, 2, 4, 5).rolls(0, 2).build();
    // Scripted calls in order: rollCount nextInt(3)=2 -> 2 rolls; then per roll pick nextInt(5) + amount nextInt(3).
    List<ItemStackData> loot = table.roll(new ScriptedRandom(2, 0, 1, 0, 2));
    assertEquals(2, loot.size());
    assertEquals(3, loot.get(0).amount()); // 2 + 1
    assertEquals(4, loot.get(1).amount()); // 2 + 2
  }

  /** A {@link Random} whose {@code nextInt(bound)} returns a scripted sequence (clamped into range). */
  private static final class ScriptedRandom extends Random {
    private final int[] values;
    private int index;

    ScriptedRandom(int... values) {
      this.values = values;
    }

    @Override
    public int nextInt(int bound) {
      int value = values[index++];
      return Math.max(0, Math.min(bound - 1, value));
    }
  }
}
