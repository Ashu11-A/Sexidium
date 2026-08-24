package com.sexidium.core.lib.data;

import java.io.File;
import java.util.Properties;

/**
 * Connection settings for the {@link Database}, resolved from the {@code database.*} config block.
 * SQLite is described by a single file; MySQL/PostgreSQL by host/port/name/credentials. Adapters build
 * this from their configuration and hand it to {@link Database}.
 */
public record DatabaseConfig(
    SqlDialect dialect,
    File sqliteFile,
    String host,
    int port,
    String databaseName,
    String user,
    String password,
    String extraParameters
) {

  /** The default embedded backend: a single SQLite file under the plugin/mod data folder. */
  public static DatabaseConfig sqlite(File file) {
    return new DatabaseConfig(SqlDialect.SQLITE, file, null, 0, null, null, null, null);
  }

  /** A networked backend (MySQL or PostgreSQL). */
  public static DatabaseConfig networked(
      SqlDialect dialect, String host, int port, String databaseName,
      String user, String password, String extraParameters) {
    return new DatabaseConfig(dialect, null, host, port, databaseName, user, password, extraParameters);
  }

  public String jdbcUrl() {
    return switch (dialect) {
      case SQLITE -> "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
      case MYSQL -> "jdbc:mysql://" + host + ":" + (port > 0 ? port : 3306) + "/" + databaseName + mysqlParameters();
      case POSTGRES -> "jdbc:postgresql://" + host + ":" + (port > 0 ? port : 5432) + "/" + databaseName + postgresParameters();
    };
  }

  private String mysqlParameters() {
    StringBuilder parameters = new StringBuilder(
        "?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&autoReconnect=true&useSSL=false");
    if (extraParameters != null && !extraParameters.isBlank()) {
      parameters.append('&').append(extraParameters.strip());
    }
    return parameters.toString();
  }

  private String postgresParameters() {
    if (extraParameters == null || extraParameters.isBlank()) {
      return "";
    }
    return "?" + extraParameters.strip();
  }

  public Properties connectionProperties() {
    Properties properties = new Properties();
    if (dialect != SqlDialect.SQLITE) {
      if (user != null) {
        properties.setProperty("user", user);
      }
      if (password != null) {
        properties.setProperty("password", password);
      }
    }
    return properties;
  }

  /** A human-readable description for startup logging (never includes the password). */
  public String describe() {
    if (dialect == SqlDialect.SQLITE) {
      return "SQLite (" + (sqliteFile == null ? "?" : sqliteFile.getName()) + ")";
    }
    return dialect.name().toLowerCase(java.util.Locale.ROOT) + " " + host + ":"
        + (port > 0 ? port : (dialect == SqlDialect.MYSQL ? 3306 : 5432)) + "/" + databaseName;
  }
}
