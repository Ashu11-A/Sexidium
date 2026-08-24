package com.sexidium.core.world.hotbar;

import java.util.List;

/** The ordered set of {@link HotbarItem}s shown for one {@link HotbarScope}. */
public final class HotbarProfile {
  private final List<HotbarItem> items;

  public HotbarProfile(List<HotbarItem> items) {
    this.items = List.copyOf(items);
  }

  public List<HotbarItem> items() {
    return items;
  }
}
