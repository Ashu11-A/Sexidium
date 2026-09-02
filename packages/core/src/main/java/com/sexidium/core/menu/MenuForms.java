package com.sexidium.core.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derives a flat, click-only projection of a {@link MenuView} for renderers that are not slot-based
 * grids — chiefly the Bedrock Cumulus Form renderer on the Paper adapter. A chest {@code MenuView} is a sparse {@code slot -> button} map padded with decorative
 * filler; a Form is a vertical list of buttons with a header. This helper bridges the two without
 * touching {@link MenuService}: it reads the same {@link MenuButton}s the chest renders.
 *
 * <p>The split is purely structural and needs no extra metadata on the button: a button that has a
 * non-null {@link MenuButton#onClick()} is an <b>action</b> (becomes a Form button); a button with a
 * {@code null} handler is a decorative <b>label</b> (becomes Form body text). This matches how
 * {@code MenuService} already builds menus — {@link MenuButton#label}/{@link MenuButton#headLabel}
 * produce null-handler entries, everything interactive carries a handler. Both lists are returned in
 * ascending slot order so the Form reads top-to-bottom like the chest reads left-to-right.</p>
 */
public final class MenuForms {
  private MenuForms() {
  }

  /**
   * The clickable buttons of {@code view}, projected in logical reading order (Sidebar -> Content -> Bottom Nav)
   * for standard 54-slot double chests, and ascending slot order for non-standard views.
   * Each becomes one Form button; the list index is the Form button id, so the renderer maps
   * {@code clickedButtonId() -> onClick}. Separator panes are strictly excluded.
   */
  public static List<Map.Entry<Integer, MenuButton>> actions(MenuView view) {
    List<Map.Entry<Integer, MenuButton>> result = new ArrayList<>();
    if (view == null) {
      return result;
    }
    for (Map.Entry<Integer, MenuButton> entry : ordered(view).entrySet()) {
      MenuButton button = entry.getValue();
      if (button != null && button.onClick() != null) {
        result.add(Map.entry(entry.getKey(), button));
      }
    }
    return result;
  }

  /**
   * The non-interactive decorative buttons of {@code view} (empty-state messages, read-only roster
   * rows, section headers), projected in logical reading order. Blank separator glass panes are excluded.
   * A renderer can join their names/lore into the Form body so the screen still carries its context text.
   */
  public static List<MenuButton> labels(MenuView view) {
    List<MenuButton> result = new ArrayList<>();
    if (view == null) {
      return result;
    }
    for (Map.Entry<Integer, MenuButton> entry : ordered(view).entrySet()) {
      MenuButton button = entry.getValue();
      if (button != null && button.onClick() == null && button.name() != null && !button.name().isBlank()) {
        result.add(button);
      }
    }
    return result;
  }

  private static int sectionRank(int slot) {
    if (ChestLayout.isSidebarSlot(slot)) {
      return 0; // Sidebar (Column 0: 0, 9, 18, 27, 36, 45)
    }
    if (ChestLayout.isContentSlot(slot)) {
      return 1; // Content (Cols 2-8, Rows 0-4)
    }
    if (ChestLayout.isBottomNavSlot(slot)) {
      return 2; // Bottom Nav (Row 5: 47-53)
    }
    return 3;
  }

  private static Map<Integer, MenuButton> ordered(MenuView view) {
    if (view == null) {
      return Map.of();
    }
    if (view.size() == ChestLayout.SIZE) {
      TreeMap<Integer, MenuButton> map = new TreeMap<>((a, b) -> {
        int rankA = sectionRank(a);
        int rankB = sectionRank(b);
        if (rankA != rankB) {
          return Integer.compare(rankA, rankB);
        }
        return Integer.compare(a, b);
      });
      map.putAll(view.buttons());
      return map;
    }
    return new TreeMap<>(view.buttons());
  }
}
