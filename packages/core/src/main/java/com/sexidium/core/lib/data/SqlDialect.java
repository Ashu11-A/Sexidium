package com.sexidium.core.lib.data;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * SQL dialect abstraction so the same schema and repositories run on SQLite (embedded, the default),
 * MySQL, and PostgreSQL. It captures the handful of places where the three backends genuinely diverge:
 * driver class, DDL type names, the auto-increment/identity column, upsert syntax, partial-index
 * support, the case-insensitive index expression, and per-connection session setup.
 *
 * <p>Everything else in the repositories is portable once it goes through this type — notably the
 * {@link #upsert} helper (SQLite/Postgres {@code ON CONFLICT ... DO UPDATE SET col=excluded.col}
 * vs MySQL {@code ON DUPLICATE KEY UPDATE col=VALUES(col)}) and the convention of writing
 * case-insensitive comparisons as {@code LOWER(col) = LOWER(?)} (valid on all three) instead of the
 * SQLite-only {@code COLLATE NOCASE}.
 */
public enum SqlDialect {
  SQLITE,
  MYSQL,
  POSTGRES;

  /** Resolves the configured {@code database.type} (mysql/mariadb, postgres/postgresql, else sqlite). */
  public static SqlDialect fromConfig(String raw) {
    if (raw == null) {
      return SQLITE;
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "mysql", "mariadb" -> MYSQL;
      case "postgres", "postgresql", "psql", "pg" -> POSTGRES;
      default -> SQLITE;
    };
  }

  public boolean isSqlite() {
    return this == SQLITE;
  }

  public boolean isMysql() {
    return this == MYSQL;
  }

  public boolean isPostgres() {
    return this == POSTGRES;
  }

  /**
   * A minimal JDBC URL of this dialect, used only to ask DriverManager whether a driver is
   * registered for it. Never opened.
   */
  public String probeUrl() {
    return switch (this) {
      case SQLITE -> "jdbc:sqlite::memory:";
      case MYSQL -> "jdbc:mysql://localhost/probe";
      case POSTGRES -> "jdbc:postgresql://localhost/probe";
    };
  }

  /** JDBC driver class name. Reported in errors; no longer used to LOAD the driver — see Database. */
  public String driverClass() {
    return switch (this) {
      case SQLITE -> "org.sqlite.JDBC";
      case MYSQL -> "com.mysql.cj.jdbc.Driver";
      case POSTGRES -> "org.postgresql.Driver";
    };
  }

  // ---- DDL type tokens ---------------------------------------------------------------------------

  /**
   * Short, indexable text (UUIDs, hashes, player/discord names) usable as a PRIMARY KEY or index key.
   * MySQL/InnoDB cannot key a {@code TEXT} column (and caps a utf8mb4 key at 191 chars), so the
   * networked dialects use {@code VARCHAR(191)}.
   */
  public String keyText() {
    return this == SQLITE ? "TEXT" : "VARCHAR(191)";
  }

  /** Free-form, possibly large text (serialized inventories, encoded prop blobs, JSON-ish payloads). */
  public String text() {
    return switch (this) {
      case SQLITE -> "TEXT";
      case MYSQL -> "LONGTEXT";
      case POSTGRES -> "TEXT";
    };
  }

  /** 64-bit integer column (the schema stores epoch millis and counters here). */
  public String intType() {
    return this == SQLITE ? "INTEGER" : "BIGINT";
  }

  /** Double-precision floating point column (player coordinates, health). */
  public String realType() {
    return switch (this) {
      case SQLITE -> "REAL";
      case MYSQL -> "DOUBLE";
      case POSTGRES -> "DOUBLE PRECISION";
    };
  }

  /** Full DDL for an auto-incrementing integer PRIMARY KEY (the caller supplies the column name). */
  public String autoIncrementPrimaryKey() {
    return switch (this) {
      case SQLITE -> "INTEGER PRIMARY KEY AUTOINCREMENT";
      case MYSQL -> "BIGINT AUTO_INCREMENT PRIMARY KEY";
      case POSTGRES -> "BIGSERIAL PRIMARY KEY";
    };
  }

  /** Whether {@code CREATE INDEX ... WHERE} (a partial index) is supported — SQLite + Postgres, not MySQL. */
  public boolean supportsPartialIndex() {
    return this != MYSQL;
  }

  /** Whether {@code CREATE INDEX IF NOT EXISTS} is supported — SQLite + Postgres, not MySQL. */
  public boolean supportsCreateIndexIfNotExists() {
    return this != MYSQL;
  }

  /**
   * Index expression for a case-insensitive name lookup. Queries compare with {@code LOWER(col)=LOWER(?)}
   * (portable everywhere); the backing index is a functional {@code LOWER(col)} index on SQLite/Postgres
   * and a plain column index on MySQL, whose default utf8mb4 collation is already case-insensitive.
   */
  public String caseInsensitiveIndexExpression(String column) {
    return switch (this) {
      case SQLITE -> column + " COLLATE NOCASE";
      case POSTGRES -> "LOWER(" + column + ")";
      case MYSQL -> column;
    };
  }

  /**
   * Per-connection session setup. SQLite keeps the rollback journal (so an external reader sees writes
   * immediately) and a busy timeout; the networked dialects need nothing here.
   */
  public void applyConnectionSetup(Connection connection) throws SQLException {
    if (this == SQLITE) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("PRAGMA journal_mode=DELETE");
        statement.executeUpdate("PRAGMA busy_timeout=5000");
      }
    }
  }

  // ---- Upsert ------------------------------------------------------------------------------------

  /**
   * Builds an {@code INSERT ... } upsert. The VALUES placeholders are positional and identical across
   * dialects (one {@code ?} per insert column), so a caller binds exactly {@code insertColumns.length}
   * parameters regardless of backend. On a conflict over {@code keyColumns}, every {@code updateColumns}
   * entry is overwritten with the incoming row's value: {@code excluded.col} on SQLite/Postgres,
   * {@code VALUES(col)} on MySQL. The conflict target must be a PRIMARY KEY or UNIQUE index.
   */
  public String upsert(String table, String[] insertColumns, String[] keyColumns, String[] updateColumns) {
    StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append('(')
        .append(String.join(", ", insertColumns)).append(") VALUES(");
    for (int i = 0; i < insertColumns.length; i++) {
      sql.append(i == 0 ? "?" : ", ?");
    }
    sql.append(')');
    if (this == MYSQL) {
      sql.append(" ON DUPLICATE KEY UPDATE ");
      for (int i = 0; i < updateColumns.length; i++) {
        if (i > 0) {
          sql.append(", ");
        }
        sql.append(updateColumns[i]).append("=VALUES(").append(updateColumns[i]).append(')');
      }
    } else {
      sql.append(" ON CONFLICT(").append(String.join(", ", keyColumns)).append(") DO UPDATE SET ");
      for (int i = 0; i < updateColumns.length; i++) {
        if (i > 0) {
          sql.append(", ");
        }
        sql.append(updateColumns[i]).append("=excluded.").append(updateColumns[i]);
      }
    }
    return sql.toString();
  }
}
