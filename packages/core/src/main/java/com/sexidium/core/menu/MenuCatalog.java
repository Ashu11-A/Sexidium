package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered registry of {@link MenuTab}s that make up the {@code /sx} hub. {@link MenuService}
 * registers every interface here once; {@link MenuService#openMain} renders the catalog into a
 * centered icon grid. A new interface becomes reachable from the hub by registering one tab — the
 * layout, permission filtering, and custom-art wiring are all handled here.
 */
public final class MenuCatalog {
  private final List<MenuTab> tabs = new ArrayList<>();

  public MenuCatalog register(MenuTab tab) {
    if (tab != null) {
      tabs.add(tab);
    }
    return this;
  }

  /** All registered tabs in declaration order. */
  public List<MenuTab> tabs() {
    return List.copyOf(tabs);
  }

  /** The tabs a given viewer may see (permission/visibility filtered), in declaration order. */
  public List<MenuTab> visibleFor(PlayerAdapter viewer) {
    List<MenuTab> visible = new ArrayList<>();
    for (MenuTab tab : tabs) {
      if (tab.visibleTo(viewer)) {
        visible.add(tab);
      }
    }
    return visible;
  }

  /** The tab with the given id, or {@code null}. */
  public MenuTab byId(String id) {
    for (MenuTab tab : tabs) {
      if (tab.id().equals(id)) {
        return tab;
      }
    }
    return null;
  }
}
