package com.sexidium.core.platform.hud;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.model.ItemKey;

import java.time.Duration;
import java.util.Locale;

/**
 * One thing drawn on a {@link HudSurfaceSpec}. Deliberately a small closed set: every element here has
 * to be renderable by EVERY driver, including the sidebar fallback that only has text lines to work
 * with, so a shape nobody can degrade gracefully does not belong in the vocabulary.
 *
 * <h2>Elements carry no coordinates</h2>
 * Position comes from declaration order — elements stack downward in the order the builder received
 * them, and {@link Spacer} is how a caller asks for a gap. Letting callers hand-place things was how
 * the old single layout ended up with the row pitch, the font scale and the sign of the y axis encoded
 * as prose comments in a yml file that nothing verified. The driver owns geometry now; a spec says
 * WHAT is drawn, never WHERE in pixels.
 *
 * <h2>Templates are localized, values are not</h2>
 * A {@link TextRow} splits into a {@link LocalizedText} template (the label — translated per viewer)
 * and a runtime value pushed under {@link #key()} (the datum — a number, a duration, a name). This is
 * the split that was impossible on the old seam, whose whole payload was one opaque
 * {@code Map<String,String>} that no {@code MessageService} could reach.
 */
public sealed interface HudElement {
  /**
   * The name a caller pushes this element's live value under, via
   * {@link com.sexidium.core.platform.HudSurfaceHandle#text} and friends. Null for an element that
   * carries no runtime value ({@link Spacer}, a fixed {@link Icon}).
   *
   * <p>Unique within a surface, lower-case, and free of whitespace — drivers embed it in placeholder
   * syntax where a space would silently split it into two arguments.</p>
   */
  String key();

  /**
   * The colour a driver draws this element in when it cannot read the template's own MiniMessage tags.
   *
   * <p>Defaulted here rather than declared per element, because only the text shapes have words to
   * colour — a bar draws its own glyphs and an icon is an item. See {@link HudColor}.</p>
   */
  default HudColor color() {
    return HudColor.DEFAULT;
  }

  /**
   * A text row: a translated label template with the pushed value substituted as {@code <value>}.
   *
   * <p>{@code color} is what a driver that cannot read the template's own MiniMessage tags draws the
   * row in; see {@link HudColor} for why a spec has to say it twice.</p>
   */
  record TextRow(String key, LocalizedText template, HudAlign align, double scale, HudColor color)
      implements HudElement {
    public TextRow {
      key = requireKey(key);
      if (template == null) {
        throw new IllegalArgumentException("Text row '" + key + "' needs a template.");
      }
      align = align == null ? HudAlign.LEFT : align;
      if (!(scale > 0.0d)) {
        throw new IllegalArgumentException("Text row '" + key + "' needs a positive scale.");
      }
      color = color == null ? HudColor.DEFAULT : color;
    }

    public TextRow(String key, LocalizedText template) {
      this(key, template, HudAlign.LEFT, 1.0d, HudColor.DEFAULT);
    }

    public TextRow(String key, LocalizedText template, HudAlign align, double scale) {
      this(key, template, align, scale, HudColor.DEFAULT);
    }
  }

  /**
   * A text row that reacts to its own value <em>changing</em>: it is drawn at {@code peakScale} on the
   * frame the value changes and eases back down to {@code scale} over {@code settle}. Used for a datum
   * whose every change is an event in itself — a countdown ticking down in the middle of the screen.
   *
   * <h2>Driven by the value, not by the caller</h2>
   * There is no "play the animation now" call, and there deliberately cannot be one. The phase is
   * derived from how long ago the value under {@link #key()} last became a different value, which is
   * something {@link com.sexidium.core.game.hud.surface.HudValues} already knows. So a caller pushes
   * {@code 5}, then {@code 4}, and the pop happens; a caller that re-pushes {@code 4} every tick —
   * which is what every existing consumer does, because pushes are idempotent by contract — does not
   * retrigger it. Hand-firing the animation would have made those two indistinguishable.
   *
   * <h2>What a driver that cannot animate does with one</h2>
   * Draws it as an ordinary text row and ignores both scales. That is the whole degrade, and it is why
   * this is a text element rather than a shape of its own: the sidebar renders it, the vanilla title
   * renders it, and only the overlay driver reads the timing.
   *
   * @param scale     the resting size, held for as long as the value does not change
   * @param peakScale the size on the frame it changes; never smaller than {@code scale}
   * @param settle    how long the fall from {@code peakScale} to {@code scale} takes
   * @param color     what a driver that flattens the template draws it in; see {@link HudColor}
   */
  record PulseRow(
      String key,
      LocalizedText template,
      HudAlign align,
      double scale,
      double peakScale,
      Duration settle,
      HudColor color
  ) implements HudElement {
    /** What a pulse settles over when the caller does not say. Long enough to read as motion, short
     *  enough to be finished before the next whole second arrives. */
    public static final Duration DEFAULT_SETTLE = Duration.ofMillis(450);

    public PulseRow {
      key = requireKey(key);
      if (template == null) {
        throw new IllegalArgumentException("Pulse row '" + key + "' needs a template.");
      }
      // Centre by default: a pulsing row is an announcement, and an announcement that grows out of its
      // left edge lurches sideways instead of swelling in place.
      align = align == null ? HudAlign.CENTER : align;
      if (!(scale > 0.0d)) {
        throw new IllegalArgumentException("Pulse row '" + key + "' needs a positive resting scale.");
      }
      if (!(peakScale >= scale)) {
        throw new IllegalArgumentException("Pulse row '" + key + "' peaks at " + peakScale
            + ", which is smaller than its resting scale " + scale + ": a pulse grows and settles"
            + " back, so the peak is the larger of the two.");
      }
      settle = settle == null || settle.isZero() || settle.isNegative() ? DEFAULT_SETTLE : settle;
      color = color == null ? HudColor.DEFAULT : color;
    }

    public PulseRow(String key, LocalizedText template, double scale, double peakScale) {
      this(key, template, HudAlign.CENTER, scale, peakScale, DEFAULT_SETTLE, HudColor.DEFAULT);
    }

    public PulseRow(
        String key,
        LocalizedText template,
        HudAlign align,
        double scale,
        double peakScale,
        Duration settle
    ) {
      this(key, template, align, scale, peakScale, settle, HudColor.DEFAULT);
    }

    /**
     * How far through the settle the row is in plain elapsed time: 0 at the instant the value changed,
     * 1 once the settle is over. A value that has never been pushed reads as 1 — at rest — rather than
     * as mid-animation, which is what keeps a surface's very first frame from being a pop nobody asked
     * for.
     */
    public double phase(long millisSinceChange) {
      if (millisSinceChange <= 0L) {
        return 0.0d;
      }
      long span = Math.max(1L, settle.toMillis());
      return Math.clamp((double) millisSinceChange / (double) span, 0.0d, 1.0d);
    }

    /**
     * How far DOWN the fall the row is at a given moment: 0 still at the peak, 1 come to rest.
     *
     * <p>A cubic ease-out over {@link #phase}, and that is the difference between "smooth" and "a
     * number shrinking at a constant rate": most of the drop happens immediately and the last of it
     * eases in, which is how a thing thrown at the screen settles.</p>
     */
    public double easedProgress(long millisSinceChange) {
      return 1.0d - Math.pow(1.0d - phase(millisSinceChange), 3.0d);
    }

    /**
     * The size a given fraction of the way down the fall — {@code 0} being the peak and {@code 1} the
     * resting size.
     *
     * <p><b>Linear</b>, deliberately, and it is worth saying why when the motion itself is eased. A
     * driver that cannot animate a size continuously has to pick a handful of fixed sizes and move
     * the row between them, and if the EASING lived here those sizes would bunch up: with eight of
     * them, half would land within a few percent of the resting size and be indistinguishable on
     * screen, leaving an animation that visibly jumped twice and then stopped. Evenly spaced sizes,
     * stepped through on an eased clock ({@link #easedProgress}), spend every frame on a size the eye
     * can actually tell from the last one — same curve, all eight steps visible.</p>
     */
    public double scaleAt(double fallen) {
      return peakScale - (peakScale - scale) * Math.clamp(fallen, 0.0d, 1.0d);
    }
  }

  /**
   * A progress bar driven by a 0..1 value pushed under {@link #key()}.
   *
   * <p>{@code widthPixels} is a request, not a guarantee — a driver with no pixels to give (the
   * sidebar) reads it as a character budget instead.</p>
   */
  record Bar(String key, LocalizedText label, int widthPixels, BarStyle style) implements HudElement {
    public Bar {
      key = requireKey(key);
      if (widthPixels <= 0) {
        throw new IllegalArgumentException("Bar '" + key + "' needs a positive width.");
      }
      style = style == null ? BarStyle.CONTINUOUS : style;
    }

    public Bar(String key, LocalizedText label) {
      this(key, label, 80, BarStyle.CONTINUOUS);
    }
  }

  /**
   * An item icon. A null {@link #key()} pins the icon to {@code icon}; a non-null one lets the caller
   * swap it at runtime by pushing an item id as text, so a challenge can show "the block you just
   * rolled" without redeclaring the surface.
   */
  record Icon(String key, ItemKey icon, double scale) implements HudElement {
    public Icon {
      key = key == null || key.isBlank() ? null : normalizeKey(key);
      if (icon == null) {
        throw new IllegalArgumentException("Icon needs a fallback item, even when it is swappable.");
      }
      if (!(scale > 0.0d)) {
        throw new IllegalArgumentException("Icon needs a positive scale.");
      }
    }

    public Icon(ItemKey icon) {
      this(null, icon, 1.0d);
    }
  }

  /** Vertical breathing room between elements. Draws nothing. */
  record Spacer(int pixels) implements HudElement {
    public Spacer {
      if (pixels <= 0) {
        throw new IllegalArgumentException("Spacer needs a positive height.");
      }
    }

    @Override
    public String key() {
      return null;
    }
  }

  private static String requireKey(String key) {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("Every valued HUD element needs a key.");
    }
    return normalizeKey(key);
  }

  private static String normalizeKey(String key) {
    String normalized = key.trim().toLowerCase(Locale.ROOT);
    if (normalized.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException(
          "HUD element key '" + key + "' cannot contain whitespace: drivers pass keys as placeholder"
              + " arguments, where a space splits one key into two.");
    }
    return normalized;
  }
}
