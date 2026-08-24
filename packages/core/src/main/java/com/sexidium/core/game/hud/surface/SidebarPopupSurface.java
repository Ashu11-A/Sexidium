package com.sexidium.core.game.hud.surface;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.hud.HudElement;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.model.PopupType;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A one-shot surface rendered through the vanilla popup path — the action bar, or a title for the
 * loud types — for players the platform driver is not reaching.
 *
 * <p>A popup has no per-player state worth keeping: {@link #show} fires it, the platform expires it,
 * and {@link #hide} has nothing to take down. Only the first drawable element is rendered, because
 * an action bar is one line and stacking rows into it produces noise rather than a readout.</p>
 */
final class SidebarPopupSurface implements HudSurfaceHandle {
  private final HudSurfaceSpec spec;
  private final Supplier<UiAdapter> uiSupplier;
  private final Predicate<PlayerAdapter> suppressed;
  private final HudValues values = new HudValues();
  private volatile boolean closed;

  SidebarPopupSurface(HudSurfaceSpec spec, Supplier<UiAdapter> uiSupplier, Predicate<PlayerAdapter> suppressed) {
    this.spec = spec;
    this.uiSupplier = uiSupplier;
    this.suppressed = suppressed == null ? player -> false : suppressed;
  }

  @Override
  public boolean active() {
    return !closed && uiSupplier.get() != null;
  }

  @Override
  public boolean activeFor(PlayerAdapter playerAdapter) {
    return active() && playerAdapter != null && !suppressed.test(playerAdapter);
  }

  @Override
  public void text(String key, LocalizedText value) {
    values.text(key, value);
  }

  @Override
  public void number(String key, double value) {
    values.number(key, value);
  }

  @Override
  public void flag(String key, boolean value) {
    values.flag(key, value);
  }

  @Override
  public void progress(String key, double value) {
    values.progress(key, value);
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
    if (!activeFor(playerAdapter)) {
      return;
    }
    LocalizedText line = firstLine();
    if (line == null) {
      return;
    }
    uiSupplier.get().showPopup(playerAdapter, popupType(), line);
  }

  /**
   * The vanilla surface that means what the spec's anchor means.
   *
   * <p>A centred surface is the game interrupting the player, and the vanilla equivalent of that is a
   * TITLE — big, in the middle, and replaced in place when the next one arrives. Everything else is a
   * readout, which is the action bar. Reading the anchor rather than taking a type from the caller is
   * what keeps the two drivers rendering the same declaration: the consumer says "centre" once, and
   * both the overlay and the player without it put it in the middle of the screen.</p>
   */
  private PopupType popupType() {
    return spec.anchor() == HudAnchor.CENTER ? PopupType.COUNTDOWN : PopupType.INFO;
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
    // Nothing to take down: the platform expires an action bar and a title on its own.
  }

  @Override
  public void close() {
    closed = true;
  }

  private LocalizedText firstLine() {
    for (HudElement element : spec.elements()) {
      LocalizedText line = values.render(element);
      if (line != null) {
        return line;
      }
    }
    return null;
  }
}
