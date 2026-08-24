package com.sexidium.core.platform;

public interface WorldLease extends AutoCloseable {
  WorldAdapter world();

  @Override
  void close();
}
