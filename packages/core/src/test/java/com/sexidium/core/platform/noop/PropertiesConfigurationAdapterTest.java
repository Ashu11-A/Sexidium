package com.sexidium.core.platform.noop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PropertiesConfigurationAdapterTest {

  @Test
  void getBoolean_returnsDefault_whenMissing() {
    assertTrue(new PropertiesConfigurationAdapter().getBoolean("x", true));
    assertFalse(new PropertiesConfigurationAdapter().getBoolean("x", false));
  }

  @Test
  void getBoolean_returnsStoredValue() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("flag", true);
    assertTrue(cfg.getBoolean("flag", false));
  }

  @Test
  void getInt_returnsDefault_whenMissing() {
    assertEquals(42, new PropertiesConfigurationAdapter().getInt("x", 42));
  }

  @Test
  void getInt_returnsStoredValue() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("num", 99);
    assertEquals(99, cfg.getInt("num", 0));
  }

  @Test
  void getInt_invalidValue_returnsDefault() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("bad", "not-a-number");
    assertEquals(7, cfg.getInt("bad", 7));
  }

  @Test
  void getLong_returnsDefault_whenMissing() {
    assertEquals(1000L, new PropertiesConfigurationAdapter().getLong("x", 1000L));
  }

  @Test
  void getLong_returnsStoredValue() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("big", 9_000_000_000L);
    assertEquals(9_000_000_000L, cfg.getLong("big", 0L));
  }

  @Test
  void getDouble_returnsDefault_whenMissing() {
    assertEquals(3.14, new PropertiesConfigurationAdapter().getDouble("x", 3.14));
  }

  @Test
  void getDouble_returnsStoredValue() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("pi", "3.14159");
    assertEquals(3.14159, cfg.getDouble("pi", 0.0), 0.0001);
  }

  @Test
  void getString_returnsDefault_whenMissing() {
    assertEquals("default", new PropertiesConfigurationAdapter().getString("x", "default"));
  }

  @Test
  void getString_returnsStoredValue() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("key", "hello");
    assertEquals("hello", cfg.getString("key", "?"));
  }

  @Test
  void getStringList_whenEmpty_returnsEmptyList() {
    assertTrue(new PropertiesConfigurationAdapter().getStringList("x").isEmpty());
  }

  @Test
  void getStringList_splitsOnComma() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("list", "a,b,c");
    assertEquals(List.of("a", "b", "c"), cfg.getStringList("list"));
  }

  @Test
  void getMapList_alwaysEmpty() {
    assertTrue(new PropertiesConfigurationAdapter().getMapList("x").isEmpty());
  }

  @Test
  void keys_alwaysEmpty() {
    assertTrue(new PropertiesConfigurationAdapter().keys("x").isEmpty());
  }

  @Test
  void get_returnsRawValue() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("k", "v");
    assertEquals("v", cfg.get("k"));
  }

  @Test
  void set_nullValue_removesKey() {
    PropertiesConfigurationAdapter cfg = new PropertiesConfigurationAdapter();
    cfg.set("k", "v");
    cfg.set("k", (String) null);
    assertNull(cfg.get("k"));
  }

  @Test
  void reload_doesNotThrow() {
    assertDoesNotThrow(() -> new PropertiesConfigurationAdapter().reload());
  }

  @Test
  void save_doesNotThrow() {
    assertDoesNotThrow(() -> new PropertiesConfigurationAdapter().save());
  }
}
