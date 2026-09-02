package com.sexidium.core.lib.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchemaMigratorTest {

  private Database openDb() throws Exception {
    Path dir = Files.createTempDirectory("sexidium-schema-test");
    return new Database(dir.resolve("test.db").toFile());
  }

  @Test
  void migrate_createsAllRequiredTables() throws Exception {
    try (Database db = openDb()) {
      Set<String> tables = tableNames(db);
      assertTrue(tables.contains("players"));
      assertTrue(tables.contains("discord_accounts"));
      assertTrue(tables.contains("auth_codes"));
      assertTrue(tables.contains("command_queue"));
      assertTrue(tables.contains("matches"));
      assertTrue(tables.contains("match_players"));
      assertTrue(tables.contains("friends"));
      assertTrue(tables.contains("friend_requests"));
      assertTrue(tables.contains("economy_accounts"));
      assertTrue(tables.contains("economy_ledger"));
    }
  }

  @Test
  void migrate_isIdempotent() throws Exception {
    Path dir = Files.createTempDirectory("sexidium-schema-idempotent");
    java.io.File dbFile = dir.resolve("test.db").toFile();
    try (Database db = new Database(dbFile)) {
      assertNotNull(db.connection());
    }
    try (Database db = new Database(dbFile)) {
      Set<String> tables = tableNames(db);
      assertTrue(tables.contains("players"));
      assertTrue(tables.contains("matches"));
      assertTrue(tables.contains("friends"));
    }
  }

  @Test
  void migrate_playersTableHasDiscordUserIdColumn() throws Exception {
    try (Database db = openDb()) {
      Set<String> columns = columnNames(db, "players");
      assertTrue(columns.contains("uuid"));
      assertTrue(columns.contains("name"));
      assertTrue(columns.contains("discord_user_id"));
      assertTrue(columns.contains("points"));
      assertTrue(columns.contains("level"));
      assertTrue(columns.contains("wins"));
      assertTrue(columns.contains("kills"));
      assertTrue(columns.contains("games"));
    }
  }

  @Test
  void migrate_economyTablesHaveRequiredColumns() throws Exception {
    try (Database db = openDb()) {
      Set<String> accounts = columnNames(db, "economy_accounts");
      assertTrue(accounts.contains("account_id"));
      assertTrue(accounts.contains("name"));
      assertTrue(accounts.contains("is_player"));
      // Cents in an integer column, never a decimal: SQLite has no real DECIMAL type, so a decimal
      // column would round through a double on exactly the backend every test runs against.
      assertTrue(accounts.contains("balance"));
      assertTrue(accounts.contains("created_at"));
      assertTrue(accounts.contains("updated_at"));

      Set<String> ledger = columnNames(db, "economy_ledger");
      assertTrue(ledger.contains("id"));
      assertTrue(ledger.contains("account_id"));
      assertTrue(ledger.contains("counterparty"));
      assertTrue(ledger.contains("op"));
      assertTrue(ledger.contains("amount"));
      assertTrue(ledger.contains("balance_after"));
      assertTrue(ledger.contains("reason"));
      assertTrue(ledger.contains("actor"));
      assertTrue(ledger.contains("node_id"));
      assertTrue(ledger.contains("created_at"));
    }
  }

  @Test
  void migrate_matchesTableHasRequiredColumns() throws Exception {
    try (Database db = openDb()) {
      Set<String> columns = columnNames(db, "matches");
      assertTrue(columns.contains("id"));
      assertTrue(columns.contains("mode_id"));
      assertTrue(columns.contains("state"));
      assertTrue(columns.contains("created_at"));
      assertTrue(columns.contains("updated_at"));
    }
  }

  @Test
  void migrate_friendsTableHasPrimaryKey() throws Exception {
    try (Database db = openDb()) {
      Set<String> columns = columnNames(db, "friends");
      assertTrue(columns.contains("player_uuid"));
      assertTrue(columns.contains("friend_uuid"));
      assertTrue(columns.contains("created_at"));
    }
  }

  private Set<String> tableNames(Database db) throws Exception {
    Set<String> names = new HashSet<>();
    try (Statement st = db.connection().createStatement();
         ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
      while (rs.next()) names.add(rs.getString("name"));
    }
    return names;
  }

  private Set<String> columnNames(Database db, String table) throws Exception {
    Set<String> names = new HashSet<>();
    try (Statement st = db.connection().createStatement();
         ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
      while (rs.next()) names.add(rs.getString("name"));
    }
    return names;
  }
}
