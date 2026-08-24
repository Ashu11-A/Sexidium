package com.sexidium.core.game.persist;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PropsTest {

  @Test
  void set_and_get_string() {
    Props p = new Props();
    p.set("key", "value");
    assertEquals("value", p.get("key"));
  }

  @Test
  void set_int_and_getInt() {
    Props p = new Props();
    p.set("num", 42);
    assertEquals(42, p.getInt("num", 0));
  }

  @Test
  void set_long_and_getLong() {
    Props p = new Props();
    p.set("big", 9_000_000_000L);
    assertEquals(9_000_000_000L, p.getLong("big", 0L));
  }

  @Test
  void set_boolean_and_getBoolean() {
    Props p = new Props();
    p.set("flag", true);
    assertTrue(p.getBoolean("flag", false));
    p.set("flag", false);
    assertFalse(p.getBoolean("flag", true));
  }

  @Test
  void get_missingKey_returnsNull() {
    assertNull(new Props().get("missing"));
  }

  @Test
  void get_withDefault_returnsMissingDefault() {
    assertEquals("default", new Props().get("missing", "default"));
  }

  @Test
  void getInt_invalidValue_returnsDefault() {
    Props p = new Props();
    p.set("bad", "not-a-number");
    assertEquals(99, p.getInt("bad", 99));
  }

  @Test
  void getLong_invalidValue_returnsDefault() {
    Props p = new Props();
    p.set("bad", "xyz");
    assertEquals(-1L, p.getLong("bad", -1L));
  }

  @Test
  void has_returnsFalse_whenMissing() {
    assertFalse(new Props().has("k"));
  }

  @Test
  void has_returnsTrue_whenSet() {
    Props p = new Props();
    p.set("k", "v");
    assertTrue(p.has("k"));
  }

  @Test
  void set_nullKey_isIgnored() {
    Props p = new Props();
    assertDoesNotThrow(() -> p.set(null, "v"));
    assertNull(p.get(null));
  }

  @Test
  void set_nullValue_isIgnored() {
    Props p = new Props();
    p.set("k", (String) null);
    assertNull(p.get("k"));
  }

  @Test
  void putAll_copiesFromOther() {
    Props a = new Props();
    a.set("x", "1");
    Props b = new Props();
    b.set("y", "2");
    b.putAll(a);
    assertEquals("1", b.get("x"));
    assertEquals("2", b.get("y"));
  }

  @Test
  void putAll_withNull_doesNotCrash() {
    assertDoesNotThrow(() -> new Props().putAll(null));
  }

  @Test
  void encode_decode_roundtrip() {
    Props original = new Props();
    original.set("mode", "combat");
    original.set("round", 3);
    original.set("flag", true);

    String encoded = original.encode();
    Props decoded = Props.decode(encoded);

    assertEquals("combat", decoded.get("mode"));
    assertEquals(3, decoded.getInt("round", 0));
    assertTrue(decoded.getBoolean("flag", false));
  }

  @Test
  void encode_emptyProps_returnsEmptyString() {
    assertEquals("", new Props().encode());
  }

  @Test
  void decode_nullOrBlank_returnsEmptyProps() {
    Props p1 = Props.decode(null);
    Props p2 = Props.decode("  ");
    assertFalse(p1.has("anything"));
    assertFalse(p2.has("anything"));
  }

  @Test
  void encode_decode_escapesNewlines() {
    Props p = new Props();
    p.set("msg", "line1\nline2");
    String encoded = p.encode();
    Props decoded = Props.decode(encoded);
    assertEquals("line1\nline2", decoded.get("msg"));
  }

  @Test
  void encode_decode_escapesEquals() {
    Props p = new Props();
    p.set("formula", "a=b+c");
    Props decoded = Props.decode(p.encode());
    assertEquals("a=b+c", decoded.get("formula"));
  }

  @Test
  void encode_decode_escapesBackslash() {
    Props p = new Props();
    p.set("path", "C:\\Users\\test");
    Props decoded = Props.decode(p.encode());
    assertEquals("C:\\Users\\test", decoded.get("path"));
  }

  @Test
  void decode_lineWithNoSeparator_isSkipped() {
    Props p = Props.decode("no-equals-sign\nkey=val");
    assertNull(p.get("no-equals-sign"));
    assertEquals("val", p.get("key"));
  }

  @Test
  void fluent_set_returnsThis() {
    Props p = new Props();
    assertSame(p, p.set("k", "v"));
    assertSame(p, p.set("k", 1));
    assertSame(p, p.set("k", 1L));
    assertSame(p, p.set("k", true));
  }
}
