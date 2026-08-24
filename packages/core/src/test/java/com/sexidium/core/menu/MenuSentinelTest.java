package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MenuSentinelTest {
  @Test
  void encodeRoundTrips() {
    String original = "<gradient:#ff5f6d:#ffc371>Sexidium</gradient>";
    String encoded = MenuSentinel.encode(original);

    assertTrue(MenuSentinel.isSexidium(encoded));
    assertEquals(original, MenuSentinel.strip(encoded));
    // The marker must be a single invisible padding char, so the visible title is preserved verbatim.
    assertEquals(original.length() + 1, encoded.length());
  }

  @Test
  void encodeIsIdempotent() {
    String once = MenuSentinel.encode("hello");
    String twice = MenuSentinel.encode(once);
    assertEquals(once, twice);
    assertEquals("hello", MenuSentinel.strip(twice));
  }

  @Test
  void plainTitlesAreNotSexidiumAndStripIsNoOp() {
    assertFalse(MenuSentinel.isSexidium("Just a chest"));
    assertFalse(MenuSentinel.isSexidium(""));
    assertFalse(MenuSentinel.isSexidium(null));
    assertEquals("Just a chest", MenuSentinel.strip("Just a chest"));
    assertEquals("", MenuSentinel.strip(null));
  }

  @Test
  void nullTitleEncodesToAValidMarkedEmptyTitle() {
    String encoded = MenuSentinel.encode(null);
    assertTrue(MenuSentinel.isSexidium(encoded));
    assertEquals("", MenuSentinel.strip(encoded));
  }
}
