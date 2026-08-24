package com.sexidium.core.world.hotbar;

import com.sexidium.core.menu.UiItem;

/**
 * A resolved hotbar item ready for the platform to render: the slot to place it in, the routing id to
 * tag onto it (so a click comes back to {@link HotbarController#handleClick}), and its {@link UiItem}
 * visual. Produced by {@link HotbarController#resolve}; the platform never sees the {@link HotbarItem}.
 */
public record HotbarSlot(int slot, String id, UiItem item) {
}
