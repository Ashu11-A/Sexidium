package com.sexidium.core.platform.model;

/** Where on the screen a {@link com.sexidium.core.platform.hud.HudSurfaceSpec} is pinned. */
public enum HudAnchor {
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_RIGHT,
  /**
   * The middle of the window, with the surface's own height straddling it.
   *
   * <p>Not a corner, and that difference reaches further than the geometry. A corner is where a
   * readout lives — something you glance at. The centre is where the game interrupts you, which is
   * why the fallback driver renders a centred surface as a vanilla <em>title</em> rather than as a
   * line on the scoreboard: a player without the overlay plugin should still be interrupted.</p>
   */
  CENTER
}
