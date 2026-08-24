package com.sexidium.core.menu;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code sexidium:space} negative/positive horizontal shifter font — the half of {@link MenuArt}
 * that nudges the render cursor by an exact pixel count so the GUI art lands where we want. Owns the
 * Private-Use code-point allocation, the magnitude decomposition ({@link #shift(int)}) and the advance
 * table the pack generator serialises. Package-private implementation detail of {@link MenuArt}; callers
 * still go through {@code MenuArt.shift}/{@code MenuArt.spaceAdvances}.
 */
final class MenuArtSpace {
  // Private-Use code points for the space (shift) font, one magnitude per power of two, both signs.
  private static final char SPACE_BASE = '\uE100';
  // Magnitudes the space font can emit; any shift decomposes into a signed sum of these.
  private static final int[] SPACE_MAGNITUDES = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024};

  private static final Map<Character, Integer> SPACE_ADVANCES = buildSpaceAdvances();

  private MenuArtSpace() {
  }

  /**
   * The advance table for the {@code space} font provider: code point → horizontal pixel advance. The
   * pack generator serialises this verbatim into {@code assets/sexidium/font/space.json}.
   */
  static Map<Character, Integer> spaceAdvances() {
    return SPACE_ADVANCES;
  }

  /**
   * The raw character sequence (in {@code sexidium:space}) that advances the render cursor by exactly
   * {@code pixels} (negative pulls left). Decomposes the magnitude into the available powers of two so
   * any reachable offset is exact. Pure, so it is unit-testable headless.
   */
  static String shift(int pixels) {
    if (pixels == 0) {
      return "";
    }
    boolean negative = pixels < 0;
    int remaining = Math.abs(pixels);
    StringBuilder out = new StringBuilder();
    for (int index = SPACE_MAGNITUDES.length - 1; index >= 0 && remaining > 0; index--) {
      int magnitude = SPACE_MAGNITUDES[index];
      while (remaining >= magnitude) {
        out.append(spaceChar(index, negative));
        remaining -= magnitude;
      }
    }
    return out.toString();
  }

  // Two code points per magnitude: even = positive advance, odd = negative advance.
  private static char spaceChar(int magnitudeIndex, boolean negative) {
    return (char) (SPACE_BASE + magnitudeIndex * 2 + (negative ? 1 : 0));
  }

  // Insertion-ordered + wrapped (NOT Map.copyOf): SexidiumResourcePack.spaceFont() serialises
  // font/space.json by iterating this map's entries, so the byte order — and the pack SHA-1 — must be
  // stable across JVM runs. Map.copyOf's iteration order is salt-randomised per JVM; a LinkedHashMap
  // preserves the deterministic magnitude order this loop inserts in.
  private static Map<Character, Integer> buildSpaceAdvances() {
    Map<Character, Integer> advances = new LinkedHashMap<>();
    for (int index = 0; index < SPACE_MAGNITUDES.length; index++) {
      advances.put(spaceChar(index, false), SPACE_MAGNITUDES[index]);
      advances.put(spaceChar(index, true), -SPACE_MAGNITUDES[index]);
    }
    return Collections.unmodifiableMap(advances);
  }
}
