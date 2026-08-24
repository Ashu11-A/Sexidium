package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class MenuFormsTest {
  private static final ItemKey ICON = ItemKey.minecraft("stone");

  @Test
  void actionsAreClickableButtonsInAscendingSlotOrder() {
    MenuView view = new MenuView("Title", 3);
    // Inserted out of slot order on purpose to prove the helper sorts by slot.
    view.set(20, MenuButton.of(ICON, "third", ctx -> {}));
    view.set(10, MenuButton.of(ICON, "first", ctx -> {}));
    view.set(14, MenuButton.label(ICON, "filler", List.of()));
    view.set(16, MenuButton.of(ICON, "second", ctx -> {}));

    List<Map.Entry<Integer, MenuButton>> actions = MenuForms.actions(view);

    assertEquals(List.of(10, 16, 20), actions.stream().map(Map.Entry::getKey).toList());
    assertEquals(List.of("first", "second", "third"), actions.stream().map(e -> e.getValue().name()).toList());
  }

  @Test
  void labelsAreNonInteractiveNamedButtonsAndExcludeBlankAndActions() {
    MenuView view = new MenuView("Title", 3);
    view.set(0, MenuButton.label(ICON, "header", List.of()));
    view.set(1, MenuButton.label(ICON, "   ", List.of())); // blank name -> skipped
    view.set(2, MenuButton.of(ICON, "clickable", ctx -> {})); // action -> not a label

    List<MenuButton> labels = MenuForms.labels(view);

    assertEquals(1, labels.size());
    assertEquals("header", labels.get(0).name());
  }

  @Test
  void actionButtonIdMapsBackToItsHandler() {
    MenuView view = new MenuView("Title", 1);
    AtomicInteger fired = new AtomicInteger(-1);
    view.set(3, MenuButton.of(ICON, "a", ctx -> fired.set(0)));
    view.set(5, MenuButton.of(ICON, "b", ctx -> fired.set(1)));

    List<Map.Entry<Integer, MenuButton>> actions = MenuForms.actions(view);
    // Simulate a Form response selecting button id 1.
    actions.get(1).getValue().onClick().accept(null);

    assertEquals(1, fired.get());
  }

  @Test
  void nullViewYieldsEmptyLists() {
    assertTrue(MenuForms.actions(null).isEmpty());
    assertTrue(MenuForms.labels(null).isEmpty());
  }
}
