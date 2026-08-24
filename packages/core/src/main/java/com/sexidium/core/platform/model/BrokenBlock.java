package com.sexidium.core.platform.model;

import java.util.List;

/**
 * The result of a type-filtered block removal (see {@code WorldAdapter.breakIfTypeNatural}): the
 * block type that was actually removed plus its resolved natural loot. Keeping the original block
 * {@link #type()} alongside the {@link #drops()} lets a sweep route loot through the drop pipeline
 * keyed by the broken block — exactly as a manual break does — so transforms like the Randomizer's
 * per-block remap apply consistently instead of being keyed by the drop item.
 */
public record BrokenBlock(ItemKey type, List<ItemStackData> drops) {
}
