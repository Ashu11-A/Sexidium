package com.sexidium.core.platform;

import java.util.Set;

public interface KitAdapter {
  boolean apply(PlayerAdapter playerAdapter, String kitName);

  boolean exists(String kitName);

  Set<String> names();

  void reload();
}
