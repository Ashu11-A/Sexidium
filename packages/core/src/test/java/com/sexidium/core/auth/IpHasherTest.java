package com.sexidium.core.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a session binds to, what is stored, and what a human is shown — the three questions
 * {@link IpHasher} answers, each of which is a different wrong answer if it drifts.
 */
class IpHasherTest {

  @Test
  @DisplayName("IPv4 binds the whole address, so a neighbour on another address never matches")
  void v4BindsFullAddress() {
    assertEquals("187.61.4.9", IpHasher.normalize("187.61.4.9", 64));
    assertNotEquals(
        IpHasher.normalize("187.61.4.9", 64),
        IpHasher.normalize("187.61.4.10", 64));
  }

  @Test
  @DisplayName("IPv6 binds the /64 prefix, because the interface identifier rotates all day")
  void v6BindsPrefix() {
    String morning = IpHasher.normalize("2804:14d:1:2:aaaa:bbbb:cccc:dddd", 64);
    String evening = IpHasher.normalize("2804:14d:1:2:1111:2222:3333:4444", 64);
    assertEquals(morning, evening,
        "binding the full v6 address would demand a fresh approval every few hours for no gain");
    assertTrue(morning.endsWith("/64"));
  }

  @Test
  @DisplayName("a narrower configured prefix genuinely narrows the binding")
  void v6HonoursTheConfiguredPrefix() {
    String wide = IpHasher.normalize("2804:14d:1:2::1", 48);
    String narrow = IpHasher.normalize("2804:14d:1:2::1", 64);
    assertNotEquals(wide, narrow);
    assertTrue(wide.endsWith("/48"));
  }

  @Test
  @DisplayName("an IPv4-mapped v6 address is the same binding as the plain v4 one")
  void v4MappedCollapsesToV4() {
    assertEquals(
        IpHasher.normalize("187.61.4.9", 64),
        IpHasher.normalize("::ffff:187.61.4.9", 64));
  }

  @Test
  @DisplayName("a zone id is a local interface fact and never part of the binding")
  void zoneIdIsStripped() {
    assertEquals(IpHasher.normalize("fe80::1", 64), IpHasher.normalize("fe80::1%eth0", 64));
  }

  @Test
  @DisplayName("unparseable and empty input still key something, and never match a real address")
  void malformedInputIsKeyedNotRejected() {
    assertEquals("", IpHasher.normalize(null, 64));
    assertEquals("", IpHasher.normalize("   ", 64));
    assertEquals("not an ip", IpHasher.normalize("Not An IP", 64));
    assertNotEquals(IpHasher.normalize("not an ip", 64), IpHasher.normalize("187.61.4.9", 64));
  }

  @Test
  @DisplayName("loopback normalises without throwing")
  void loopbackNormalises() {
    assertEquals("127.0.0.1", IpHasher.normalize("127.0.0.1", 64));
    assertTrue(IpHasher.normalize("::1", 64).endsWith("/64"));
  }

  @Test
  @DisplayName("a nonsense prefix falls back to /64 rather than binding nothing or everything")
  void prefixIsClamped() {
    String sane = IpHasher.normalize("2804:14d:1:2::1", 64);
    assertEquals(sane, IpHasher.normalize("2804:14d:1:2::1", 0));
    assertEquals(sane, IpHasher.normalize("2804:14d:1:2::1", -8));
    assertEquals(sane, IpHasher.normalize("2804:14d:1:2::1", 999));
  }

  @Test
  @DisplayName("redaction strips a zone id too, so the display form matches the binding")
  void redactionStripsTheZoneId() {
    assertEquals(IpHasher.redact("fe80::1"), IpHasher.redact("fe80::1%eth0"));
  }

  @Test
  @DisplayName("the pepper changes the hash, which is the whole point of having one")
  void pepperChangesTheHash() {
    String normalized = IpHasher.normalize("187.61.4.9", 64);
    assertNotEquals(IpHasher.hash(normalized, "pepper-a"), IpHasher.hash(normalized, "pepper-b"));
    assertEquals(IpHasher.hash(normalized, "pepper-a"), IpHasher.hash(normalized, "pepper-a"));
  }

  @Test
  @DisplayName("the hash is 64 hex characters and never contains the address")
  void hashIsOpaque() {
    String hashed = IpHasher.hash(IpHasher.normalize("187.61.4.9", 64), "salt");
    assertEquals(64, hashed.length());
    assertTrue(hashed.matches("[0-9a-f]{64}"));
    assertFalse(hashed.contains("187"));
  }

  @Test
  @DisplayName("a null normalised address still hashes rather than throwing on a login")
  void nullHashesToSomething() {
    assertEquals(64, IpHasher.hash(null, null).length());
  }

  @Test
  @DisplayName("redaction keeps enough to recognise a network and not enough to dial it")
  void redactionShape() {
    assertEquals("187.61.*.*", IpHasher.redact("187.61.4.9"));
    assertTrue(IpHasher.redact("2804:14d:1:2::1").endsWith("/48"));
    assertEquals("", IpHasher.redact(""));
    assertEquals("unknown", IpHasher.redact("Not An IP"));
    assertEquals("unknown", IpHasher.redact(null) == null ? "unknown" : IpHasher.redact("nope"));
  }
}
