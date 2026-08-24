package com.sexidium.paper.adapter.config;

import com.sexidium.core.platform.ConfigurationAdapter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PaperConfigurationAdapter implements ConfigurationAdapter {

  /**
   * Prefix for the JVM startup override of any config key: {@code -Dsexidium.<path>=<value>}.
   *
   * <p>This is what lets every node in the network run off ONE config file. The file is identical on
   * all four backends except for six values that describe the node itself -- {@code network.node.id},
   * {@code .role}, {@code .address}, {@code .port}, {@code api.port}, {@code api.rpc-port} -- and a
   * per-node copy of a 1700-line file existing solely to carry six numbers is how the copies drift:
   * a setting changed on the lobby and forgotten on the workers is invisible until the behaviour
   * differs under load.</p>
   *
   * <p>Identity belongs on the command line and settings belong in the file, because the command line
   * is where the container already says which node it is. Deliberately checked BEFORE the file so the
   * shared copy can be read-only to a node that must not rewrite it.</p>
   *
   * <p>Applies to every key, not a fixed list: a caller that reads {@code some.new.key} through this
   * adapter is overridable the day it is written, with nothing to register.</p>
   */
  public static final String OVERRIDE_PREFIX = "sexidium.";

  private final JavaPlugin plugin;

  public PaperConfigurationAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  /** The startup override for {@code path}, or null when none was given. Blank counts as absent. */
  private static String override(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String value = System.getProperty(OVERRIDE_PREFIX + path);
    return value == null || value.isBlank() ? null : value.trim();
  }

  @Override
  public boolean getBoolean(String path, boolean defaultValue) {
    String override = override(path);
    if (override != null) {
      return Boolean.parseBoolean(override);
    }
    return plugin.getConfig().getBoolean(path, defaultValue);
  }

  @Override
  public int getInt(String path, int defaultValue) {
    return (int) getLong(path, defaultValue);
  }

  @Override
  public long getLong(String path, long defaultValue) {
    String override = override(path);
    if (override != null) {
      try {
        return Long.parseLong(override);
      } catch (NumberFormatException notANumber) {
        // A malformed override must not silently fall back to the file: on a shared config the file
        // holds the OTHER node's value, so "ignore it" means booting with somebody else's port.
        throw new IllegalStateException("Startup override -D" + OVERRIDE_PREFIX + path + "='" + override
            + "' is not a number. Fix the node's launch arguments; refusing to start on a value that"
            + " would otherwise be read from a config file shared with the other nodes.", notANumber);
      }
    }
    return plugin.getConfig().getLong(path, defaultValue);
  }

  @Override
  public double getDouble(String path, double defaultValue) {
    String override = override(path);
    if (override != null) {
      try {
        return Double.parseDouble(override);
      } catch (NumberFormatException notANumber) {
        throw new IllegalStateException("Startup override -D" + OVERRIDE_PREFIX + path + "='" + override
            + "' is not a number.", notANumber);
      }
    }
    return plugin.getConfig().getDouble(path, defaultValue);
  }

  @Override
  public String getString(String path, String defaultValue) {
    String override = override(path);
    return override != null ? override : plugin.getConfig().getString(path, defaultValue);
  }

  @Override
  public List<String> getStringList(String path) {
    String override = override(path);
    if (override == null) {
      return plugin.getConfig().getStringList(path);
    }
    // Comma-separated, because a JVM property is a single string and `network.node.capabilities` is
    // the one list a node genuinely owns. An explicit empty list is spelled "-" rather than "": a
    // blank property is treated as "not set" above, so there would otherwise be no way to say
    // "override this list to nothing" from the command line.
    if ("-".equals(override)) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (String part : override.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        values.add(trimmed);
      }
    }
    return List.copyOf(values);
  }

  @Override
  public List<Map<String, Object>> getMapList(String path) {
    List<Map<String, Object>> maps = new ArrayList<>();
    for (Map<?, ?> rawMap : plugin.getConfig().getMapList(path)) {
      Map<String, Object> convertedMap = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
        if (entry.getKey() != null) {
          Object value = unwrap(entry.getValue());
          convertedMap.put(String.valueOf(entry.getKey()), value);
        }
      }
      maps.add(convertedMap);
    }
    return maps;
  }

  @Override
  public Set<String> keys(String path) {
    ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
    return section == null ? Set.of() : section.getKeys(false);
  }

  @Override
  public Object get(String path) {
    return unwrap(plugin.getConfig().get(path));
  }

  @Override
  public boolean contains(String path) {
    return plugin.getConfig().contains(path);
  }

  @Override
  public void set(String path, Object value) {
    plugin.getConfig().set(path, value);
  }

  @Override
  public void reload() {
    plugin.reloadConfig();
  }

  @Override
  public void save() {
    plugin.saveConfig();
  }

  // Bukkit wraps section values in MemorySection instances; expose a copy
  // containing only plain Java values so the core can read them without
  // touching the live config.
  static Object unwrap(Object value) {
    if (value instanceof MemorySection section) {
      Map<String, Object> values = new LinkedHashMap<>();
      for (String key : section.getKeys(false)) {
        values.put(key, unwrap(section.get(key)));
      }
      return values;
    }
    return value;
  }
}
