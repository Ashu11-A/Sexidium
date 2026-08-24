package com.sexidium.core.game.experience.compose;

/**
 * Ordered phases a {@link DropContributor} runs in. A generator (e.g. Randomizer remap) populates
 * the loot before a transform (e.g. Double Drops multiplier) scales the accumulated list, so the
 * multiplier applies to whatever another challenge produced.
 */
public enum DropPhase {
  /** Produce/replace the base loot (Randomizer remap, sweep loot injection). */
  GENERATE,
  /** Scale or rewrite the accumulated loot (Double Drops multiply). */
  TRANSFORM,
  /** Remove or clamp entries (caps, blacklists). */
  FILTER,
  /** Final observers; the host emits after this phase. */
  SINK
}
