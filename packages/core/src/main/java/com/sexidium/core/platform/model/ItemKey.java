package com.sexidium.core.platform.model;

import java.util.Locale;

public record ItemKey(String namespace, String value) {
  public ItemKey {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Item key cannot be blank.");
    }
    namespace = namespace == null || namespace.isBlank() ? "minecraft" : namespace.toLowerCase(Locale.ROOT);
    value = value.toLowerCase(Locale.ROOT);
  }

  public static ItemKey minecraft(String value) {
    return new ItemKey("minecraft", value);
  }

  /**
   * Parses {@code namespace:path} (or a bare {@code path}, defaulting to {@code minecraft}) into an
   * {@link ItemKey}, or returns null for a blank input. Lets config-driven block/item ids be read in
   * one place instead of each caller re-splitting on the colon.
   */
  public static ItemKey parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim();
    int colon = normalized.indexOf(':');
    if (colon > 0) {
      return new ItemKey(normalized.substring(0, colon), normalized.substring(colon + 1));
    }
    return minecraft(normalized);
  }

  public String qualifiedName() {
    return namespace + ":" + value;
  }
}
