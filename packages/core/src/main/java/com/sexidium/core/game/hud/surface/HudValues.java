package com.sexidium.core.game.hud.surface;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.platform.hud.HudElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The live values pushed into one surface, and the one place that turns an element plus its value
 * into something renderable.
 *
 * <p>Shared by every driver on purpose. The sidebar renders a row through {@link #render}; a platform
 * driver reads the raw typed values out and hands them to whatever its plugin wants. Keeping the
 * formatting decisions here — how a number reads, how many glyphs a bar is, what an unset key looks
 * like — is what keeps the two renderings of the same declaration recognisably the same readout.</p>
 *
 * <p>Written from the game thread and read from whatever thread a driver renders on, hence the
 * concurrent maps.</p>
 */
public final class HudValues {
  /** What a driver shows for a key nothing has pushed yet. */
  public static final String UNSET = "—";

  private static final char BAR_FILLED = '█';
  private static final char BAR_EMPTY = '░';
  /** A sidebar has characters, not pixels; a spec's pixel width reads as roughly one glyph per 8. */
  private static final int PIXELS_PER_GLYPH = 8;
  private static final int MIN_BAR_GLYPHS = 4;
  private static final int MAX_BAR_GLYPHS = 24;

  private final Map<String, LocalizedText> texts = new ConcurrentHashMap<>();
  private final Map<String, Double> numbers = new ConcurrentHashMap<>();
  private final Map<String, Boolean> flags = new ConcurrentHashMap<>();
  private final Map<String, Double> progresses = new ConcurrentHashMap<>();
  /**
   * When each key last became a DIFFERENT value, in epoch millis. Absent until it changes at least
   * once, which is not the same as "absent until it is first pushed" — see {@link #changedAt}.
   */
  private final Map<String, Long> changedAt = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public HudValues() {
    this(System::currentTimeMillis);
  }

  /** @param clock epoch millis, injectable so a test can drive an animation without sleeping */
  public HudValues(LongSupplier clock) {
    this.clock = clock == null ? System::currentTimeMillis : clock;
  }

  public void text(String key, LocalizedText value) {
    put(texts, key, value);
  }

  public void number(String key, double value) {
    put(numbers, key, value);
  }

  public void flag(String key, boolean value) {
    put(flags, key, value);
  }

  /** Stores a bar fill, clamped to 0..1 here so no driver has to re-clamp it. */
  public void progress(String key, double value) {
    double clamped = Double.isNaN(value) ? 0.0d : Math.clamp(value, 0.0d, 1.0d);
    put(progresses, key, clamped);
  }

  public LocalizedText textValue(String key) {
    return texts.get(normalize(key));
  }

  public Double numberValue(String key) {
    return numbers.get(normalize(key));
  }

  public Boolean flagValue(String key) {
    return flags.get(normalize(key));
  }

  public Double progressValue(String key) {
    return progresses.get(normalize(key));
  }

  /**
   * When this key last became a different value, in epoch millis, or 0 if it never has.
   *
   * <p>What a {@link HudElement.PulseRow} animates from. It is a CHANGE stamp, not a write stamp, and
   * that distinction is the whole contract: consumers push their values unconditionally on every
   * cadence — pushes are idempotent by design, so a readout that comes up mid-match has something to
   * draw on its first frame — and stamping every one of those would leave a pulse permanently at its
   * peak, re-triggered a moment before it could ever settle.</p>
   */
  public long changedAt(String key) {
    Long stamp = changedAt.get(normalize(key));
    return stamp == null ? 0L : stamp;
  }

  /** Whether anything at all has been pushed under this key. */
  public boolean has(String key) {
    String normalized = normalize(key);
    return normalized != null
        && (texts.containsKey(normalized)
        || numbers.containsKey(normalized)
        || flags.containsKey(normalized)
        || progresses.containsKey(normalized));
  }

  /**
   * The plain-text form of a key's value, for a driver that can only carry strings.
   *
   * <p>Returns {@link #UNSET} rather than null or an empty string for a key nothing has pushed:
   * a visibly missing value is a bug someone can see and fix, a silently blank row is not.</p>
   */
  public String plain(String key) {
    LocalizedText localized = textValue(key);
    if (localized != null) {
      // A localized value cannot be flattened without a language; drivers that can render it call
      // textValue() instead. This is the last-resort form for a driver that cannot.
      return localized.messageKey().path();
    }
    Double number = numberValue(key);
    if (number != null) {
      return number(number);
    }
    Double progress = progressValue(key);
    if (progress != null) {
      return Math.round(progress * 100.0d) + "%";
    }
    Boolean flag = flagValue(key);
    if (flag != null) {
      return String.valueOf(flag);
    }
    return UNSET;
  }

  /**
   * Renders one element as a HUD line, or null when this element has nothing to draw on a text
   * surface (a spacer, an icon) — the caller skips it and keeps rendering the rest.
   */
  public LocalizedText render(HudElement element) {
    return switch (element) {
      case HudElement.TextRow textRow -> compose(textRow.template(), valueArgument(textRow.key()));
      // Identical to a text row on purpose: the size is the driver's to animate, the line is not.
      case HudElement.PulseRow pulseRow -> compose(pulseRow.template(), valueArgument(pulseRow.key()));
      case HudElement.Bar bar -> compose(
          bar.label(),
          MessageArg.mini("value", glyphs(bar)),
          MessageArg.text("percent", Math.round(fill(bar.key()) * 100.0d) + "%")
      );
      case HudElement.Icon ignored -> null;
      case HudElement.Spacer ignored -> null;
    };
  }

  /**
   * Substitutes the live value into an element's template, keeping whatever arguments the template
   * already carried — a consumer's label is free to name placeholders of its own beside {@code value}.
   */
  private static LocalizedText compose(LocalizedText template, MessageArg... values) {
    if (template == null) {
      return null;
    }
    List<MessageArg> arguments = new ArrayList<>(template.arguments());
    arguments.addAll(Arrays.asList(values));
    return new LocalizedText(template.messageKey(), arguments);
  }

  /** The {@code <value>} substitution for a text row, whichever typed setter last fed its key. */
  private MessageArg valueArgument(String key) {
    LocalizedText localized = textValue(key);
    if (localized != null) {
      return MessageArg.localized("value", localized);
    }
    Double number = numberValue(key);
    if (number != null) {
      return MessageArg.text("value", number(number));
    }
    Double progress = progressValue(key);
    if (progress != null) {
      return MessageArg.text("value", Math.round(progress * 100.0d) + "%");
    }
    Boolean flag = flagValue(key);
    if (flag != null) {
      return MessageArg.text("value", String.valueOf(flag));
    }
    return MessageArg.text("value", UNSET);
  }

  private String glyphs(HudElement.Bar bar) {
    int width = Math.clamp((long) bar.widthPixels() / PIXELS_PER_GLYPH, MIN_BAR_GLYPHS, MAX_BAR_GLYPHS);
    int filled = (int) Math.round(fill(bar.key()) * width);
    StringBuilder builder = new StringBuilder(width + 32);
    builder.append("<white>");
    builder.append(String.valueOf(BAR_FILLED).repeat(filled));
    builder.append("</white><dark_gray>");
    builder.append(String.valueOf(BAR_EMPTY).repeat(width - filled));
    builder.append("</dark_gray>");
    return builder.toString();
  }

  private double fill(String key) {
    Double progress = progressValue(key);
    if (progress != null) {
      return progress;
    }
    // A caller that pushed a bare number into a bar meant it as a ratio; treating it as zero would
    // silently draw an empty bar over a working value.
    Double number = numberValue(key);
    return number == null ? 0.0d : Math.clamp(number, 0.0d, 1.0d);
  }

  /** Whole numbers read as whole numbers; anything else keeps one decimal. */
  private static String number(double value) {
    if (value == Math.rint(value) && !Double.isInfinite(value)) {
      return String.valueOf((long) value);
    }
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private <T> void put(Map<String, T> target, String key, T value) {
    String normalized = normalize(key);
    if (normalized == null) {
      return;
    }
    T previous = value == null ? target.remove(normalized) : target.put(normalized, value);
    if (!Objects.equals(previous, value)) {
      changedAt.put(normalized, clock.getAsLong());
    }
  }

  private static String normalize(String key) {
    return key == null || key.isBlank() ? null : key.trim().toLowerCase(Locale.ROOT);
  }
}
