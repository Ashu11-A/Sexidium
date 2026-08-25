package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single funnel for every block change in an experience. A player's manual break, a
 * Break-One-Break-All sweep, a Random-Chunks conversion and a TNT blast all route loot through the
 * shared {@link DropPipeline} here, so a multiplier reaches loot it never used to see, and all
 * placement/destruction is arbitrated by the registered {@link BlockChangeVeto}s. See
 * {@code docs/gameplay/experiences.md}.
 */
public final class BlockBreakService {
  private final DropPipeline drops;
  private final List<BlockChangeVeto> vetoes = new ArrayList<>();
  // The world-integrity guard, consulted BEFORE any challenge veto: some blocks are off-limits to every
  // mode, no matter what the challenges themselves want.
  private BlockGuard guard = BlockGuard.defaults();

  public BlockBreakService(DropPipeline drops) {
    this.drops = drops;
  }

  /** Installs the server's protected-block policy (read from config once, shared by every challenge). */
  public void guard(BlockGuard guard) {
    this.guard = guard == null ? BlockGuard.defaults() : guard;
  }

  /** The active world-integrity guard — the one list of blocks no challenge may destroy. */
  public BlockGuard guard() {
    return guard;
  }

  /**
   * <b>The check every block-editing challenge must make.</b> True when the block currently at this
   * position may be destroyed or replaced: it is not world-integrity protected, and no challenge veto
   * objects. Anything that edits blocks one at a time — a replication copy, a walking trail, a converted
   * column — goes through here rather than reasoning about protection itself.
   */
  public boolean mayModify(WorldAdapter world, BlockPosition at) {
    if (world == null || at == null) {
      return false;
    }
    ItemKey existing = world.blockTypeAt(at);
    if (guard.isProtected(existing)) {
      return false;
    }
    return allowsBreak(center(at), existing);
  }

  /**
   * The subset of block ids a bulk sweep may erase. A sweep hands the platform a whole SET of ids to
   * remove in one call, so protection has to be applied to that set — this is what stops "break every
   * block of this type everywhere" from taking an End portal frame with it.
   */
  public Set<String> breakableTypes(Set<String> typeValues) {
    return guard.breakableTypes(typeValues);
  }

  /** The world position at the centre of a block, for veto checks that work in world coordinates. */
  public static WorldPosition center(BlockPosition at) {
    return new WorldPosition(at.worldName(), at.blockX() + 0.5, at.blockY() + 0.5, at.blockZ() + 0.5,
        0.0F, 0.0F);
  }

  public void registerVeto(BlockChangeVeto veto) {
    if (veto != null) {
      vetoes.add(veto);
    }
  }

  /** Removes a previously registered veto (live challenge removal / chaos cycle reset). */
  public void unregisterVeto(BlockChangeVeto veto) {
    if (veto != null) {
      vetoes.remove(veto);
    }
  }

  /** True only when every registered veto allows placing {@code type} at {@code position}. */
  public boolean allowsPlace(WorldPosition position, ItemKey type) {
    if (guard.isProtected(type)) {
      return false; // a protected block is never something a challenge gets to scatter around either
    }
    for (BlockChangeVeto veto : vetoes) {
      if (!veto.allowsPlace(position, type)) {
        return false;
      }
    }
    return true;
  }

  /** True only when every registered veto allows breaking/converting at {@code position}. */
  public boolean allowsBreak(WorldPosition position, ItemKey type) {
    if (guard.isProtected(type)) {
      return false; // world integrity outranks every challenge
    }
    for (BlockChangeVeto veto : vetoes) {
      if (!veto.allowsBreak(position, type)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Runs a player's manual block break through the drop pipeline. Returns true when vanilla loot must
   * be suppressed (the caller flips {@code BlockBreakGameEvent.setDropItems(false)}).
   */
  public boolean onManualBreak(PlayerAdapter breaker, BlockPosition blockPosition, ItemKey blockKey) {
    if (blockKey == null || blockPosition == null) {
      return false;
    }
    WorldPosition center = new WorldPosition(blockPosition.worldName(),
        blockPosition.blockX() + 0.5, blockPosition.blockY() + 0.5, blockPosition.blockZ() + 0.5, 0.0F, 0.0F);
    DropContext context = new DropContext(DropSource.BLOCK_BREAK, blockKey, center, breaker, List.of());
    // Resolve the block's natural break loot BEFORE the vanilla break removes it (the block is still
    // present at HIGHEST-priority break time), using the breaker's ACTUAL held tool — a wooden pickaxe on
    // iron ore yields nothing, a bare hand on leaves yields the leaf loot table and never the leaf block.
    // A seedSourceItem() contributor (Double Drops) then multiplies that real drop, not the block item.
    WorldAdapter world = breaker == null ? null : breaker.world();
    // Only when the platform can genuinely compute loot: otherwise an empty result would be read as "this
    // break drops nothing" when it really means "this backend cannot tell", and every break would go
    // empty. Such a platform keeps the older "the block itself is the loot" seed.
    if (world != null && world.resolvesBlockLoot()) {
      context.setNaturalSeed(world.naturalDrops(blockPosition, breaker));
      // The same roll, repeatable: a random loot table (leaves) is re-rolled per call, so a multiplier
      // can sample it instead of scaling one lucky/unlucky roll. Only called while the block still
      // exists, i.e. inside the process() below.
      context.setLootSampler(() -> world.naturalDrops(blockPosition, breaker));
    }
    return drops.process(context);
  }

  /**
   * Resolves the loot for blocks a challenge already removed (a sweep/explosion) WITHOUT emitting,
   * routed through the drop pipeline so multipliers/remaps apply, so the caller can run its own
   * optimized emission (e.g. Break-One-Break-All's merged chunk stacks). The pipeline is keyed by the
   * original {@code blockType} (so the Randomizer remaps the broken block exactly as it does a manual
   * break, not the drop item) with {@code naturalLoot} — the block's already-resolved natural drops —
   * as the base loot at {@code position}.
   */
  public List<ItemStackData> transformRemovedLoot(DropSource source, WorldPosition position, ItemKey blockType,
      List<ItemStackData> naturalLoot, PlayerAdapter cause) {
    if (blockType == null || naturalLoot == null || naturalLoot.isEmpty()) {
      return List.of();
    }
    DropContext context = new DropContext(source, blockType, position, cause, naturalLoot);
    return new ArrayList<>(drops.transform(context));
  }
}
