package com.sexidium.core.game.hud;

/**
 * The visibility state of a {@link GameHud} for one player, cycled by the toggle gestures (double-tap
 * sneak on PC, look straight up on mobile). {@link #FULL} shows every contributor's complete output,
 * {@link #COMPACT} a trimmed "just my own info" variant, and {@link #HIDDEN} closes the panel. The
 * cycle order is FULL → COMPACT → HIDDEN → FULL.
 */
public enum HudView {
  FULL,
  COMPACT,
  HIDDEN;

  /** The next state in the FULL → COMPACT → HIDDEN → FULL cycle. */
  public HudView next() {
    return switch (this) {
      case FULL -> COMPACT;
      case COMPACT -> HIDDEN;
      case HIDDEN -> FULL;
    };
  }

  public boolean isHidden() {
    return this == HIDDEN;
  }
}
