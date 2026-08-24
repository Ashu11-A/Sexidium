package com.sexidium.core.platform.hud;

/**
 * How a {@link HudElement.Bar} draws its fill.
 *
 * <p>A driver that cannot draw the requested style is expected to pick its nearest neighbour rather
 * than skip the element — the sidebar fallback, for one, renders every style as a run of block glyphs.</p>
 */
public enum BarStyle {
  /** A continuous fill from empty to full. */
  CONTINUOUS,
  /** Discrete pips, for a value that is naturally countable (lives, hearts, segments). */
  SEGMENTED
}
