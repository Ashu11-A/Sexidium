package com.sexidium.core.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * An amount of money, held as a whole number of MINOR UNITS (cents) and never as a decimal.
 *
 * <p>The storage decision is the whole reason this type exists. {@code SqlDialect} has no decimal
 * token, and SQLite — the default backend, and the one every test runs against — has no real
 * {@code DECIMAL} type at all: a "decimal" column there is a {@code REAL}, so every balance would
 * round through a double on exactly the backend nobody would notice it on. The same call, for the
 * same reason, was already made for {@code network_nodes.tps}, which is stored scaled ×100.</p>
 *
 * <p>{@link #SCALE} is a compile-time constant and must stay one. It is not a config key and must
 * never become one: changing it would silently reinterpret every balance already in the table —
 * 1000 cents (10.00) would read back as 10.00 with scale 3, i.e. a player's money divided by ten.</p>
 *
 * <p>Arithmetic uses {@code Math.addExact}/{@code subtractExact} on purpose. An overflow here has to
 * THROW: a wrapped {@code long} turns a deposit into a negative balance, which is the one failure
 * mode a money type must not have. Callers reach this through {@link EconomyService}, which catches
 * and answers {@code INVALID_AMOUNT} rather than letting it escape into a command.</p>
 */
public record Money(long minorUnits) implements Comparable<Money> {

  /** Minor units per major unit, as a power of ten. Fixed forever — see the class javadoc. */
  public static final int SCALE = 2;

  public static final Money ZERO = new Money(0L);

  public static Money ofMinor(long minorUnits) {
    return minorUnits == 0L ? ZERO : new Money(minorUnits);
  }

  /** Rounds HALF_UP to {@link #SCALE} first: a Vault caller may hand us any scale it likes. */
  public static Money of(BigDecimal amount) {
    if (amount == null) {
      return ZERO;
    }
    return ofMinor(amount.setScale(SCALE, RoundingMode.HALF_UP).unscaledValue().longValueExact());
  }

  /**
   * Parses user or config input. Empty when the text is not an amount this currency can hold.
   *
   * <p>Stricter than {@code new BigDecimal(String)} in two places, and both of them are inputs a
   * player can type:</p>
   *
   * <ul>
   *   <li><b>Exponents.</b> {@code new BigDecimal("1e3")} is a perfectly good 1000, and
   *       {@code "1e300"} is a perfectly good number that no {@code long} can hold. Neither is
   *       something anybody means to type into {@code /pay}, so the notation is refused outright
   *       rather than left to overflow somewhere further in.</li>
   *   <li><b>More decimals than the currency has.</b> {@code "10.505"} would round to {@code 10.51}
   *       and charge a cent nobody asked for. Refusing is the only answer that cannot surprise.</li>
   * </ul>
   *
   * <p>A leading {@code -} parses fine and is deliberately NOT rejected here: whether a negative
   * amount is legal is a question about the COMMAND (a {@code /pay -5} must be refused, an admin
   * adjustment need not be), and answering it in the parser would make both callers wrong.</p>
   */
  public static Optional<Money> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return Optional.empty();
    }
    for (int index = 0; index < trimmed.length(); index++) {
      char character = trimmed.charAt(index);
      if (character == 'e' || character == 'E' || character == '+') {
        return Optional.empty();
      }
    }
    BigDecimal value;
    try {
      value = new BigDecimal(trimmed);
    } catch (NumberFormatException notANumber) {
      // "abc", "NaN", "Infinity", a bare "-": BigDecimal refuses all of them, and so do we.
      return Optional.empty();
    }
    if (value.scale() > SCALE) {
      return Optional.empty();
    }
    try {
      return Optional.of(ofMinor(value.movePointRight(SCALE).longValueExact()));
    } catch (ArithmeticException tooLarge) {
      return Optional.empty();
    }
  }

  /** The display/interop value, always at exactly {@link #SCALE} digits. */
  public BigDecimal toBigDecimal() {
    return BigDecimal.valueOf(minorUnits, SCALE);
  }

  public Money plus(Money other) {
    return ofMinor(Math.addExact(minorUnits, other.minorUnits));
  }

  public Money minus(Money other) {
    return ofMinor(Math.subtractExact(minorUnits, other.minorUnits));
  }

  public Money negated() {
    return ofMinor(Math.negateExact(minorUnits));
  }

  public boolean isPositive() {
    return minorUnits > 0L;
  }

  public boolean isNegative() {
    return minorUnits < 0L;
  }

  public boolean isZero() {
    return minorUnits == 0L;
  }

  @Override
  public int compareTo(Money other) {
    return Long.compare(minorUnits, other.minorUnits);
  }

  @Override
  public String toString() {
    return toBigDecimal().toPlainString();
  }
}
