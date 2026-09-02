package com.sexidium.paper.adapter.ui.betterhud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The version→format table is the one data-driven decision the plugin makes about resource packs
 * (Plan §7: "Unit-test PackFormats boundaries; it is pure and needs no server"). The boundaries that
 * matter are the ones where a naive parser would misplace a version: patch numbers must not move the
 * format, unknown minors must degrade to "no opinion" (-1), and garbage must never throw.
 */
class PackFormatsTest {

  @Test
  void knownMinorsMapToTheirFormats() {
    assertEquals(84, PackFormats.of("26.1"));
    assertEquals(88, PackFormats.of("26.2"));
  }

  @Test
  void thePatchNeverMovesTheFormat() {
    assertEquals(84, PackFormats.of("26.1.2"));
    assertEquals(84, PackFormats.of("26.1.0"));
    assertEquals(88, PackFormats.of("26.2.1"));
  }

  @Test
  void anUnplacedVersionIsAnOpinionOfMinusOneNotAThrow() {
    assertEquals(-1, PackFormats.of("26.3"));
    assertEquals(-1, PackFormats.of("25.6"));
    // A pinned release candidate keeps its minor: the format follows 26.x, not the RC suffix.
    assertEquals(88, PackFormats.of("26.2-rc-1"));
  }

  @Test
  void nullBlankAndGarbageDegrade() {
    assertEquals(-1, PackFormats.of(null));
    assertEquals(-1, PackFormats.of(""));
    assertEquals(-1, PackFormats.of("   "));
    assertEquals(-1, PackFormats.of("garbage"));
    assertEquals(-1, PackFormats.of("26"));
    assertEquals(-1, PackFormats.of(".1"));
  }

  @Test
  void surroundingWhitespaceIsTolerated() {
    assertEquals(84, PackFormats.of(" 26.1.2 "));
  }
}
