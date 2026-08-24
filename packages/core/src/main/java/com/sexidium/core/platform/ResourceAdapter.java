package com.sexidium.core.platform;

import java.io.InputStream;
import java.util.Optional;

public interface ResourceAdapter {
  Optional<InputStream> openResource(String resourcePath);
}
