package com.sexidium.core.game.experience.compose;

/** Where a {@link DropContext} originated, so contributors can treat paths differently. */
public enum DropSource {
  /** A player's manual block break ({@code BlockBreakGameEvent}); vanilla loot exists as a fallback. */
  BLOCK_BREAK,
  /** A budgeted area sweep (Break-One-Break-All); the block is already removed, items must be emitted. */
  SWEEP,
  /** A TNT / explosion removal; the block is already gone. */
  EXPLOSION,
  /** A mob death. */
  ENTITY_DEATH,
  /** Anything else. */
  OTHER
}
