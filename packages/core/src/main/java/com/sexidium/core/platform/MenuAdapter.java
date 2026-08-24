package com.sexidium.core.platform;

import com.sexidium.core.menu.MenuView;

/**
 * Renders a platform-agnostic {@link MenuView} as a chest GUI for a player and routes slot clicks
 * back to the view's button handlers. Paper uses InvUI; NeoForge uses a vanilla container menu.
 */
public interface MenuAdapter {
  MenuAdapter NOOP = (player, view) -> {
  };

  void open(PlayerAdapter player, MenuView view);

  /** Closes any Sexidium menu currently open for the player. */
  default void close(PlayerAdapter player) {
  }

  /**
   * Whether this player is looking at a Sexidium menu right now.
   *
   * <p>Asked before a screen is redrawn in place: a list of other players' worlds is built from rows
   * the owners keep changing, and re-opening it is the only way to show a change that happened after
   * it was drawn. Re-opening it for somebody who has already closed it would shove a chest GUI back
   * into their face, so the redraw is conditional on this — and defaults to {@code false}, which
   * means a platform that cannot answer simply never redraws (the behaviour before it existed).</p>
   */
  default boolean isOpen(PlayerAdapter player) {
    return false;
  }

  /**
   * Closes every Sexidium menu open on this server, for everyone.
   *
   * <p>Called on teardown and at the start of a drain. Without it a chest GUI survives the listener
   * that cancels its clicks, and the buttons in it become real items a player can take out — so the
   * cost of skipping this is not a cosmetic glitch, it is item duplication.</p>
   */
  default void closeAll() {
  }
}
