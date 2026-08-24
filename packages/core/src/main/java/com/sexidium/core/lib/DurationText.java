package com.sexidium.core.lib;

/**
 * Renders a number of seconds as a short human duration, for readouts that have room for a few
 * characters and no more — a HUD row, a scoreboard line, a menu subtitle.
 *
 * <p>Two units at most, largest first, and the smaller one is dropped once it stops mattering: a run
 * measured in days does not need its minutes, and printing them makes the number harder to read rather
 * than more precise. Never localized on purpose — {@code 3h 12m} is the same glyphs everywhere, and the
 * one surface that has to draw it (the BetterHud corner overlay) is operator-owned and cannot reach
 * {@code MessageService} anyway.</p>
 */
public final class DurationText {
  private static final long MINUTE = 60L;
  private static final long HOUR = 60L * MINUTE;
  private static final long DAY = 24L * HOUR;

  private DurationText() {
  }

  /**
   * {@code 41s} · {@code 12m 05s} · {@code 3h 12m} · {@code 2d 4h}. A negative input reads as
   * {@code 0s} rather than counting backwards — a counter that has somehow gone below zero is a bug
   * upstream, and showing it as negative time only moves the confusion onto the player.
   */
  public static String compact(long seconds) {
    long total = Math.max(0L, seconds);
    if (total >= DAY) {
      return (total / DAY) + "d " + ((total % DAY) / HOUR) + "h";
    }
    if (total >= HOUR) {
      return (total / HOUR) + "h " + ((total % HOUR) / MINUTE) + "m";
    }
    if (total >= MINUTE) {
      // Zero-padded, because a bare "12m 5s" reads as a typo beside "12m 45s" one second later.
      return (total / MINUTE) + "m " + String.format("%02d", total % MINUTE) + "s";
    }
    return total + "s";
  }
}
