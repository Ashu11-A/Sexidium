package com.sexidium.core.platform.model;

/** The title timings that have a meaning, so the two adapters that send titles agree on them. */
public final class TitleSpecs {
  private TitleSpecs() {
  }

  /**
   * A title that is replaced once a second and must not blink while it is.
   *
   * <p>No fade in either direction, and a stay comfortably longer than the second between updates.
   * That combination is what makes a sequence of these read as ONE number counting down rather than as
   * five separate announcements: with a fade-in, every tick of the countdown would dim to nothing and
   * swell back, and with a stay shorter than the gap it would disappear between them.</p>
   *
   * <p>The overshoot also covers the last number. Nothing sends a title to clear the countdown — the
   * reset teleports everybody a moment later — so the final value expires on its own instead of
   * hanging on screen.</p>
   */
  public static TitleSpec countdown(String titleMiniMessage) {
    return new TitleSpec(titleMiniMessage, "", 0L, 1500L, 250L);
  }
}
