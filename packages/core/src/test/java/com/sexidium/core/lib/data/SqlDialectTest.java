package com.sexidium.core.lib.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlDialectTest {

  @Test
  void fromConfig_resolvesAliasesAndDefaultsToSqlite() {
    assertEquals(SqlDialect.SQLITE, SqlDialect.fromConfig(null));
    assertEquals(SqlDialect.SQLITE, SqlDialect.fromConfig("sqlite"));
    assertEquals(SqlDialect.SQLITE, SqlDialect.fromConfig("something-unknown"));
    assertEquals(SqlDialect.MYSQL, SqlDialect.fromConfig("mysql"));
    assertEquals(SqlDialect.MYSQL, SqlDialect.fromConfig("MariaDB"));
    assertEquals(SqlDialect.POSTGRES, SqlDialect.fromConfig("postgres"));
    assertEquals(SqlDialect.POSTGRES, SqlDialect.fromConfig("  POSTGRESQL "));
  }

  @Test
  void typeTokens_differPerBackend() {
    assertEquals("TEXT", SqlDialect.SQLITE.keyText());
    assertEquals("VARCHAR(191)", SqlDialect.MYSQL.keyText());
    assertEquals("VARCHAR(191)", SqlDialect.POSTGRES.keyText());

    assertEquals("INTEGER", SqlDialect.SQLITE.intType());
    assertEquals("BIGINT", SqlDialect.MYSQL.intType());

    assertEquals("REAL", SqlDialect.SQLITE.realType());
    assertEquals("DOUBLE", SqlDialect.MYSQL.realType());
    assertEquals("DOUBLE PRECISION", SqlDialect.POSTGRES.realType());

    assertEquals("LONGTEXT", SqlDialect.MYSQL.text());
    assertEquals("TEXT", SqlDialect.POSTGRES.text());
  }

  @Test
  void autoIncrementPrimaryKey_isDialectSpecific() {
    assertEquals("INTEGER PRIMARY KEY AUTOINCREMENT", SqlDialect.SQLITE.autoIncrementPrimaryKey());
    assertEquals("BIGINT AUTO_INCREMENT PRIMARY KEY", SqlDialect.MYSQL.autoIncrementPrimaryKey());
    assertEquals("BIGSERIAL PRIMARY KEY", SqlDialect.POSTGRES.autoIncrementPrimaryKey());
  }

  @Test
  void indexCapabilities_matchBackend() {
    assertTrue(SqlDialect.SQLITE.supportsPartialIndex());
    assertTrue(SqlDialect.POSTGRES.supportsPartialIndex());
    assertFalse(SqlDialect.MYSQL.supportsPartialIndex());

    assertTrue(SqlDialect.SQLITE.supportsCreateIndexIfNotExists());
    assertFalse(SqlDialect.MYSQL.supportsCreateIndexIfNotExists());

    assertEquals("name COLLATE NOCASE", SqlDialect.SQLITE.caseInsensitiveIndexExpression("name"));
    assertEquals("LOWER(name)", SqlDialect.POSTGRES.caseInsensitiveIndexExpression("name"));
    assertEquals("name", SqlDialect.MYSQL.caseInsensitiveIndexExpression("name"));
  }

  @Test
  void upsert_postgresAndSqliteUseOnConflictExcluded() {
    String expected = "INSERT INTO friends(player_uuid, friend_uuid, friend_name, created_at) "
        + "VALUES(?, ?, ?, ?) ON CONFLICT(player_uuid, friend_uuid) DO UPDATE SET "
        + "friend_name=excluded.friend_name";
    String[] columns = {"player_uuid", "friend_uuid", "friend_name", "created_at"};
    String[] keys = {"player_uuid", "friend_uuid"};
    String[] updates = {"friend_name"};
    assertEquals(expected, SqlDialect.SQLITE.upsert("friends", columns, keys, updates));
    assertEquals(expected, SqlDialect.POSTGRES.upsert("friends", columns, keys, updates));
  }

  @Test
  void upsert_mysqlUsesOnDuplicateKeyValues() {
    String expected = "INSERT INTO friends(player_uuid, friend_uuid, friend_name, created_at) "
        + "VALUES(?, ?, ?, ?) ON DUPLICATE KEY UPDATE friend_name=VALUES(friend_name)";
    assertEquals(expected, SqlDialect.MYSQL.upsert(
        "friends",
        new String[] {"player_uuid", "friend_uuid", "friend_name", "created_at"},
        new String[] {"player_uuid", "friend_uuid"},
        new String[] {"friend_name"}));
  }

  @Test
  void upsert_bindsOnePlaceholderPerColumnRegardlessOfDialect() {
    String[] columns = {"id", "mode_id", "data", "updated_at"};
    String[] keys = {"id"};
    String[] updates = {"mode_id", "data", "updated_at"};
    for (SqlDialect dialect : SqlDialect.values()) {
      long placeholders = dialect.upsert("matches", columns, keys, updates).chars().filter(c -> c == '?').count();
      assertEquals(columns.length, placeholders, "placeholder count for " + dialect);
    }
  }

  @Test
  void jdbcUrl_isBuiltPerDialect() {
    assertTrue(DatabaseConfig.networked(SqlDialect.MYSQL, "db.host", 3306, "sexidium", "u", "p", "")
        .jdbcUrl().startsWith("jdbc:mysql://db.host:3306/sexidium?"));
    assertEquals("jdbc:postgresql://db.host:5432/sexidium",
        DatabaseConfig.networked(SqlDialect.POSTGRES, "db.host", 5432, "sexidium", "u", "p", "").jdbcUrl());
    assertTrue(DatabaseConfig.networked(SqlDialect.POSTGRES, "db.host", 5432, "sexidium", "u", "p", "sslmode=require")
        .jdbcUrl().endsWith("/sexidium?sslmode=require"));
  }
}
