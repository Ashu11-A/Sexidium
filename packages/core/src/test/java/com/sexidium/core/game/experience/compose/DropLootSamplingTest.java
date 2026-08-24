package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards how a multiplier scales a block's REAL loot: a probabilistic table (leaves) must be sampled and
 * projected, never scaled from a single lucky or unlucky roll, and a break that legitimately drops
 * nothing (wrong tool) must stay empty instead of falling back to the block item.
 */
class DropLootSamplingTest {
  private static final ItemKey OAK_LEAVES = ItemKey.minecraft("oak_leaves");
  private static final ItemKey SAPLING = ItemKey.minecraft("oak_sapling");
  private static final ItemKey STICK = ItemKey.minecraft("stick");
  private static final ItemKey APPLE = ItemKey.minecraft("apple");
  private static final ItemKey IRON_ORE = ItemKey.minecraft("iron_ore");
  private static final ItemKey COBBLESTONE = ItemKey.minecraft("cobblestone");
  private static final WorldPosition POS = new WorldPosition("world", 0.5, 64.5, 0.5, 0f, 0f);

  @Test
  void aProbabilisticTableIsSampledAcrossTheWholeMultiplier() {
    // A leaf table: 5% sapling, 2% stick, 0.5% apple — the rest of the time, nothing at all.
    Random random = new Random(42);
    DropContext context = leafBreak(random);
    context.seedSourceItem();
    context.multiply(65_536, StackMergeService.MAX_AMOUNT);

    Map<ItemKey, Integer> byType = totals(context.drops());
    // Expectations over 65,536 breaks: ~3277 saplings, ~1311 sticks, ~328 apples. Estimating from a
    // sample is noisy by design, so assert generous bands rather than exact counts.
    assertInRange(byType.getOrDefault(SAPLING, 0), 1500, 6000, "saplings");
    assertInRange(byType.getOrDefault(STICK, 0), 300, 3000, "sticks");
    assertInRange(byType.getOrDefault(APPLE, 0), 0, 2000, "apples");
    // …and never the leaf block itself: that needs shears or Silk Touch.
    assertFalse(byType.containsKey(OAK_LEAVES), "the leaf BLOCK must not drop: " + byType);

    // The rare drop must actually reach players: over a handful of payouts, apples DO show up. (With a
    // single roll scaled by the multiplier this would be all-or-nothing at 65,536 apples a time.)
    int payoutsWithApples = 0;
    long appleTotal = 0;
    for (int run = 0; run < 8; run++) {
      DropContext other = leafBreak(random);
      other.seedSourceItem();
      other.multiply(65_536, StackMergeService.MAX_AMOUNT);
      int apples = totals(other.drops()).getOrDefault(APPLE, 0);
      appleTotal += apples;
      if (apples > 0) {
        payoutsWithApples++;
      }
    }
    assertTrue(payoutsWithApples > 0, "a 0.5% drop must still pay out across 8 huge payouts");
    assertInRange((int) (appleTotal / 8), 30, 1200, "average apples per payout");
  }

  @Test
  void oneLuckyRollIsNotTurnedIntoAMillionApples() {
    // The seeded roll is a jackpot apple, but the table only yields an apple 0.5% of the time.
    AtomicInteger rolls = new AtomicInteger();
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, OAK_LEAVES, POS, null, List.of());
    context.setNaturalSeed(List.of(new ItemStackData(APPLE, 1, Map.of())));
    context.setLootSampler(() -> {
      rolls.incrementAndGet();
      return List.of(); // every subsequent roll is empty
    });
    context.seedSourceItem();
    context.multiply(65_536, StackMergeService.MAX_AMOUNT);

    assertTrue(rolls.get() > 1, "the table must be re-rolled, not scaled from the first roll");
    assertEquals(0, totals(context.drops()).getOrDefault(APPLE, 0),
        "a table that (re)rolls empty must not pay out 65,536 apples");
  }

  @Test
  void oneUnluckyRollDoesNotZeroOutTheWholePayout() {
    // The seeded roll came up empty, but the table does yield saplings — the payout must reflect that.
    AtomicInteger rolls = new AtomicInteger();
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, OAK_LEAVES, POS, null, List.of());
    context.setNaturalSeed(List.of());
    context.setLootSampler(() -> rolls.incrementAndGet() % 2 == 0
        ? List.of(new ItemStackData(SAPLING, 1, Map.of()))
        : List.of());
    context.seedSourceItem();
    context.multiply(1000, StackMergeService.MAX_AMOUNT);

    // Roughly half of 1000 breaks yield a sapling.
    assertInRange(totals(context.drops()).getOrDefault(SAPLING, 0), 300, 700, "saplings");
  }

  @Test
  void aDeterministicTableScalesExactly() {
    // Stone always yields exactly one cobblestone, so sampling must reproduce a plain multiplication.
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, ItemKey.minecraft("stone"), POS, null, List.of());
    context.setNaturalSeed(List.of(new ItemStackData(COBBLESTONE, 1, Map.of())));
    context.setLootSampler(() -> List.of(new ItemStackData(COBBLESTONE, 1, Map.of())));
    context.seedSourceItem();
    context.multiply(4096, StackMergeService.MAX_AMOUNT);

    assertEquals(4096, totals(context.drops()).getOrDefault(COBBLESTONE, 0));
  }

  @Test
  void theWrongToolDropsNothingEvenAtAHugeMultiplier() {
    // A wooden pickaxe on iron ore: vanilla yields nothing, so a multiplier has nothing to multiply.
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, IRON_ORE, POS, null, List.of());
    context.setNaturalSeed(List.of());
    context.setLootSampler(List::of);
    context.seedSourceItem();
    context.multiply(65_536, StackMergeService.MAX_AMOUNT);

    assertTrue(context.drops().isEmpty(), "wrong tool must not conjure loot: " + context.drops());
    assertTrue(context.vanillaSuppressed(), "…and vanilla must not drop the block either");
  }

  @Test
  void withoutASamplerTheOldBlockItemSeedIsKept() {
    // A platform that cannot compute loot (no sampler installed) must keep working exactly as before,
    // rather than concluding that every break drops nothing.
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, COBBLESTONE, POS, null, List.of());
    context.seedSourceItem();
    context.multiply(8, 1_000_000);

    assertEquals(8, totals(context.drops()).getOrDefault(COBBLESTONE, 0));
  }

  @Test
  void lootGeneratedByAnotherChallengeIsScaledLinearlyNotResampled() {
    // A Randomizer remap owns the loot; re-rolling the BLOCK's table would throw its result away.
    AtomicInteger rolls = new AtomicInteger();
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, OAK_LEAVES, POS, null, List.of());
    context.setNaturalSeed(List.of(new ItemStackData(SAPLING, 1, Map.of())));
    context.setLootSampler(() -> {
      rolls.incrementAndGet();
      return List.of(new ItemStackData(SAPLING, 1, Map.of()));
    });
    context.replaceAll(List.of(new ItemStackData(APPLE, 2, Map.of()))); // the remap
    context.multiply(10, 1_000_000);

    assertEquals(0, rolls.get(), "another contributor's loot must not be re-rolled from the block");
    assertEquals(20, totals(context.drops()).getOrDefault(APPLE, 0));
  }

  @Test
  void sweepLootIsNeverResampled() {
    // A sweep already rolled the table once per block it removed, so its accumulated bucket is exact.
    DropContext context = new DropContext(DropSource.SWEEP, OAK_LEAVES, POS, null,
        List.of(new ItemStackData(SAPLING, 7, Map.of())));
    context.seedSourceItem();
    context.multiply(100, 1_000_000);

    assertEquals(700, totals(context.drops()).getOrDefault(SAPLING, 0));
  }

  /** A leaf break whose sampler rolls the real vanilla-ish leaf probabilities. */
  private static DropContext leafBreak(Random random) {
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, OAK_LEAVES, POS, null, List.of());
    context.setNaturalSeed(rollLeaves(random));
    context.setLootSampler(() -> rollLeaves(random));
    return context;
  }

  private static List<ItemStackData> rollLeaves(Random random) {
    java.util.List<ItemStackData> roll = new java.util.ArrayList<>();
    if (random.nextDouble() < 0.05) {
      roll.add(new ItemStackData(SAPLING, 1, Map.of()));
    }
    if (random.nextDouble() < 0.02) {
      roll.add(new ItemStackData(STICK, 1, Map.of()));
    }
    if (random.nextDouble() < 0.005) {
      roll.add(new ItemStackData(APPLE, 1, Map.of()));
    }
    return roll;
  }

  private static Map<ItemKey, Integer> totals(List<ItemStackData> stacks) {
    Map<ItemKey, Integer> byType = new java.util.LinkedHashMap<>();
    for (ItemStackData stack : stacks) {
      byType.merge(stack.itemKey(), stack.amount(), Integer::sum);
    }
    return byType;
  }

  private static void assertInRange(int actual, int min, int max, String what) {
    assertTrue(actual >= min && actual <= max, what + " out of range: " + actual + " not in [" + min + "," + max + "]");
  }
}
