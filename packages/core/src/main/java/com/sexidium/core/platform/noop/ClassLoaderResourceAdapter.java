package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.ResourceAdapter;

import java.io.InputStream;
import java.util.Optional;

public final class ClassLoaderResourceAdapter implements ResourceAdapter {
  private final ClassLoader classLoader;

  public ClassLoaderResourceAdapter(ClassLoader classLoader) {
    this.classLoader = classLoader == null ? ClassLoader.getSystemClassLoader() : classLoader;
  }

  @Override
  public Optional<InputStream> openResource(String resourcePath) {
    return Optional.ofNullable(classLoader.getResourceAsStream(resourcePath));
  }
}
