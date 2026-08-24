package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.ConfigurationAdapter;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class PropertiesConfigurationAdapter implements ConfigurationAdapter {
  private final Properties properties = new Properties();

  @Override
  public boolean getBoolean(String path, boolean defaultValue) {
    String value = properties.getProperty(path);
    return value == null ? defaultValue : Boolean.parseBoolean(value);
  }

  @Override
  public int getInt(String path, int defaultValue) {
    try {
      return Integer.parseInt(properties.getProperty(path, Integer.toString(defaultValue)));
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  @Override
  public long getLong(String path, long defaultValue) {
    try {
      return Long.parseLong(properties.getProperty(path, Long.toString(defaultValue)));
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  @Override
  public double getDouble(String path, double defaultValue) {
    try {
      return Double.parseDouble(properties.getProperty(path, Double.toString(defaultValue)));
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  @Override
  public String getString(String path, String defaultValue) {
    return properties.getProperty(path, defaultValue);
  }

  @Override
  public List<String> getStringList(String path) {
    String value = properties.getProperty(path);
    return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
  }

  @Override
  public List<Map<String, Object>> getMapList(String path) {
    return List.of();
  }

  @Override
  public Set<String> keys(String path) {
    return Set.of();
  }

  @Override
  public Object get(String path) {
    return properties.get(path);
  }

  @Override
  public boolean contains(String path) {
    return properties.containsKey(path);
  }

  @Override
  public void set(String path, Object value) {
    if (value == null) {
      properties.remove(path);
    } else {
      properties.setProperty(path, String.valueOf(value));
    }
  }

  @Override
  public void reload() {
  }

  @Override
  public void save() {
  }
}
