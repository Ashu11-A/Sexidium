package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;

/**
 * Common contract for chest-GUI screens in Sexidium.
 */
public interface Screen {

  /**
   * Title of this screen (MiniMessage formatted string).
   */
  String title();

  /**
   * Total number of chest inventory rows (1..6). Defaults to 6 (54 slots).
   */
  default int rows() {
    return ChestLayout.ROWS;
  }

  /**
   * Row count used when rendered without resource-pack art (Bedrock / no-pack). Defaults to {@link #rows()}.
   */
  default int plainRows() {
    return rows();
  }

  /**
   * Background frame glyph id (e.g. {@link MenuArt#BG_MAIN}), or {@code null} for plain chest background.
   */
  default String backgroundArt() {
    return null;
  }

  /**
   * Baked screen art scene id (e.g. {@code "main-hub"}), or {@code null} if none.
   */
  default String screenArt() {
    return null;
  }

  /**
   * Whether buttons on this screen carry animated gradient name frames.
   */
  default boolean isAnimated() {
    return false;
  }

  /**
   * Assembles and populates the {@link MenuView} for the given player.
   *
   * @param player viewing player
   * @return built view ready to be sent
   */
  MenuView build(PlayerAdapter player);

  /**
   * Opens this screen for the given player.
   *
   * @param player player to open for
   */
  void open(PlayerAdapter player);

  /**
   * Re-renders or updates the open screen for the player.
   *
   * @param player player viewing the screen
   */
  void refresh(PlayerAdapter player);

  /**
   * Hook called immediately before opening this screen.
   *
   * @param player player opening the screen
   */
  default void onOpen(PlayerAdapter player) {
  }

  /**
   * Hook called when this screen is closed.
   *
   * @param player player closing the screen
   */
  default void onClose(PlayerAdapter player) {
  }
}
