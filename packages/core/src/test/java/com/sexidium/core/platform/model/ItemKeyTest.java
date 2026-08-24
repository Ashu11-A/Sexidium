package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemKeyTest {

  @Test
  void minecraft_factory_setsNamespace() {
    ItemKey key = ItemKey.minecraft("DIAMOND");
    assertEquals("minecraft", key.namespace());
    assertEquals("diamond", key.value());
  }

  @Test
  void nullNamespace_defaultsToMinecraft() {
    ItemKey key = new ItemKey(null, "stone");
    assertEquals("minecraft", key.namespace());
  }

  @Test
  void blankNamespace_defaultsToMinecraft() {
    ItemKey key = new ItemKey("  ", "stone");
    assertEquals("minecraft", key.namespace());
  }

  @Test
  void value_isLowercased() {
    ItemKey key = new ItemKey("minecraft", "DIAMOND_SWORD");
    assertEquals("diamond_sword", key.value());
  }

  @Test
  void namespace_isLowercased() {
    ItemKey key = new ItemKey("MyMod", "item");
    assertEquals("mymod", key.namespace());
  }

  @Test
  void qualifiedName_concatenatesWithColon() {
    ItemKey key = new ItemKey("minecraft", "stone");
    assertEquals("minecraft:stone", key.qualifiedName());
  }

  @Test
  void blankValue_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> new ItemKey("minecraft", ""));
    assertThrows(IllegalArgumentException.class, () -> new ItemKey("minecraft", "  "));
    assertThrows(IllegalArgumentException.class, () -> new ItemKey("minecraft", null));
  }

  @Test
  void equality_byValue() {
    assertEquals(ItemKey.minecraft("stone"), ItemKey.minecraft("stone"));
    assertNotEquals(ItemKey.minecraft("stone"), ItemKey.minecraft("dirt"));
  }
}
