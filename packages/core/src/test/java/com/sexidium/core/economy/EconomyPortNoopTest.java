package com.sexidium.core.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EconomyPortNoopTest {

  @Test
  void noop_answersSafelyForEveryMethod() {
    EconomyPort port = EconomyPort.noop();
    UUID playerId = UUID.randomUUID();
    assertFalse(port.enabled());
    assertEquals(Money.ZERO, port.balance(playerId));
    // Blank and not "$0.00": a HUD row that renders an amount nobody has is a lie.
    assertEquals("", port.formattedBalance(playerId));
    assertNotNull(port.deposit(playerId, Money.ofMinor(100L), "test"));
    assertEquals(EconomyResult.Status.DISABLED, port.deposit(playerId, Money.ofMinor(100L), "t").status());
    assertEquals(EconomyResult.Status.DISABLED, port.withdraw(playerId, Money.ofMinor(100L), "t").status());
  }

  @Test
  void noop_hasIsTrueOnlyForZero() {
    EconomyPort port = EconomyPort.noop();
    UUID playerId = UUID.randomUUID();
    assertEquals(true, port.has(playerId, Money.ZERO));
    assertFalse(port.has(playerId, Money.ofMinor(1L)));
  }
}
