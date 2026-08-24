package com.sexidium.core.platform;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface ConfigurationAdapter {
  boolean getBoolean(String path, boolean defaultValue);

  int getInt(String path, int defaultValue);

  long getLong(String path, long defaultValue);

  double getDouble(String path, double defaultValue);

  String getString(String path, String defaultValue);

  List<String> getStringList(String path);

  List<Map<String, Object>> getMapList(String path);

  Set<String> keys(String path);

  Object get(String path);

  boolean contains(String path);

  void set(String path, Object value);

  void reload();

  void save();
}
