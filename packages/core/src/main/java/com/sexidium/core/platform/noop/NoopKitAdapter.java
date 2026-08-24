package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.KitAdapter;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.Set;

public final class NoopKitAdapter implements KitAdapter {
  @Override
  public boolean apply(PlayerAdapter playerAdapter, String kitName) {
    return false;
  }

  @Override
  public boolean exists(String kitName) {
    return false;
  }

  @Override
  public Set<String> names() {
    return Set.of();
  }

  @Override
  public void reload() {
  }
}
