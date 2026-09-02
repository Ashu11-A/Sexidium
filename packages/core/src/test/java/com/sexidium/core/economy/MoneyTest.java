package com.sexidium.core.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

  @Test
  void parse_acceptsTheShapesPlayersActuallyType() {
    assertEquals(1000L, Money.parse("10").orElseThrow().minorUnits());
    assertEquals(1050L, Money.parse("10.5").orElseThrow().minorUnits());
    assertEquals(1050L, Money.parse("10.50").orElseThrow().minorUnits());
    // Chat pads arguments; a trimmable amount must not be an error message.
    assertEquals(1050L, Money.parse(" 10.50 ").orElseThrow().minorUnits());
  }

  @Test
  void parse_rejectsEverythingThatIsNotAnAmount() {
    assertTrue(Money.parse("").isEmpty());
    assertTrue(Money.parse("   ").isEmpty());
    assertTrue(Money.parse(null).isEmpty());
    assertTrue(Money.parse("abc").isEmpty());
    assertTrue(Money.parse("-").isEmpty());
    assertTrue(Money.parse("NaN").isEmpty());
    assertTrue(Money.parse("Infinity").isEmpty());
  }

  @Test
  void parse_rejectsExponentsEvenThoughBigDecimalAcceptsThem() {
    // new BigDecimal("1e3") is a perfectly good 1000, and "1e300" is a perfectly good number no long
    // can hold. Neither is something anybody means to type into /pay.
    assertTrue(Money.parse("1e3").isEmpty());
    assertTrue(Money.parse("1E3").isEmpty());
    assertTrue(Money.parse("1e300").isEmpty());
  }

  @Test
  void parse_rejectsMoreDecimalsThanTheCurrencyHas() {
    // Rounding 10.505 to 10.51 would charge a cent nobody asked for.
    assertTrue(Money.parse("10.505").isEmpty());
  }

  @Test
  void parse_rejectsAmountsNoLongCanHold() {
    assertTrue(Money.parse("99999999999999999999").isEmpty());
  }

  @Test
  void of_roundsHalfUpToScale() {
    assertEquals(1051L, Money.of(new BigDecimal("10.505")).minorUnits());
    assertEquals(1050L, Money.of(new BigDecimal("10.504")).minorUnits());
    assertEquals(1000L, Money.of(new BigDecimal("10")).minorUnits());
  }

  @Test
  void arithmetic_throwsOnOverflowRatherThanWrapping() {
    Money max = Money.ofMinor(Long.MAX_VALUE);
    Money min = Money.ofMinor(Long.MIN_VALUE);
    // A wrapped long turns a deposit into a negative balance, which is the one failure mode a money
    // type must not have.
    assertThrows(ArithmeticException.class, () -> max.plus(Money.ofMinor(1L)));
    assertThrows(ArithmeticException.class, () -> min.minus(Money.ofMinor(1L)));
  }

  @Test
  void roundTrip_isIdentity() {
    for (long minor : new long[] {0L, 1L, 99L, 100L, 123_456L, -250L}) {
      Money money = Money.ofMinor(minor);
      assertEquals(money, Money.of(money.toBigDecimal()));
      assertEquals(money, Money.parse(money.toBigDecimal().toPlainString()).orElseThrow());
    }
  }

  @Test
  void comparisonsAndSigns() {
    assertTrue(Money.ofMinor(5L).isPositive());
    assertTrue(Money.ofMinor(-5L).isNegative());
    assertTrue(Money.ZERO.isZero());
    assertTrue(Money.ofMinor(5L).compareTo(Money.ofMinor(4L)) > 0);
    assertEquals(Money.ofMinor(-5L), Money.ofMinor(5L).negated());
  }
}
