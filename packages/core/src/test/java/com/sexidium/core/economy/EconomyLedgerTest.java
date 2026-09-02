package com.sexidium.core.economy;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit trail. Without it, "an admin says a player's money vanished" is unanswerable — which is
 * why the ledger exists at all, and why every one of these assertions is about a row being there and
 * agreeing with the balance rather than about it being pretty.
 */
class EconomyLedgerTest {

  private record Row(String op, long amount, long balanceAfter, String counterparty, long createdAt) {
  }

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
  @DisplayName("one signed row per mutation, and balance_after agrees with the balance")
  void everyMutationLeavesOneRow() throws Exception {
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    economy.deposit(playerId, Money.parse("10.00").orElseThrow(), "quest");
    economy.withdraw(playerId, Money.parse("4.00").orElseThrow(), "shop");
    economy.set(playerId, Money.parse("50.00").orElseThrow(), "admin-set", "console");
    economy.flushWrites();

    List<Row> rows = rows(playerId);
    assertEquals(3, rows.size());
    assertEquals(EconomyService.OP_DEPOSIT, rows.get(0).op());
    assertEquals(1000L, rows.get(0).amount());
    assertEquals(11_000L, rows.get(0).balanceAfter());
    // Signed: a withdrawal is a negative amount, so the column sums to the balance.
    assertEquals(EconomyService.OP_WITHDRAW, rows.get(1).op());
    assertEquals(-400L, rows.get(1).amount());
    assertEquals(10_600L, rows.get(1).balanceAfter());
    // A set records the DELTA it applied, not the absolute -- a column that mixes the two cannot be
    // summed, and summing it is the whole point of the ledger.
    assertEquals(EconomyService.OP_SET, rows.get(2).op());
    assertEquals(5_000L - 10_600L, rows.get(2).amount());
    assertEquals(5_000L, rows.get(2).balanceAfter());
    assertEquals(economy.balance(playerId).minorUnits(), rows.get(2).balanceAfter());
  }

  @Test
  void transfer_leavesBothHalvesPointingAtEachOther() throws Exception {
    UUID alice = UUID.randomUUID();
    UUID bob = UUID.randomUUID();
    economy.ensureAccount(alice, "Alice", true);
    economy.ensureAccount(bob, "Bob", true);
    economy.transfer(alice, bob, Money.parse("30.00").orElseThrow(), "pay");
    economy.flushWrites();

    List<Row> outgoing = rows(alice);
    List<Row> incoming = rows(bob);
    assertEquals(1, outgoing.size());
    assertEquals(1, incoming.size());
    assertEquals(EconomyService.OP_TRANSFER_OUT, outgoing.get(0).op());
    assertEquals(-3_000L, outgoing.get(0).amount());
    assertEquals(bob.toString(), outgoing.get(0).counterparty());
    assertEquals(EconomyService.OP_TRANSFER_IN, incoming.get(0).op());
    assertEquals(3_000L, incoming.get(0).amount());
    assertEquals(alice.toString(), incoming.get(0).counterparty());
  }

  @Test
  void refusedMutations_leaveNoRow() throws Exception {
    UUID playerId = UUID.randomUUID();
    economy.ensureAccount(playerId, "Alice", true);
    economy.withdraw(playerId, Money.parse("999.00").orElseThrow(), "shop");
    economy.flushWrites();
    // A refusal is not a movement. A ledger that recorded attempts could not be summed to a balance.
    assertTrue(rows(playerId).isEmpty());
  }

  @Test
  void ledgerDisabled_writesNothing() throws Exception {
    configuration.set("economy.ledger.enabled", "false");
    UUID playerId = UUID.randomUUID();
    economy.deposit(playerId, Money.parse("1.00").orElseThrow(), "quest");
    economy.flushWrites();
    assertTrue(rows(playerId).isEmpty());
  }

  @Test
  void retentionSweep_prunesByCreatedAt() throws Exception {
    UUID playerId = UUID.randomUUID();
    economy.deposit(playerId, Money.parse("1.00").orElseThrow(), "quest");
    economy.deposit(playerId, Money.parse("2.00").orElseThrow(), "quest");
    economy.flushWrites();
    assertEquals(2, rows(playerId).size());

    // Age the first row past the retention window, exactly as a month of uptime would.
    long ancient = System.currentTimeMillis() - 40L * 86_400_000L;
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "UPDATE economy_ledger SET created_at = ? WHERE id = (SELECT MIN(id) FROM economy_ledger)")) {
        statement.setLong(1, ancient);
        statement.executeUpdate();
      }
    }
    economy.pruneLedger();
    assertEquals(1, rows(playerId).size());
  }

  private List<Row> rows(UUID accountId) throws Exception {
    List<Row> rows = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT op, amount, balance_after, counterparty, created_at FROM economy_ledger"
              + " WHERE account_id = ? ORDER BY id")) {
        statement.setString(1, accountId.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            rows.add(new Row(resultSet.getString("op"), resultSet.getLong("amount"),
                resultSet.getLong("balance_after"), resultSet.getString("counterparty"),
                resultSet.getLong("created_at")));
          }
        }
      }
    }
    return rows;
  }
}
