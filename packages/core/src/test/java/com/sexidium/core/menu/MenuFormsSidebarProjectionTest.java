package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MenuFormsSidebarProjectionTest {
  private static final ItemKey DUMMY_ICON = ItemKey.minecraft("stone");

  @Test
  @DisplayName("blank separator panes are excluded from both actions and labels")
  void separatorsAreIgnoredByMenuForms() {
    SidebarScreen.Builder screen = SidebarScreen.of("Test Screen")
        .sidebar(0, MenuButton.of(DUMMY_ICON, "Sidebar 0", ctx -> {}))
        .content(2, MenuButton.of(DUMMY_ICON, "Content 2", ctx -> {}))
        .back(ctx -> {});

    MenuView view = screen.build();

    List<Map.Entry<Integer, MenuButton>> actions = MenuForms.actions(view);
    List<MenuButton> labels = MenuForms.labels(view);

    // Separators (slots 1, 10, 19, 28, 37, 46) must not be in actions or labels
    for (int sepSlot : ChestLayout.SEPARATOR_SLOTS) {
      assertFalse(actions.stream().anyMatch(e -> e.getKey() == sepSlot),
          "separator slot " + sepSlot + " should not be an action");
      assertFalse(labels.stream().anyMatch(l -> " ".equals(l.name())),
          "blank separator label should be omitted from body text");
    }

    // Actions must contain the clickable buttons in ascending slot order: slot 0, slot 2, slot 47
    assertEquals(List.of(0, 2, ChestLayout.BACK_SLOT),
        actions.stream().map(Map.Entry::getKey).toList());
  }

  @Test
  @DisplayName("PaginatedScreen projects body labels and interactive buttons correctly for Bedrock forms")
  void paginatedScreenProjection() {
    PaginatedScreen.PaginatedBuilder<String> screen = PaginatedScreen.<String>of("Paginated Test")
        .items(List.of("Item A", "Item B"))
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .sidebar(0, MenuButton.of(DUMMY_ICON, "Add Item", ctx -> {}))
        .back(ctx -> {});

    MenuView view = screen.build();

    List<Map.Entry<Integer, MenuButton>> actions = MenuForms.actions(view);
    List<MenuButton> labels = MenuForms.labels(view);

    // Clickable buttons: slot 0 (Add Item), slot 2 (Item A), slot 3 (Item B), slot 47 (Back)
    assertEquals(List.of(0, 2, 3, ChestLayout.BACK_SLOT),
        actions.stream().map(Map.Entry::getKey).toList());
    assertEquals(List.of("Add Item", "Item A", "Item B", "<red>« Back</red>"),
        actions.stream().map(e -> e.getValue().name()).toList());

    // Blank separator panes are ignored
    assertTrue(labels.isEmpty());
  }

  @Test
  @DisplayName("empty paginated screen projects empty indicator as body label and back button as action")
  void emptyPaginatedScreenProjection() {
    PaginatedScreen.PaginatedBuilder<String> screen = PaginatedScreen.<String>of("Empty Screen")
        .items(List.of())
        .itemMapper(s -> MenuButton.of(DUMMY_ICON, s, ctx -> {}))
        .back(ctx -> {});

    MenuView view = screen.build();

    List<Map.Entry<Integer, MenuButton>> actions = MenuForms.actions(view);
    List<MenuButton> labels = MenuForms.labels(view);

    // Action: only Back button at slot 47
    assertEquals(1, actions.size());
    assertEquals(ChestLayout.BACK_SLOT, actions.get(0).getKey());

    // Label: empty indicator at slot 24
    assertEquals(1, labels.size());
    assertTrue(labels.get(0).name().contains("No items found"));
  }

  @Test
  @DisplayName("54-slot double chest projects actions in logical reading order: Sidebar -> Content -> Bottom Nav")
  void logicalReadingOrderProjection() {
    SidebarScreen.Builder screen = SidebarScreen.of("Reading Order Test")
        .sidebar(0, MenuButton.of(DUMMY_ICON, "Sidebar Tab 0", ctx -> {}))
        .sidebar(9, MenuButton.of(DUMMY_ICON, "Sidebar Tab 1", ctx -> {}))
        .sidebar(18, MenuButton.of(DUMMY_ICON, "Sidebar Tab 2", ctx -> {}))
        .content(2, MenuButton.of(DUMMY_ICON, "Content Item 1", ctx -> {}))
        .content(11, MenuButton.of(DUMMY_ICON, "Content Item 2", ctx -> {}))
        .content(20, MenuButton.of(DUMMY_ICON, "Content Item 3", ctx -> {}))
        .primaryAction(MenuButton.of(DUMMY_ICON, "Primary Action", ctx -> {}))
        .back(ctx -> {});

    MenuView view = screen.build();

    List<Map.Entry<Integer, MenuButton>> actions = MenuForms.actions(view);

    // Order must strictly be: Sidebar (0, 9, 18) -> Content (2, 11, 20) -> Bottom Nav (47, 53)
    List<Integer> expectedSlots = List.of(0, 9, 18, 2, 11, 20, ChestLayout.SLOT_BACK, ChestLayout.SLOT_PRIMARY);
    assertEquals(expectedSlots, actions.stream().map(Map.Entry::getKey).toList());
  }
}
