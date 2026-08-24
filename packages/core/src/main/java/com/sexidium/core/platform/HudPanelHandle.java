package com.sexidium.core.platform;

import com.sexidium.core.i18n.LocalizedText;

public interface HudPanelHandle {
  HudPanelHandle NOOP = new HudPanelHandle() {
    @Override
    public void line(int index, LocalizedText localizedText) {
    }

    @Override
    public void removeLine(int index) {
    }

    @Override
    public void show(PlayerAdapter playerAdapter) {
    }

    @Override
    public void hide(PlayerAdapter playerAdapter) {
    }

    @Override
    public void close() {
    }
  };

  void line(int index, LocalizedText localizedText);

  void removeLine(int index);

  /**
   * Pushes any pending {@link #line}/{@link #removeLine} changes to the viewers. Implementations may
   * batch line mutations and only re-render on {@code refresh()}, so callers should invoke this once
   * after updating a group of lines rather than relying on each {@code line()} call to repaint.
   */
  default void refresh() {
  }

  void show(PlayerAdapter playerAdapter);

  void hide(PlayerAdapter playerAdapter);

  /**
   * Stops tracking a player WITHOUT restoring whatever display they had before this panel took over —
   * used when another system has already claimed the player's sidebar slot (e.g. they joined a match, so
   * the match's board is now showing). Restoring here would steal that board. The default falls back to
   * the restoring {@link #hide} + {@link #close} for adapters that do not hijack a shared slot.
   */
  default void forget(PlayerAdapter playerAdapter) {
    hide(playerAdapter);
    close();
  }

  void close();
}
