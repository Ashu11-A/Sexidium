package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TitleSpecTest {

  @Test
  void fields_areAccessible() {
    TitleSpec spec = new TitleSpec("<red>Title", "<blue>Sub", 500, 2000, 500);
    assertEquals("<red>Title", spec.titleMiniMessage());
    assertEquals("<blue>Sub", spec.subtitleMiniMessage());
    assertEquals(500, spec.fadeInMillis());
    assertEquals(2000, spec.stayMillis());
    assertEquals(500, spec.fadeOutMillis());
  }

  @Test
  void equality_byValue() {
    TitleSpec a = new TitleSpec("t", "s", 1, 2, 3);
    TitleSpec b = new TitleSpec("t", "s", 1, 2, 3);
    assertEquals(a, b);
  }
}
