package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;

import org.junit.jupiter.api.Test;

class MenuCatalogTest {
  private static final ItemKey ICON = ItemKey.minecraft("stone");

  // The visibility predicates here ignore the viewer, so a null player is fine for filtering tests.
  private static MenuTab tab(String id) {
    return MenuTab.of(id, ICON, null, id, List.of(), player -> {});
  }

  @Test
  void registersAndLooksUpById() {
    MenuCatalog catalog = new MenuCatalog().register(tab("a")).register(tab("b"));
    assertEquals(List.of("a", "b"), catalog.tabs().stream().map(MenuTab::id).toList());
    assertEquals("b", catalog.byId("b").id());
    assertNull(catalog.byId("missing"));
  }

  @Test
  void visibleForFiltersHiddenTabs() {
    MenuCatalog catalog = new MenuCatalog()
        .register(tab("always"))
        .register(tab("admin").visibleWhen(viewer -> false))
        .register(tab("also"));
    assertEquals(List.of("always", "also"),
        catalog.visibleFor(null).stream().map(MenuTab::id).toList());
  }

  @Test
  void ignoresNullRegistration() {
    MenuCatalog catalog = new MenuCatalog().register(null).register(tab("a"));
    assertEquals(1, catalog.tabs().size());
  }
}
