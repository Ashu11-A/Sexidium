package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The mutable loot of a single break/death as it flows through the {@link DropPipeline}. Every
 * {@link DropContributor} reads and edits the same {@link #drops()} list, which is what lets a
 * multiplier scale whatever a generator produced.
 *
 * <p>For {@link DropSource#BLOCK_BREAK} the list starts EMPTY and vanilla loot is the fallback: if
 * no contributor touches it ({@link #dirty()} stays false) vanilla is left intact. Calling
 * {@link #add} layers a bonus stack <em>on top of</em> vanilla; {@link #replaceAll}/{@link #multiply}/
 * {@link #suppressVanilla} replace it. For {@link DropSource#SWEEP}/{@link DropSource#EXPLOSION}/
 * {@link DropSource#ENTITY_DEATH} the block/mob is already gone, so {@link #mustEmit()} is true and
 * the seeded list is always emitted.</p>
 */
public final class DropContext {
  /** Hard ceiling on loot-table rolls used to estimate a block's per-break average. */
  private static final int MAX_LOOT_SAMPLES = 512;
  /** Never estimate from fewer rolls than this (unless the multiplier itself is smaller). */
  private static final int MIN_LOOT_SAMPLES = 16;
  /** Stop early once this many item units have been observed — a common drop needs no more rolls. */
  private static final int ENOUGH_OBSERVATIONS = 32;

  private final DropSource source;
  private final ItemKey sourceKey;
  private final WorldPosition position;
  private final PlayerAdapter cause;
  private final List<ItemStackData> drops = new ArrayList<>();
  private List<ItemStackData> naturalSeed = List.of();
  private final boolean mustEmit;
  private boolean dirty;
  private boolean vanillaSuppressed;
  // True while the loot is exactly the block's own natural roll and nothing else has produced it, which
  // is what makes re-rolling that same table a valid way to scale it (see multiply).
  private boolean naturalOnly;
  // Re-rolls the source block's loot on demand. A random table (leaves, grass) gives a different answer
  // every call, which is how a huge multiplier is resolved by SAMPLING rather than by scaling one roll.
  private Supplier<List<ItemStackData>> lootSampler;
  // How many ticks the sink should pour this loot out over (<= 1 = all in one tick). See spreadOver.
  private int spreadTicks;

  public DropContext(DropSource source, ItemKey sourceKey, WorldPosition position, PlayerAdapter cause,
      List<ItemStackData> initial) {
    this.source = source == null ? DropSource.OTHER : source;
    this.sourceKey = sourceKey;
    this.position = position;
    this.cause = cause;
    this.mustEmit = this.source != DropSource.BLOCK_BREAK;
    if (initial != null) {
      for (ItemStackData stack : initial) {
        if (stack != null) {
          drops.add(stack);
        }
      }
    }
  }

  public DropSource source() {
    return source;
  }

  /** The block type (or mob type) the loot came from; may be null. */
  public ItemKey sourceKey() {
    return sourceKey;
  }

  public WorldPosition position() {
    return position;
  }

  /** The breaking/killing player, or null. */
  public PlayerAdapter cause() {
    return cause;
  }


  /** The live, mutable loot list — contributors edit this directly. */
  public List<ItemStackData> drops() {
    return drops;
  }

  public boolean dirty() {
    return dirty;
  }

  public boolean mustEmit() {
    return mustEmit;
  }

  public boolean vanillaSuppressed() {
    return vanillaSuppressed;
  }

  /** Suppress the vanilla drop (the native break will keep no loot). Implies {@link #dirty()}. */
  public void suppressVanilla() {
    vanillaSuppressed = true;
    dirty = true;
  }

  /** Add a bonus stack. Without {@link #suppressVanilla()} it layers on top of vanilla loot. */
  public void add(ItemStackData stack) {
    if (stack != null) {
      drops.add(stack);
      dirty = true;
      naturalOnly = false; // no longer just the block's own roll
    }
  }

  /** Replace all loot with the given stacks and suppress vanilla. */
  public void replaceAll(List<ItemStackData> stacks) {
    naturalOnly = false; // somebody generated their own loot (a Randomizer remap); do not re-roll ours
    drops.clear();
    if (stacks != null) {
      for (ItemStackData stack : stacks) {
        if (stack != null) {
          drops.add(stack);
        }
      }
    }
    suppressVanilla();
  }

  /**
   * The block's natural break loot (e.g. stone -&gt; cobblestone), if the platform resolved it. Set by
   * {@link BlockBreakService#onManualBreak}; {@link #seedSourceItem()} prefers it over the block item.
   */
  public void setNaturalSeed(List<ItemStackData> stacks) {
    naturalSeed = stacks == null ? List.of() : stacks;
  }

  /**
   * Supplies a FRESH roll of the source block's loot each time it is called (see
   * {@code WorldAdapter.naturalDrops(position, breaker)}). Set by {@link BlockBreakService#onManualBreak}
   * and only valid for the duration of that call, while the block still exists.
   */
  public void setLootSampler(Supplier<List<ItemStackData>> lootSampler) {
    this.lootSampler = lootSampler;
  }

  /**
   * Ensure the block's loot is represented if nothing has produced loot yet: prefer the resolved
   * natural break result ({@link #setNaturalSeed}, e.g. stone -&gt; cobblestone) and fall back to a
   * single item of the source block when no natural loot is available.
   */
  public void seedSourceItem() {
    if (!drops.isEmpty()) {
      return;
    }
    if (naturalSeed.isEmpty() && lootSampler != null) {
      // The block's own roll came up empty (leaves that dropped nothing, stone hit with a bare hand).
      // That is a legitimate result, and it is still "natural only" — a multiplier samples the table
      // rather than inventing a block item that vanilla would never have dropped.
      naturalOnly = true;
      return;
    }
    if (!naturalSeed.isEmpty()) {
      drops.addAll(naturalSeed);
      dirty = true;
      naturalOnly = true;
      return;
    }
    if (sourceKey != null) {
      drops.add(new ItemStackData(sourceKey, 1, Map.of()));
      dirty = true;
    }
  }

  /**
   * Scale every stack amount by {@code factor} (clamped per stack to {@code maxAmount}) and suppress
   * vanilla. A no-op for factor &lt;= 1 except that it still suppresses vanilla, matching the intent
   * that the multiplier owns the loot.
   */
  public void multiply(int factor, int maxAmount) {
    int safeFactor = Math.max(1, factor);
    int cap = Math.max(1, maxAmount);
    if (safeFactor > 1 && naturalOnly && lootSampler != null) {
      multiplySampled(safeFactor, cap);
      return;
    }
    for (int i = 0; i < drops.size(); i++) {
      ItemStackData stack = drops.get(i);
      long scaled = (long) stack.amount() * safeFactor;
      int amount = (int) Math.min(cap, scaled);
      drops.set(i, new ItemStackData(stack.itemKey(), amount, stack.metadata()));
    }
    suppressVanilla();
  }

  /**
   * Scales the block's own loot to {@code factor} breaks by SAMPLING its loot table instead of scaling a
   * single roll. A block whose drops are probabilistic — leaves give a sapling ~5% of the time, a stick
   * ~2%, an apple ~0.5% — would otherwise turn one lucky roll into 65,536 apples, or one unlucky roll
   * into nothing at all. Here the table is re-rolled — up to {@link #MAX_LOOT_SAMPLES} times, never more
   * than the factor itself, stopping early once enough items have been seen — each item's average yield
   * per break is measured, and that average is projected onto the full factor. The fractional remainder
   * is settled by a coin flip, so the expected total is exact and small factors stay honest.
   *
   * <p>Nothing about which items a block can drop is hard-coded: the numbers come from re-rolling the
   * platform's real loot function, so modded blocks, data-pack loot tables and tool/enchantment effects
   * are all reflected automatically.</p>
   */
  private void multiplySampled(int factor, int cap) {
    int maxSamples = Math.min(MAX_LOOT_SAMPLES, factor);
    int minSamples = Math.min(MIN_LOOT_SAMPLES, maxSamples);
    Map<ItemKey, Long> totals = new LinkedHashMap<>();
    long observed = 0;
    int samples = 0;
    while (samples < maxSamples) {
      samples++;
      for (ItemStackData stack : lootSampler.get()) {
        if (stack != null && stack.amount() > 0) {
          totals.merge(stack.itemKey(), (long) stack.amount(), Long::sum);
          observed += stack.amount();
        }
      }
      // A common drop (stone -> cobblestone) is nailed down after a handful of rolls; a RARE one (an
      // apple from leaves, ~0.5%) needs the full budget or it would estimate as "never happens" and the
      // player would never see one no matter how large the multiplier.
      if (samples >= minSamples && observed >= ENOUGH_OBSERVATIONS) {
        break;
      }
    }
    drops.clear();
    for (Map.Entry<ItemKey, Long> entry : totals.entrySet()) {
      double expected = entry.getValue() * (factor / (double) samples);
      long amount = (long) Math.floor(expected);
      if (ThreadLocalRandom.current().nextDouble() < expected - amount) {
        amount++; // settle the fraction, so an item that averages 0.3 per break still shows up
      }
      if (amount > 0) {
        drops.add(new ItemStackData(entry.getKey(), (int) Math.min(cap, amount), Map.of()));
      }
    }
    suppressVanilla();
  }

  /**
   * Ask the sink to POUR this loot out over {@code spreadTicks} instead of spawning it all in one tick.
   * A huge multiplier resolves to thousands of item entities, and spawning them in a single tick is what
   * freezes the server; streaming them from the break position spreads that cost over time while the
   * player watches the pile grow. {@code <= 1} (the default) keeps the instant, single-tick emission.
   */
  public void spreadOver(int spreadTicks) {
    this.spreadTicks = Math.max(0, spreadTicks);
  }

  /** How many ticks the sink should spread this loot over; {@code <= 1} means emit it all at once. */
  public int spreadTicks() {
    return spreadTicks;
  }
}
