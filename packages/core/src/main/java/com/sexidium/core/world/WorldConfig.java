package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;

/**
 * Reads the temp/persistent world tuning from configuration and builds the derived border + world
 * settings from it. Owns the {@code worlds.temp.*} key space (with the legacy {@code temporary-worlds.*}
 * fallback) and the border/settings/worldgen-spec assembly that the lease policy used to inline.
 *
 * <p>Extracted from {@code AbstractWorldControl} as a pure helper: it holds no mutable world state and
 * makes no platform calls, it only translates configuration into value objects.</p>
 */
final class WorldConfig {
  private final ConfigurationAdapter configuration;

  WorldConfig(ConfigurationAdapter configuration) {
    this.configuration = configuration;
  }

  // ----- raw temp reads (worlds.temp.* with legacy temporary-worlds.* fallback) -----------------

  String readString(String key, String defaultValue) {
    if (configuration.contains("worlds.temp." + key)) {
      return configuration.getString("worlds.temp." + key, defaultValue);
    }
    return configuration.getString("temporary-worlds." + key, defaultValue);
  }

  boolean readBoolean(String key, boolean defaultValue) {
    if (configuration.contains("worlds.temp." + key)) {
      return configuration.getBoolean("worlds.temp." + key, defaultValue);
    }
    return configuration.getBoolean("temporary-worlds." + key, defaultValue);
  }

  int readInt(String key, int defaultValue) {
    if (configuration.contains("worlds.temp." + key)) {
      return configuration.getInt("worlds.temp." + key, defaultValue);
    }
    return configuration.getInt("temporary-worlds." + key, defaultValue);
  }

  double readDouble(String key, double defaultValue) {
    if (configuration.contains("worlds.temp." + key)) {
      return configuration.getDouble("worlds.temp." + key, defaultValue);
    }
    return configuration.getDouble("temporary-worlds." + key, defaultValue);
  }

  // ----- derived settings ----------------------------------------------------------------------

  double borderSize() {
    return Math.max(32.0, readDouble("world-size", 750.0));
  }

  /**
   * Experience-world border diameter from {@code worlds.experiences.world-size}. Defaults to 0 (NO
   * border) — experiences are borderless so the vanilla seed-generated stronghold + End portal is
   * reachable. NOT clamped to a minimum like {@link #borderSize()}, so 0 passes through to "leave the
   * border untouched / clear it". Temp/minigame worlds keep their own {@code worlds.temp.world-size}.
   */
  double experienceBorderSize() {
    return Math.max(0.0, configuration.getDouble("worlds.experiences.world-size", 0.0));
  }

  private int borderWarning() {
    return Math.max(0, readInt("warning-distance", 20));
  }

  private double borderDamage() {
    return Math.max(0.0, readDouble("damage-per-block", 0.2));
  }

  WorldSettings tempSettings() {
    return WorldSettings.forGameWorld(borderSize(), borderWarning(), borderDamage(),
        readBoolean("pvp", true), readString("difficulty", "NORMAL"),
        readBoolean("auto-save", true));
  }

  WorldSettings persistentSettings() {
    // Experiences are borderless by default (experienceBorderSize() = 0) so the vanilla stronghold/End
    // portal is reachable; the artificial reachable-End guarantee is disabled to match.
    return WorldSettings.forPersistentWorld(experienceBorderSize(), borderWarning(), borderDamage(),
        readBoolean("pvp", true), readString("difficulty", "NORMAL"));
  }
}
