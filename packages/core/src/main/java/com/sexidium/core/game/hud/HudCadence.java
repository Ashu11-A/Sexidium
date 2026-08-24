package com.sexidium.core.game.hud;

import com.sexidium.core.platform.ConfigurationAdapter;

/**
 * How often a HUD repaints, resolved in one place.
 *
 * <h2>Why this exists</h2>
 * Every HUD used to read its own key with its own hardcoded default and its own floor: the experience
 * HUD at 20 ticks, the lobby card at 40, the Race board at 10, TNT War at 20, the team card at 20.
 * Nothing tied them together, so "how often does a readout update" had five different answers depending
 * on which screen you were looking at — and the lobby's two-second cadence was the one people noticed,
 * because a ping and a player count that lag a whole second behind look broken rather than cheap.
 *
 * <p>{@link #DEFAULT_PATH} is now the single answer. A mode may still override it — Race genuinely
 * wants a faster board — but an override is a deliberate act rather than an accident of whichever
 * default was typed first.</p>
 *
 * <h2>One second, and why not faster</h2>
 * Twenty ticks is not a compromise for these readouts, it is the resolution of the data. A countdown
 * printed in whole seconds cannot look smoother by being recomputed more often; it can only cost more.
 * What repainting faster DOES buy is responsiveness when a value changes off-cadence — a death, a
 * captured objective — and that is why the floor here is 1 rather than 5: a mode that wants it is not
 * argued with.
 */
public final class HudCadence {
  /** The shared cadence every HUD falls back to. */
  public static final String DEFAULT_PATH = "hud.refresh-ticks";

  /** One second. */
  public static final long DEFAULT_TICKS = 20L;

  private HudCadence() {
  }

  /**
   * The shared cadence.
   *
   * @param configuration may be null (tests, headless hosts), in which case the default stands
   */
  public static long ticks(ConfigurationAdapter configuration) {
    return ticks(configuration, null);
  }

  /**
   * The cadence for one HUD: its own override when the operator has set one, otherwise the shared
   * cadence.
   *
   * @param overridePath a mode-specific key, or null to use the shared cadence directly
   */
  public static long ticks(ConfigurationAdapter configuration, String overridePath) {
    if (configuration == null) {
      return DEFAULT_TICKS;
    }
    long shared = configuration.getLong(DEFAULT_PATH, DEFAULT_TICKS);
    // contains() rather than a sentinel default: an override set to the same value as the shared
    // cadence is indistinguishable from an absent one otherwise, and the distinction is what lets the
    // shared knob move a mode that never asked to opt out.
    long resolved = overridePath != null && configuration.contains(overridePath)
        ? configuration.getLong(overridePath, shared)
        : shared;
    return Math.max(1L, resolved);
  }
}
