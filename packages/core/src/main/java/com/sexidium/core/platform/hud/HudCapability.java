package com.sexidium.core.platform.hud;

/**
 * One thing a {@link com.sexidium.core.platform.HudDriver} may or may not be able to draw.
 *
 * <p>Asked BEFORE a surface is opened, so a consumer can pick a spec the driver can honour instead of
 * declaring a bar and silently getting nothing. Every driver answers honestly for itself — including
 * "yes I am installed, but my shaders do not match this client's pack format, so I cannot draw
 * anything", which is exactly the state that turns BetterHud's readout into a row of white boxes.</p>
 */
public enum HudCapability {
  /** Text rows positioned freely on the surface. */
  TEXT,
  /** A filled progress bar. */
  BAR,
  /** An item/texture icon. */
  ICON,
  /** Fire-and-forget surfaces of kind {@link HudSurfaceKind#POPUP}. */
  POPUP,
  /** Per-player conditional visibility evaluated by the driver rather than by the caller. */
  CONDITION
}
