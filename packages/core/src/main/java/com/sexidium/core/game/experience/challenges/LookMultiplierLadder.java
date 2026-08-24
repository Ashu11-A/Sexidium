package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Pure progression maths for the Look-Multiplies challenge: an ordered ladder of item milestones and the
 * multiplier each one grants. Kept free of any platform/state dependency so the level/multiplier/progress
 * logic can be unit-tested directly.
 *
 * <p>A {@code level} is the number of milestones already cleared: level 0 is the start (multiplier =
 * {@code startingMultiplier}), level {@code L} means the first {@code L} rungs are done and the
 * multiplier is that of the {@code L}-th rung. The rung a player is currently working toward is
 * {@link #next(int)} — its {@code item} and {@code count} are what the HUD shows as "next required".</p>
 */
final class LookMultiplierLadder {
  /** One rung: hold {@code count} of {@code item} (collectively) to step the multiplier to {@code multiplier}. */
  record Milestone(ItemKey item, int count, int multiplier) {
  }

  private final List<Milestone> milestones;
  private final int startingMultiplier;

  LookMultiplierLadder(List<Milestone> milestones, int startingMultiplier) {
    this.milestones = List.copyOf(milestones);
    this.startingMultiplier = Math.max(1, startingMultiplier);
  }

  int size() {
    return milestones.size();
  }

  boolean maxed(int level) {
    return level >= milestones.size();
  }

  /** The multiplier (copies per look-tick) at a given cleared-milestone count, clamped into range. */
  int multiplierAt(int level) {
    if (level <= 0 || milestones.isEmpty()) {
      return startingMultiplier;
    }
    int index = Math.min(level, milestones.size()) - 1;
    return Math.max(1, milestones.get(index).multiplier());
  }

  /** The rung being worked toward at this level, or {@code null} when the ladder is maxed out. */
  Milestone next(int level) {
    return maxed(level) ? null : milestones.get(Math.max(0, level));
  }

  /**
   * Advances the level as far as the collective inventory allows: while the next rung's item is held in
   * at least its required count, clear it and look at the following rung (several rungs can clear at once,
   * as when a large stack satisfies two thresholds together). Never regresses — returns a level {@code >=}
   * the input.
   */
  int advance(int level, ToIntFunction<ItemKey> collectiveHeld) {
    int current = Math.max(0, level);
    while (current < milestones.size()) {
      Milestone rung = milestones.get(current);
      if (collectiveHeld.applyAsInt(rung.item()) < Math.max(1, rung.count())) {
        break;
      }
      current++;
    }
    return current;
  }

  /** Fill for the XP progress bar: fraction of the next rung's requirement currently held, or full when maxed. */
  float progress(int level, ToIntFunction<ItemKey> collectiveHeld) {
    Milestone rung = next(level);
    if (rung == null) {
      return 1.0f;
    }
    int required = Math.max(1, rung.count());
    int held = Math.max(0, collectiveHeld.applyAsInt(rung.item()));
    return Math.max(0.0f, Math.min(1.0f, (float) held / required));
  }
}
