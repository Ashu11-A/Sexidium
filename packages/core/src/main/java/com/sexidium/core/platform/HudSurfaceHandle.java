package com.sexidium.core.platform;

import com.sexidium.core.i18n.LocalizedText;

/**
 * A live surface a {@link HudDriver} opened from a
 * {@link com.sexidium.core.platform.hud.HudSurfaceSpec}: the caller's handle for pushing values into
 * it, showing it to players, and taking it down.
 *
 * <h2>Values are typed</h2>
 * The seam this replaces had exactly one mutator, {@code value(String, String)}, which meant a
 * duration, a percentage and a boolean all arrived as pre-formatted English and no renderer could do
 * anything with them but print them. Typed setters let a driver format a number in the viewer's
 * locale, fill a bar from a ratio, and evaluate a flag as a visibility condition — and let the
 * sidebar fallback render the same pushed value as a line of text.
 *
 * <p>All four mutators are cheap and idempotent: callers push on every HUD refresh rather than
 * tracking what changed, and {@link #refresh()} flushes a batch.</p>
 *
 * <h2>{@link #active()} versus {@link #activeFor(PlayerAdapter)}</h2>
 * The most load-bearing distinction on the seam, and the reason the old one was a per-player
 * predicate rather than a flag. Every real surface has viewers it cannot reach — BetterHud rides boss
 * bars, which Geyser does not render and which BetterHud itself switches off for Floodgate players —
 * so a Bedrock player standing next to a Java player gets nothing from the same handle. Anything that
 * decides "the surface has this covered, drop the fallback" must decide it from
 * {@link #activeFor(PlayerAdapter)}. Deciding it from {@link #active()} blanks the screen of every
 * viewer the surface does not reach.
 *
 * <p>Callers no longer have to remember that: opening a surface through the composite driver installs
 * the sidebar fallback automatically, per player, on exactly this signal.</p>
 */
public interface HudSurfaceHandle {
  /** The handle a driver returns when it cannot draw the spec at all. Never active, never renders. */
  HudSurfaceHandle NOOP = new HudSurfaceHandle() {
    @Override
    public void text(String key, LocalizedText value) {
    }

    @Override
    public void number(String key, double value) {
    }

    @Override
    public void flag(String key, boolean value) {
    }

    @Override
    public void progress(String key, double value) {
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

  /**
   * Whether this surface renders somewhere <em>anybody</em> can see it. False for {@link #NOOP}, and
   * false for a real handle whose platform call has failed and been given up on.
   *
   * <p>Prefer {@link #activeFor(PlayerAdapter)} for any decision about one player's screen.</p>
   */
  default boolean active() {
    return false;
  }

  /** Whether this surface is drawing for ONE player right now. Defaults to the coarse {@link #active()}. */
  default boolean activeFor(PlayerAdapter playerAdapter) {
    return active();
  }

  /**
   * Publishes the value of a {@link com.sexidium.core.platform.hud.HudElement.TextRow}, rendered per
   * viewer in that viewer's own language.
   */
  void text(String key, LocalizedText value);

  /** Publishes a numeric value, formatted by the driver rather than by the caller. */
  void number(String key, double value);

  /** Publishes a boolean, which a driver may use as a per-player visibility condition. */
  void flag(String key, boolean value);

  /** Publishes a {@link com.sexidium.core.platform.hud.HudElement.Bar}'s fill, clamped to 0..1. */
  void progress(String key, double value);

  /**
   * Declares that a row is deliberately showing nothing, or takes that declaration back.
   *
   * <p>Not the same as pushing an empty value, and not the same as never pushing one. An unpushed key
   * draws the unset dash, which is a readout saying it has lost track; a blanked one is a readout
   * saying this row is switched off. Each driver honours that in the only way its surface allows — a
   * pixel-addressed overlay draws an empty slot nobody can see, a line-addressed sidebar drops the row
   * rather than leave a hole in the middle of the panel.</p>
   *
   * <p>The value under the key is kept, so un-blanking restores what was last published rather than a
   * dash. Default no-op: a driver with nothing to hide loses nothing by ignoring it.</p>
   */
  default void blank(String key, boolean blanked) {
  }

  /** Starts showing this surface to a player. For a popup, fires it once. */
  void show(PlayerAdapter playerAdapter);

  /** Stops showing this surface to a player. */
  void hide(PlayerAdapter playerAdapter);

  /** Flushes pending value changes to the viewers, for a driver that batches. */
  default void refresh() {
  }

  /** Tears the surface down for everyone and releases whatever the driver allocated for it. */
  void close();
}
