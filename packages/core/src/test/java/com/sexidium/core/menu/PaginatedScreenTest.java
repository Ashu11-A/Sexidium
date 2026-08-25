package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaginatedScreenTest {
  private static final ItemKey DUMMY_ICON = ItemKey.minecraft("paper");

  @Test
  @DisplayName("empty list renders empty indicator, 1 page, and back button without next/prev controls")
  void emptyListPagination() {
    PaginatedScreen.PaginatedBuilder<String> screen = PaginatedScreen.<String>of("<gold>Empty List</gold>")
        .items(List.of())
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .back(ctx -> {})
        .onPageChange(p -> {});

    assertEquals(1, screen.totalPages());
    assertEquals(0, screen.clampedPage());

    MenuView view = screen.build();
    assertEquals(54, view.size());
    assertNotNull(view.button(ChestLayout.BACK_SLOT), "back button at slot 47");
    assertNotNull(view.button(24), "empty indicator at center slot 24");
    assertTrue(view.button(24).name().contains("No items found"));

    assertNull(view.button(ChestLayout.PREV_PAGE_SLOT), "no prev page on page 0");
    assertNull(view.button(ChestLayout.NEXT_PAGE_SLOT), "no next page on single-page view");
    assertNull(view.button(ChestLayout.PAGE_INFO_SLOT), "no page info on single-page view");
  }

  @Test
  @DisplayName("single full page (28 items) renders all content slots without pagination buttons")
  void singleFullPage() {
    List<String> items = new ArrayList<>();
    for (int i = 0; i < 28; i++) {
      items.add("Item #" + i);
    }

    PaginatedScreen.PaginatedBuilder<String> screen = PaginatedScreen.<String>of("<gold>Full Page</gold>")
        .items(items)
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .back(ctx -> {})
        .onPageChange(p -> {});

    assertEquals(1, screen.totalPages());
    assertEquals(0, screen.clampedPage());

    MenuView view = screen.build();
    for (int i = 0; i < 28; i++) {
      int slot = ChestLayout.contentSlot(i);
      MenuButton btn = view.button(slot);
      assertNotNull(btn, "button at slot " + slot);
      assertEquals("Item #" + i, btn.name());
    }

    assertNull(view.button(ChestLayout.PREV_PAGE_SLOT));
    assertNull(view.button(ChestLayout.NEXT_PAGE_SLOT));
    assertNull(view.button(ChestLayout.PAGE_INFO_SLOT));
    assertNotNull(view.button(ChestLayout.BACK_SLOT));
  }

  @Test
  @DisplayName("multi-page screen (75 items across 3 pages) renders navigation controls and handles page navigation")
  void multiPageNavigation() {
    List<String> items = new ArrayList<>();
    for (int i = 0; i < 75; i++) {
      items.add("Item #" + i);
    }

    AtomicInteger requestedPage = new AtomicInteger(-1);

    // Page 0 (items 0..27)
    PaginatedScreen.PaginatedBuilder<String> screen0 = PaginatedScreen.<String>of("<gold>Multi Page</gold>")
        .items(items)
        .page(0)
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .back(ctx -> {})
        .onPageChange(requestedPage::set);

    assertEquals(3, screen0.totalPages());
    assertEquals(0, screen0.clampedPage());

    MenuView view0 = screen0.build();
    assertEquals("Item #0", view0.button(ChestLayout.contentSlot(0)).name());
    assertEquals("Item #27", view0.button(ChestLayout.contentSlot(27)).name());
    assertNull(view0.button(ChestLayout.PREV_PAGE_SLOT));
    assertNotNull(view0.button(ChestLayout.NEXT_PAGE_SLOT));
    assertNotNull(view0.button(ChestLayout.PAGE_INFO_SLOT));
    assertTrue(view0.button(ChestLayout.PAGE_INFO_SLOT).name().contains("1/3"));

    // Click Next on page 0 -> requests page 1
    view0.button(ChestLayout.NEXT_PAGE_SLOT).onClick().accept(null);
    assertEquals(1, requestedPage.get());

    // Page 1 (items 28..55)
    PaginatedScreen.PaginatedBuilder<String> screen1 = PaginatedScreen.<String>of("<gold>Multi Page</gold>")
        .items(items)
        .page(1)
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .back(ctx -> {})
        .onPageChange(requestedPage::set);

    MenuView view1 = screen1.build();
    assertEquals("Item #28", view1.button(ChestLayout.contentSlot(0)).name());
    assertEquals("Item #55", view1.button(ChestLayout.contentSlot(27)).name());
    assertNotNull(view1.button(ChestLayout.PREV_PAGE_SLOT));
    assertNotNull(view1.button(ChestLayout.NEXT_PAGE_SLOT));
    assertTrue(view1.button(ChestLayout.PAGE_INFO_SLOT).name().contains("2/3"));

    // Click Prev on page 1 -> requests page 0
    view1.button(ChestLayout.PREV_PAGE_SLOT).onClick().accept(null);
    assertEquals(0, requestedPage.get());

    // Page 2 (items 56..74, 19 items)
    PaginatedScreen.PaginatedBuilder<String> screen2 = PaginatedScreen.<String>of("<gold>Multi Page</gold>")
        .items(items)
        .page(2)
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .back(ctx -> {})
        .onPageChange(requestedPage::set);

    MenuView view2 = screen2.build();
    assertEquals("Item #56", view2.button(ChestLayout.contentSlot(0)).name());
    assertEquals("Item #74", view2.button(ChestLayout.contentSlot(18)).name());
    assertNull(view2.button(ChestLayout.contentSlot(19)), "slot 19 empty on last partial page");
    assertNotNull(view2.button(ChestLayout.PREV_PAGE_SLOT));
    assertNull(view2.button(ChestLayout.NEXT_PAGE_SLOT));
    assertTrue(view2.button(ChestLayout.PAGE_INFO_SLOT).name().contains("3/3"));
  }

  @Test
  @DisplayName("out-of-bounds page indices are clamped safely")
  void pageIndexClamping() {
    List<String> items = List.of("A", "B", "C");

    PaginatedScreen.PaginatedBuilder<String> underflow = PaginatedScreen.<String>of("Underflow")
        .items(items)
        .page(-10);
    assertEquals(0, underflow.clampedPage());

    PaginatedScreen.PaginatedBuilder<String> overflow = PaginatedScreen.<String>of("Overflow")
        .items(items)
        .page(999);
    assertEquals(0, overflow.clampedPage());
  }

  @Test
  @DisplayName("custom sidebar shortcuts and separators are rendered alongside paginated content")
  void sidebarAndSeparatorsInPaginatedScreen() {
    PaginatedScreen.PaginatedBuilder<String> screen = PaginatedScreen.<String>of("Sidebar Test")
        .items(List.of("One", "Two"))
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .sidebar(0, MenuButton.of(DUMMY_ICON, "Sidebar 0", ctx -> {}))
        .sidebar(9, MenuButton.of(DUMMY_ICON, "Sidebar 9", ctx -> {}))
        .back(ctx -> {});

    MenuView view = screen.build();
    assertEquals("Sidebar 0", view.button(0).name());
    assertEquals("Sidebar 9", view.button(9).name());

    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(view.button(sep), "separator at " + sep);
    }
  }
}
