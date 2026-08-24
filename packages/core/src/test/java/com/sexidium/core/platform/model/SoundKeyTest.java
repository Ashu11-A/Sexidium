package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoundKeyTest {

  @Test
  void value_isRetained() {
    assertEquals("entity.player.levelup", new SoundKey("entity.player.levelup").value());
  }

  @Test
  void blank_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> new SoundKey(""));
    assertThrows(IllegalArgumentException.class, () -> new SoundKey("  "));
    assertThrows(IllegalArgumentException.class, () -> new SoundKey(null));
  }

  @Test
  void equality_byValue() {
    assertEquals(new SoundKey("a.b.c"), new SoundKey("a.b.c"));
    assertNotEquals(new SoundKey("a"), new SoundKey("b"));
  }
}
