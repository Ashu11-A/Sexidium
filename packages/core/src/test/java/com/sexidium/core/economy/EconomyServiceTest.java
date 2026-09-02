package com.sexidium.core.economy;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomyServiceTest {

  @TempDir
  Path tmp;

  private Database database;
  private PropertiesConfigurationAdapter configuration;
  private EconomyService economy;

  @BeforeEach
  void setUp() throws Exception {
    database = EconomyTestSupport.database(tmp);
    configuration = EconomyTestSupport.config();
    economy = EconomyTestSupport.service(database, configuration);
  }

  @AfterEach
  void tearDown() {
    economy.shutdown();
    database.close();
  }

  @Test
  @DisplayName("an account with no row reports the starting balance, not zero")
  void unknownAccount_readsTheStartingBalance() {
    // The row is created lazily, and a brand-new player must not be shown a balance of nothing that
    // then jumps to 100 a second later.
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(UUID.randomUUID()));
    assertFalse(economy.hasAccount(UUID.randomUUID()));
  }

  @Test
  void ensureAccount_isIdempotentAndNeverResetsTheBalance() {
    UUID playerId = UUID.randomUUID();
    assertTrue(economy.ensureAccount(playerId, "Alice", true));
    assertTrue(economy.deposit(playerId, Money.parse("50.00").orElseThrow(), "test").ok());
    // The second call is the one that used to matter: an upsert listing `balance` would reset a
    // returning player to the starting amount on their first join of the day.
    assertTrue(economy.ensureAccount(playerId, "Alice", true));
    assertEquals(Money.parse("150.00").orElseThrow(), economy.balance(playerId));
    assertEquals(1, economy.top(10).size());
  }

  @Test
  void depositAndWithdraw_settleAtTheReportedBalance() {
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    EconomyResult deposited = economy.deposit(playerId, Money.parse("25.50").orElseThrow(), "test");
    assertTrue(deposited.ok());
    assertEquals(Money.parse("125.50").orElseThrow(), deposited.balance());
    EconomyResult withdrawn = economy.withdraw(playerId, Money.parse("0.50").orElseThrow(), "test");
    assertTrue(withdrawn.ok());
    assertEquals(Money.parse("125.00").orElseThrow(), withdrawn.balance());
    assertEquals(Money.parse("125.00").orElseThrow(), economy.balance(playerId));
  }

  @Test
  @DisplayName("an overdraw is refused and leaves the balance EXACTLY where it was")
  void overdraw_changesNothing() {
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    EconomyResult result = economy.withdraw(playerId, Money.parse("100.01").orElseThrow(), "test");
    assertFalse(result.ok());
    assertEquals(EconomyResult.Status.INSUFFICIENT_FUNDS, result.status());
    assertEquals(Money.parse("100.00").orElseThrow(), result.balance());
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(playerId));
  }

  @Test
  void ceiling_refusesWithLimitExceededAndChangesNothing() {
    configuration.set("economy.limits.maximum-balance", "150.00");
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    EconomyResult result = economy.deposit(playerId, Money.parse("100.00").orElseThrow(), "test");
    assertFalse(result.ok());
    // Diagnosed and not assumed: telling a player "insufficient funds" when they hit the ceiling
    // sends them looking for money they already have.
    assertEquals(EconomyResult.Status.LIMIT_EXCEEDED, result.status());
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(playerId));
  }

  @Test
  void setAndReset() {
    UUID playerId = UUID.randomUUID();
    assertTrue(economy.set(playerId, Money.parse("7.77").orElseThrow(), "test", "admin").ok());
    assertEquals(Money.parse("7.77").orElseThrow(), economy.balance(playerId));
    assertTrue(economy.reset(playerId, "test", "admin").ok());
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(playerId));
  }

  @Test
  void transfer_movesTheWholeAmount() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    economy.ensureAccount(alice, "Alice", true);
    economy.ensureAccount(bob, "Bob", true);
    assertTrue(economy.transfer(alice, bob, Money.parse("30.00").orElseThrow(), "pay").ok());
    assertEquals(Money.parse("70.00").orElseThrow(), economy.balance(alice));
    assertEquals(Money.parse("130.00").orElseThrow(), economy.balance(bob));
  }

  @Test
  @DisplayName("an overdrawing transfer moves NOTHING — both sides unchanged")
  void overdrawingTransfer_movesNothing() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    economy.ensureAccount(alice, "Alice", true);
    economy.ensureAccount(bob, "Bob", true);
    EconomyResult result = economy.transfer(alice, bob, Money.parse("500.00").orElseThrow(), "pay");
    assertFalse(result.ok());
    assertEquals(EconomyResult.Status.INSUFFICIENT_FUNDS, result.status());
    // BOTH balances, because the failure this guards against is a credit that landed without its
    // matching debit -- money created out of a refused payment.
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(alice));
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(bob));
  }

  @Test
  void accountByName_isCaseInsensitive() {
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "AlIcE", true);
    assertNotNull(economy.accountByName("alice"));
    assertNotNull(economy.accountByName("ALICE"));
    assertEquals(playerId.toString(), economy.accountByName("alice").accountId());
    assertNull(economy.accountByName("nobody"));
  }

  @Test
  void top_isOrderedByBalanceDescending() {
    UUID poor = UUID.randomUUID();
    UUID rich = UUID.randomUUID();
    economy.ensureAccount(poor, "Poor", true);
    economy.ensureAccount(rich, "Rich", true);
    economy.deposit(rich, Money.parse("500.00").orElseThrow(), "test");
    List<EconomyAccount> top = economy.top(10);
    assertEquals(2, top.size());
    assertEquals("Rich", top.get(0).name());
    assertEquals("Poor", top.get(1).name());
  }

  @Test
  @DisplayName("the cache serves a stale value until it is invalidated")
  void cacheTtlAndInvalidate() throws Exception {
    configuration.set("economy.cache-ttl-seconds", 60);
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(playerId));
    // A PEER's change: written straight to the row, so this node's cache knows nothing about it.
    writeBalanceBehindTheCache(playerId, 4_200L);
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(playerId));
    economy.invalidate(playerId);
    assertEquals(Money.parse("42.00").orElseThrow(), economy.balance(playerId));
  }

  @Test
  void zeroTtl_readsTheRowEveryTime() throws Exception {
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    assertEquals(Money.parse("100.00").orElseThrow(), economy.balance(playerId));
    writeBalanceBehindTheCache(playerId, 4_200L);
    assertEquals(Money.parse("42.00").orElseThrow(), economy.balance(playerId));
  }

  @Test
  void renameAndDelete() {
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    assertTrue(economy.renameAccount(playerId, "Alicia"));
    assertNotNull(economy.accountByName("Alicia"));
    assertNull(economy.accountByName("Alice"));
    assertTrue(economy.deleteAccount(playerId));
    assertFalse(economy.hasAccount(playerId));
  }

  @Test
  void nameMap_containsEveryAccount() {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    economy.ensureAccount(alice, "Alice", true);
    economy.ensureAccount(bob, "Bob", true);
    assertEquals("Alice", economy.nameMap().get(alice));
    assertEquals("Bob", economy.nameMap().get(bob));
  }

  /** Simulates a peer node writing the shared row, with no bus message reaching this node. */
  private void writeBalanceBehindTheCache(UUID playerId, long minorUnits) throws Exception {
    synchronized (database.lock()) {
      try (var statement = database.connection().prepareStatement(
          "UPDATE economy_accounts SET balance = ? WHERE account_id = ?")) {
        statement.setLong(1, minorUnits);
        statement.setString(2, playerId.toString());
        statement.executeUpdate();
      }
    }
  }
}
