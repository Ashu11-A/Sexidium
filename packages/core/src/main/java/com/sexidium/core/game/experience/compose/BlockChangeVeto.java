package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;

/**
 * A challenge's say over block changes other challenges make. Registered via
 * {@link ChallengeRegistry#blockVeto(BlockChangeVeto)}. A change is allowed only when EVERY veto
 * allows it, so e.g. Block Deleter ("this type is deleted forever") can stop Walking Blocks from
 * re-placing the deleted type, instead of the two silently fighting each tick.
 */
public interface BlockChangeVeto {
  /** May a challenge place {@code type} at {@code position}? */
  default boolean allowsPlace(WorldPosition position, ItemKey type) {
    return true;
  }

  /** May a challenge break/convert the block at {@code position} (currently {@code type})? */
  default boolean allowsBreak(WorldPosition position, ItemKey type) {
    return true;
  }
}
