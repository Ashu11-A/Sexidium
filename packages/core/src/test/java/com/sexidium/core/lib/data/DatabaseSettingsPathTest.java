package com.sexidium.core.lib.data;

import com.sexidium.core.platform.ConfigurationAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseSettingsPathTest {

  private static final class Config implements ConfigurationAdapter {
    private final Map<String, String> values = new HashMap<>();

    Config with(String path, String value) {
      values.put(path, value);
      return this;
    }

    @Override public String getString(String path, String defaultValue) {
      return values.getOrDefault(path, defaultValue);
    }
    @Override public boolean getBoolean(String path, boolean d) { return d; }
    @Override public int getInt(String path, int d) { return d; }
    @Override public long getLong(String path, long d) { return d; }
    @Override public double getDouble(String path, double d) { return d; }
    @Override public List<String> getStringList(String path) { return List.of(); }
    @Override public List<Map<String, Object>> getMapList(String path) { return List.of(); }
    @Override public Set<String> keys(String path) { return Set.of(); }
    @Override public Object get(String path) { return values.get(path); }
    @Override public boolean contains(String path) { return values.containsKey(path); }
    @Override public void set(String path, Object value) { }
    @Override public void reload() { }
    @Override public void save() { }
  }

  @TempDir
  Path dataDirectory;

  @Test
  @DisplayName("a relative database.file stays inside the plugin data directory")
  void relativePath() {
    DatabaseConfig config = DatabaseSettings.resolve(
        new Config().with("database.type", "sqlite").with("database.file", "sexidium.db"),
        dataDirectory.toFile());

    assertEquals(new File(dataDirectory.toFile(), "sexidium.db"), config.sqliteFile());
  }

  @Test
  @DisplayName("an ABSOLUTE database.file is used as given, so several nodes can share one file")
  void absolutePath() {
    // Regression: this used to be joined onto the data directory, producing
    // plugins/Sexidium/tmp/.../central.db and giving every node its own private database
    // while the operator believed they were sharing one.
    File shared = new File("/tmp/sexidium-shared-test/central.db");

    DatabaseConfig config = DatabaseSettings.resolve(
        new Config().with("database.type", "sqlite").with("database.file", shared.getAbsolutePath()),
        dataDirectory.toFile());

    assertEquals(shared, config.sqliteFile());
  }
}
