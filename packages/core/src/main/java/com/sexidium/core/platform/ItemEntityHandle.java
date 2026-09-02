package com.sexidium.core.platform;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.UUID;

/**
 * A platform handle to a single dropped item entity on the ground, so the core
 * {@link com.sexidium.core.game.experience.compose.StackMergeService} can track over-cap loot stacks and
 * consolidate them over time without depending on Bukkit/Minecraft types. Mirrors {@link MobHandle}.
 *
 * <p>An over-cap stack (more than the vanilla 64) is a normal item entity whose amount has been grown;
 * the handle reads and edits that amount in place so two stacks can pour into each other.</p>
 */
public interface ItemEntityHandle {
  /** The entity's unique id, used to dedupe and to tell two handles apart. */
  UUID id();

  /** False once the entity is gone (picked up, despawned, removed) — pruned from monitoring. */
  boolean valid();

  /** The item type carried by the stack. */
  ItemKey itemKey();

  /** Current item count (may exceed the vanilla 64 for a merged stack). */
  int amount();

  /** Set the item count. A value of {@code <= 0} should be followed by {@link #remove()} by the caller. */
  void setAmount(int amount);

  /** Despawn the entity (its items have been moved into another stack). */
  void remove();

  /** Current world position of the entity. */
  WorldPosition position();

  /**
   * Pushes the entity with the given per-tick velocity, used to fly a stack toward the pile it is being
   * merged into instead of letting it blink out of existence. Returns {@code false} when the platform
   * cannot move item entities — the caller then merges the stack immediately rather than waiting for an
   * animation that will never arrive. Default: unsupported.
   */
  default boolean setVelocity(double velocityX, double velocityY, double velocityZ) {
    return false;
  }

  /** Name of the world the entity is in. */
  String worldName();
}
