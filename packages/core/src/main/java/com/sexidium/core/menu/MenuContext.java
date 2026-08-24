package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;

/** Passed to a {@link MenuButton} click handler: who clicked and how. */
public record MenuContext(PlayerAdapter player, ClickType clickType) {
  public enum ClickType {
    LEFT,
    RIGHT,
    SHIFT_LEFT,
    SHIFT_RIGHT,
    MIDDLE,
    OTHER
  }
}
