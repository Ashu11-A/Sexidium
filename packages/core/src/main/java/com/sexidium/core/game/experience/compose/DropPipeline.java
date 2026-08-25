package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.experience.ExperienceHost;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Host-owned ordered loot pipeline. Challenges register {@link DropContributor}s during
 * {@code Challenge.register}; the host runs every break/sweep/death through here so layered
 * transforms (a multiplier over a remap) compose on one shared list. Registration/ordering live in
 * {@link ContributorRegistry}; this class adds the loot-emission sink. See
 * {@code docs/gameplay/experiences.md}.
 */
public final class DropPipeline extends ContributorRegistry<DropContributor> {
  private final ExperienceHost host;
  private int maxStackSize = 64;

  public DropPipeline(ExperienceHost host) {
    super(Comparator
        .comparingInt((DropContributor contributor) -> contributor.phase().ordinal())
        .thenComparingInt(DropContributor::order));
    this.host = host;
  }

  /** Largest single ground/inventory stack the sink emits; the rest is split into more stacks. */
  public void maxStackSize(int maxStackSize) {
    this.maxStackSize = Math.max(1, maxStackSize);
  }

  /** Runs every contributor over the context (no emission). Returns the resolved loot list. */
  public List<ItemStackData> transform(DropContext context) {
    for (DropContributor contributor : contributors()) {
      contributor.contribute(context);
    }
    return context.drops();
  }

  /**
   * Runs the context through every contributor and emits the resolved loot when needed. Returns true
   * when vanilla loot must be suppressed (the caller flips the native event's {@code dropItems}).
   */
  public boolean process(DropContext context) {
    transform(context);
    if (context.mustEmit() || context.dirty()) {
      emit(context);
    }
    // Never claim loot that was not produced. Suppression means "vanilla must not drop this block,
    // because WE dropped it" — so with an empty list it is a lie that destroys the loot entirely: the
    // block breaks and nothing comes out.
    //
    // The list is empty more often than it looks. A contributor latches suppression while multiplying,
    // and the natural-drop seed is empty for every tool-gated block when the breaker has no tool — which
    // is the state of EVERY player for the first minutes after a world reset, because the reset wipes
    // inventories. That is the "blocks break but nothing drops" report: bare-handed players mining stone.
    return context.vanillaSuppressed() && !context.drops().isEmpty();
  }

  /**
   * Spawns the resolved loot on the ground, splitting oversize stacks. When the context asked to be
   * spread ({@link DropContext#spreadOver}) the stacks are POURED out over that many ticks from the same
   * position instead of all landing in one tick — see {@link #stream}.
   */
  public void emit(DropContext context) {
    List<ItemStackData> drops = context.drops();
    if (drops.isEmpty()) {
      return;
    }
    WorldPosition position = context.position();
    WorldAdapter world = host == null ? null : host.world();
    if (world == null || position == null) {
      return;
    }
    // Emit into the world the BREAK happened in, not whatever world the match currently points at. The
    // two differ for the span of a world reset — the match lease is swapped mid-flight — so a break
    // processed across that boundary would otherwise pour its loot into the world being deleted.
    if (position.worldName() != null) {
      WorldAdapter breakWorld = world.inWorld(position.worldName());
      if (breakWorld != null) {
        world = breakWorld;
      }
    }
    List<ItemStackData> stacks = split(drops);
    if (context.spreadTicks() > 1 && stacks.size() > 1) {
      stream(stacks, world, position, context.spreadTicks());
      return;
    }
    for (ItemStackData stack : stacks) {
      world.dropItem(position, stack);
    }
  }

  /** The loot as concrete ground stacks: every oversize stack cut into {@link #maxStackSize} pieces. */
  private List<ItemStackData> split(List<ItemStackData> drops) {
    List<ItemStackData> stacks = new ArrayList<>();
    for (ItemStackData stack : drops) {
      int remaining = stack.amount();
      while (remaining > 0) {
        int amount = Math.min(maxStackSize, remaining);
        stacks.add(new ItemStackData(stack.itemKey(), amount, stack.metadata()));
        remaining -= amount;
      }
    }
    return stacks;
  }

  /**
   * Pours {@code stacks} out from {@code position} over {@code spreadTicks} ticks, an even share each
   * tick. Spawning several thousand item entities inside one tick is what makes a big multiplier freeze
   * the server; spreading the same spawns over time keeps every tick cheap and turns the payout into a
   * visible fountain at the break. The timer is host-tracked, so it is cancelled with the match.
   */
  private void stream(List<ItemStackData> stacks, WorldAdapter world, WorldPosition position, int spreadTicks) {
    int ticks = Math.min(spreadTicks, stacks.size());
    int perTick = Math.max(1, (stacks.size() + ticks - 1) / ticks);
    // Index into the pending stacks, advanced by the timer below. A one-element array because the
    // lambda needs to mutate it and the task cannot see itself until runTimer returns.
    int[] next = {0};
    ScheduledTask[] task = new ScheduledTask[1];
    // Delay 1, not 0: the first batch must land AFTER runTimer returns, so the task can cancel itself.
    ScheduledTask scheduled = host.runTimer(() -> {
      int end = Math.min(stacks.size(), next[0] + perTick);
      for (int index = next[0]; index < end; index++) {
        world.dropItem(position, stacks.get(index));
      }
      next[0] = end;
      if (next[0] >= stacks.size() && task[0] != null) {
        task[0].cancel();
      }
    }, 1L, 1L);
    task[0] = scheduled;
    if (scheduled == null) {
      // No scheduler on this host: drop the loot now rather than losing it. Nothing has been emitted
      // yet (the first batch was scheduled a tick out), so this cannot double up.
      for (ItemStackData stack : stacks) {
        world.dropItem(position, stack);
      }
    }
  }
}
