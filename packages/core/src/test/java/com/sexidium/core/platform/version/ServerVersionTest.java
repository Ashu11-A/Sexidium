package com.sexidium.core.platform.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerVersionTest {

  @Test
  @DisplayName("parses full, partial and suffixed version strings alike")
  void parsesTheShapesPlatformsActuallyReport() {
    assertEquals(new ServerVersion(26, 1, 2, "26.1.2"), ServerVersion.parse("26.1.2"));
    assertEquals(new ServerVersion(26, 2, 0, "26.2"), ServerVersion.parse("26.2"));
    assertEquals(new ServerVersion(1, 21, 4, "1.21.4-R0.1-SNAPSHOT"),
        ServerVersion.parse("1.21.4-R0.1-SNAPSHOT"));
    // Paper's getBukkitVersion cut at the first dash is what feeds the last-resort source.
    ServerVersion fromBukkit = ServerVersion.parse("26.1.2-74-a1b2c3");
    assertEquals(26, fromBukkit.major());
    assertEquals(1, fromBukkit.minor());
    assertEquals(2, fromBukkit.patch());
    // Record equality keeps the raw string; parts are what comparisons use.
    assertEquals("26.1.2-74-a1b2c3", fromBukkit.raw());
  }

  @Test
  @DisplayName("garbage degrades to UNKNOWN, never to a guess")
  void garbageIsUnknown() {
    assertFalse(ServerVersion.parse(null).known());
    assertFalse(ServerVersion.parse("").known());
    assertFalse(ServerVersion.parse("   ").known());
    assertFalse(ServerVersion.parse("unknown").known());
    assertEquals(ServerVersion.UNKNOWN, ServerVersion.parse("no-digits-here"));
  }

  @Test
  @DisplayName("atLeast compares major then minor; UNKNOWN is never 'at least'")
  void atLeastComparesAndFailsClosed() {
    assertTrue(ServerVersion.parse("26.1.2").atLeast(26, 1));
    assertTrue(ServerVersion.parse("26.2").atLeast(26, 1));
    assertFalse(ServerVersion.parse("26.1.2").atLeast(26, 2));
    assertFalse(ServerVersion.parse("25.9").atLeast(26, 1));
    assertFalse(ServerVersion.UNKNOWN.atLeast(1, 0), "an unknown version must not read as new");
    assertFalse(ServerVersion.UNKNOWN.atLeast(0, 0));
  }
}
