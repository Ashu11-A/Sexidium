package com.sexidium.core.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Derives a flat, click-only projection of a {@link MenuView} for renderers that are not slot-based
 * grids — chiefly the Bedrock Cumulus Form renderer on the Paper adapter and the NeoForge client
 * overlay. A chest {@code MenuView} is a sparse {@code slot -> button} map padded with decorative
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
   * The clickable buttons of {@code view}, in ascending slot order. Each becomes one Form button; the
   * list index is the Form button id, so the renderer maps {@code clickedButtonId() -> onClick}.
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
   * rows, section headers), in ascending slot order. A renderer can join their names/lore into the
   * Form body so the screen still carries its context text.
   */
  public static List<MenuButton> labels(MenuView view) {
    List<MenuButton> result = new ArrayList<>();
    if (view == null) {
      return result;
    }
    for (MenuButton button : ordered(view).values()) {
      if (button != null && button.onClick() == null && button.name() != null && !button.name().isBlank()) {
        result.add(button);
      }
    }
    return result;
  }

  private static TreeMap<Integer, MenuButton> ordered(MenuView view) {
    return new TreeMap<>(view.buttons());
  }
}
