package com.sexidium.core.economy;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;

import java.nio.file.Path;
import java.sql.SQLException;

/**
 * A real {@link Database} in a {@code @TempDir} plus an {@link EconomyService} on top of it — the
 * same shape {@code SchemaMigratorTest} and {@code WorldLeaseFenceConcurrencyTest} use.
 *
 * <p>A real SQLite file and not a mock, deliberately: the whole correctness argument for this
 * subsystem is a single conditional UPDATE, and a mocked database would prove nothing about it.</p>
 */
final class EconomyTestSupport {

  static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private EconomyTestSupport() {
  }

  static Database database(Path tempDir) throws SQLException {
    return new Database(tempDir.resolve("economy-test.db").toFile());
  }

  static PropertiesConfigurationAdapter config() {
    PropertiesConfigurationAdapter configuration = new PropertiesConfigurationAdapter();
    // Zero TTL so a test reads the row every time. The cache has its own test; everywhere else it
    // would only hide the value the database actually holds.
    configuration.set("economy.cache-ttl-seconds", 0);
    return configuration;
  }

  static EconomyService service(Database database, PropertiesConfigurationAdapter configuration) {
    return new EconomyService(configuration, SILENT, database, new CurrencyFormat(configuration), "test-node");
  }
}
