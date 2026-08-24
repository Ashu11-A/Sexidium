package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class MenuArtTest {

  /** Every shift decomposes into space-font chars whose advances sum back to exactly the request. */
  @Test
  void shiftAdvancesSumToRequestedPixels() {
    Map<Character, Integer> advances = MenuArt.spaceAdvances();
    int[] cases = {0, 1, -1, 7, -7, 8, -8, 64, 255, -256, 1000, -2047, 2048, -3000};
    for (int pixels : cases) {
      String emitted = MenuArt.shift(pixels);
      int sum = 0;
      for (int index = 0; index < emitted.length(); index++) {
        Integer advance = advances.get(emitted.charAt(index));
        assertNotNull(advance, "every emitted char must be a defined space-font glyph");
        sum += advance;
      }
      assertEquals(pixels, sum, "shift(" + pixels + ") must advance exactly that many pixels");
    }
  }

  @Test
  void shiftZeroIsEmpty() {
    assertEquals("", MenuArt.shift(0));
  }

  @Test
  void spaceAdvancesAreSignSymmetric() {
    int positives = 0;
    for (Map.Entry<Character, Integer> entry : MenuArt.spaceAdvances().entrySet()) {
      if (entry.getValue() > 0) {
        positives++;
        assertTrue(MenuArt.spaceAdvances().containsValue(-entry.getValue()),
            "each positive advance needs a negative counterpart");
      }
    }
    assertEquals(MenuArt.spaceAdvances().size(), positives * 2, "advances come in +/- pairs");
  }

  @Test
  void glyphsHaveDistinctCodepointsClearOfTheSentinel() {
    boolean[] seen = new boolean[0x10000];
    assertFalse(MenuArt.glyphs().isEmpty());
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      int codepoint = glyph.codepoint();
      assertTrue(codepoint != MenuSentinel.MARKER, "glyphs must not collide with the menu sentinel");
      assertFalse(seen[codepoint], "glyph code points must be unique");
      seen[codepoint] = true;
      assertTrue(glyph.height() > 0 && glyph.ascent() >= 0);
    }
    assertNotNull(MenuArt.glyph(MenuArt.chestGlyphId(6)));
    assertNull(MenuArt.glyph("no-such-glyph"));
  }

  @Test
  void modelIdIsNamespacedSectionPath() {
    // Icon ids are <section>/<name> paths into the ./icons/ set; the model id is sexidium:<id>.
    assertEquals("sexidium:" + MenuArt.ICON_LOBBY, MenuArt.model(MenuArt.ICON_LOBBY));
    assertEquals("sexidium:system/home", MenuArt.model(MenuArt.ICON_LOBBY));
  }

  /**
   * The icon list order IS the order the pack writes its per-icon zip entries, so it must be a stable,
   * salt-independent sequence — otherwise the pack SHA-1 changes every JVM restart and clients re-download
   * it on every join. We emit a sorted-by-id sequence; assert it really is sorted. (Guards against a
   * regression to a {@code Map.copyOf(...).values()}-driven order, whose iteration is per-JVM salted.)
   */
  @Test
  void iconListIsSortedForAStablePackHash() {
    java.util.List<String> ids = MenuArt.icons().stream().map(MenuArt.IconModel::id).toList();
    java.util.List<String> sorted = new java.util.ArrayList<>(ids);
    java.util.Collections.sort(sorted);
    assertEquals(sorted, ids, "icon order must be sorted so the pack SHA-1 is stable across JVM runs");
  }

  /** The space-advance map is serialised into font/space.json in iteration order; it must be stable. */
  @Test
  void spaceAdvanceKeysAreInStableAscendingOrder() {
    java.util.List<Character> keys = new java.util.ArrayList<>(MenuArt.spaceAdvances().keySet());
    java.util.List<Character> sorted = new java.util.ArrayList<>(keys);
    java.util.Collections.sort(sorted);
    assertEquals(sorted, keys, "space-advance key order must be deterministic for a stable pack SHA-1");
  }
}
