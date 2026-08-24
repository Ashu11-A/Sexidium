package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthCodeResultTest {

  @Test
  void createdResult_hasFields() {
    AuthCodeResult r = new AuthCodeResult(AuthCodeResult.Status.CREATED, "ABC123", 9999L, null);
    assertEquals(AuthCodeResult.Status.CREATED, r.status());
    assertEquals("ABC123", r.code());
    assertEquals(9999L, r.expiresAt());
    assertNull(r.discordUserId());
  }

  @Test
  void alreadyLinkedResult_hasDiscordId() {
    AuthCodeResult r = new AuthCodeResult(AuthCodeResult.Status.ALREADY_LINKED, null, 0, "discord-42");
    assertEquals(AuthCodeResult.Status.ALREADY_LINKED, r.status());
    assertEquals("discord-42", r.discordUserId());
    assertNull(r.code());
  }

  @Test
  void disabledResult_hasNoCode() {
    AuthCodeResult r = new AuthCodeResult(AuthCodeResult.Status.DISABLED, null, 0, null);
    assertEquals(AuthCodeResult.Status.DISABLED, r.status());
    assertNull(r.code());
  }

  @Test
  void statusEnum_hasThreeValues() {
    assertEquals(3, AuthCodeResult.Status.values().length);
  }

  @Test
  void equality_byValue() {
    AuthCodeResult a = new AuthCodeResult(AuthCodeResult.Status.CREATED, "X", 1L, null);
    AuthCodeResult b = new AuthCodeResult(AuthCodeResult.Status.CREATED, "X", 1L, null);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }
}
