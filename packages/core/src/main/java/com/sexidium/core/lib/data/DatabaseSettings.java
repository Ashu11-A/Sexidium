package com.sexidium.core.lib.data;

import com.sexidium.core.platform.ConfigurationAdapter;

import java.io.File;

/**
 * Resolves the {@code database.*} configuration block into a {@link DatabaseConfig}, shared by every
 * adapter (Paper, NeoForge) so the selection logic lives in one place. SQLite (the default) is read from
 * {@code database.file}; MySQL/PostgreSQL from {@code database.host/port/name/user/password/properties}.
 */
public final class DatabaseSettings {

  private DatabaseSettings() {}

  public static DatabaseConfig resolve(ConfigurationAdapter configuration, File dataDirectory) {
    SqlDialect dialect = SqlDialect.fromConfig(configuration.getString("database.type", "sqlite"));
    if (dialect == SqlDialect.SQLITE) {
      // An ABSOLUTE database.file is used as-is. new File(parent, child) joins even when the child
      // is absolute, which silently produced plugins/Sexidium/tmp/.../central.db and gave every node
      // its own private database while the operator believed they were sharing one -- the exact
      // configuration a small network uses.
      String configured = configuration.getString("database.file", "sexidium.db");
      File sqliteFile = new File(configured);
      if (!sqliteFile.isAbsolute()) {
        sqliteFile = new File(dataDirectory, configured);
      }
      return DatabaseConfig.sqlite(sqliteFile);
    }
    int defaultPort = dialect == SqlDialect.MYSQL ? 3306 : 5432;
    return DatabaseConfig.networked(
        dialect,
        configuration.getString("database.host", "localhost"),
        configuration.getInt("database.port", defaultPort),
        configuration.getString("database.name", "sexidium"),
        configuration.getString("database.user", "sexidium"),
        configuration.getString("database.password", ""),
        configuration.getString("database.properties", ""));
  }
}
