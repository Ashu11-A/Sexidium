package com.sexidium.core.platform.hud;

/**
 * Whether a surface stays on screen until it is hidden, or shows itself once and expires.
 *
 * <p>The distinction is not cosmetic: a driver renders the two through completely different backing
 * objects (on BetterHud, a {@code hud} versus a {@code popup}), and only a PERSISTENT surface is a
 * candidate for the sidebar fallback — a toast that has already expired has nothing to fall back to.</p>
 */
public enum HudSurfaceKind {
  /** Shown until hidden. Values are pushed continuously and re-read every frame. */
  PERSISTENT,
  /** Fired once per occurrence, expires on its own after the spec's duration. */
  POPUP
}
