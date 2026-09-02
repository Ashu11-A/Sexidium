package com.sexidium.core.economy;

import com.sexidium.core.platform.ConfigurationAdapter;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Turns a {@link Money} into the string a player reads. Kept out of {@link Money} on purpose: the
 * amount is a fact about the ledger, the presentation is a fact about the config, and folding the
 * two together would drag {@link ConfigurationAdapter} into a value type every test constructs.
 *
 * <p><b>{@link DecimalFormat} is not thread-safe</b>, and this class is called from at least two
 * threads at once — the sidebar formats a balance on the HUD render tick while {@code /pay} formats
 * one on the main thread. A single shared instance corrupts its own internal buffer under that and
 * produces garbage digits, intermittently, which is the worst possible way for a money display to
 * fail. So each thread gets its own, rebuilt only when {@link #reload()} publishes a new spec.</p>
 *
 * <p>A locale nobody recognises falls back to {@code Locale.US} rather than throwing: an operator's
 * typo in {@code economy.format.locale} must cost them grouping separators, never the boot.</p>
 */
public final class CurrencyFormat {

  private final ConfigurationAdapter configuration;

  /**
   * Everything the formatter is built from, published as ONE immutable object. Reference identity is
   * what the per-thread cache below compares, so a reload has to replace the whole record rather
   * than mutate fields — a half-updated spec read by the render thread is exactly the tearing this
   * design exists to avoid.
   */
  private record Spec(
      String currencyId,
      String symbol,
      String nameSingular,
      String namePlural,
      boolean symbolBefore,
      boolean grouping,
      Locale locale
  ) {
  }

  private record Bound(Spec spec, DecimalFormat format) {
  }

  private volatile Spec spec;
  private final ThreadLocal<Bound> bound = new ThreadLocal<>();

  public CurrencyFormat(ConfigurationAdapter configuration) {
    this.configuration = configuration;
    this.spec = readSpec();
  }

  /** Re-reads the config. Every thread notices on its next format, because the spec object changed. */
  public void reload() {
    this.spec = readSpec();
  }

  public String currencyId() {
    return spec.currencyId();
  }

  public String symbol() {
    return spec.symbol();
  }

  public String nameSingular() {
    return spec.nameSingular();
  }

  public String namePlural() {
    return spec.namePlural();
  }

  /** {@code "$1,234.56"}. PLAIN TEXT — never MiniMessage; the HUD passes this through MessageArg.text. */
  public String format(Money amount) {
    return format(amount == null ? BigDecimal.ZERO : amount.toBigDecimal());
  }

  public String format(BigDecimal amount) {
    Spec current = spec;
    String digits = formatter(current).format(amount == null ? BigDecimal.ZERO : amount);
    return current.symbolBefore() ? current.symbol() + digits : digits + " " + current.symbol();
  }

  /** {@code "1,234.56 dollars"} — the amount followed by the currency's own name, singular at 1. */
  public String formatNamed(Money amount) {
    Spec current = spec;
    Money safe = amount == null ? Money.ZERO : amount;
    String digits = formatter(current).format(safe.toBigDecimal());
    boolean singular = Math.abs(safe.minorUnits()) == (long) Math.pow(10, Money.SCALE);
    return digits + " " + (singular ? current.nameSingular() : current.namePlural());
  }

  private DecimalFormat formatter(Spec current) {
    Bound cached = bound.get();
    if (cached == null || cached.spec() != current) {
      cached = new Bound(current, newFormat(current));
      bound.set(cached);
    }
    return cached.format();
  }

  private static DecimalFormat newFormat(Spec current) {
    StringBuilder pattern = new StringBuilder(current.grouping() ? "#,##0" : "#0");
    if (Money.SCALE > 0) {
      pattern.append('.');
      pattern.append("0".repeat(Money.SCALE));
    }
    DecimalFormat format = new DecimalFormat(pattern.toString(),
        DecimalFormatSymbols.getInstance(current.locale()));
    format.setGroupingUsed(current.grouping());
    return format;
  }

  private Spec readSpec() {
    return new Spec(
        configuration.getString("economy.currency.id", "dollar"),
        configuration.getString("economy.currency.symbol", "$"),
        configuration.getString("economy.currency.name-singular", "dollar"),
        configuration.getString("economy.currency.name-plural", "dollars"),
        configuration.getBoolean("economy.currency.symbol-before", true),
        configuration.getBoolean("economy.format.grouping", true),
        resolveLocale(configuration.getString("economy.format.locale", "en-US")));
  }

  /**
   * {@code Locale.forLanguageTag} does not throw on nonsense — it answers {@code Locale.ROOT}, whose
   * language tag is {@code "und"}. That is the only signal there is that the operator typed something
   * wrong, and it has to be turned into a real locale here: ROOT formats without grouping separators,
   * which would look like a Sexidium bug rather than like a config typo.
   */
  static Locale resolveLocale(String tag) {
    if (tag == null || tag.isBlank()) {
      return Locale.US;
    }
    Locale locale = Locale.forLanguageTag(tag.trim());
    return "und".equals(locale.toLanguageTag()) ? Locale.US : locale;
  }
}
