package com.sexidium.core.platform.model;

import java.util.Locale;

/**
 * The dimension class a world belongs to. Used to ask a world for one of its linked siblings
 * ({@link com.sexidium.core.platform.WorldAdapter#dimension}) so an experience can drop the player into
 * its Nether or its End instead of its Overworld.
 */
public enum WorldDimension {
  OVERWORLD,
  NETHER,
  END;

  /** Parses a dimension name (case-insensitive); anything unknown/blank resolves to {@link #OVERWORLD}. */
  public static WorldDimension of(String value) {
    if (value == null || value.isBlank()) {
      return OVERWORLD;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "nether", "the_nether" -> NETHER;
      case "end", "the_end" -> END;
      default -> OVERWORLD;
    };
  }
}
