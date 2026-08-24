package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
  private Database db;
  private AuthService service;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("sexidium-auth-test");
    db = new Database(dir.resolve("auth.db").toFile());
    service = new AuthService(db, () -> true);
  }

  @AfterEach
  void tearDown() throws Exception {
    db.close();
  }

  @Test
  void createCode_whenEnabled_returnsCreatedResult() throws Exception {
    AuthCodeResult result = service.createCode("uuid-1", "Steve", 6, 60_000L, "23456789");
    assertEquals(AuthCodeResult.Status.CREATED, result.status());
    assertNotNull(result.code());
    assertEquals(6, result.code().length());
    assertTrue(result.expiresAt() > System.currentTimeMillis());
  }

  @Test
  void createCode_whenDisabled_returnsDisabledResult() throws Exception {
    AuthService disabled = new AuthService(db, () -> false);
    AuthCodeResult result = disabled.createCode("uuid-1", "Steve", 6, 60_000L, "23456789");
    assertEquals(AuthCodeResult.Status.DISABLED, result.status());
    assertNull(result.code());
  }

  @Test
  void createCode_whenAlreadyLinked_returnsAlreadyLinked() throws Exception {
    linkPlayer("uuid-linked", "Alex", "discord-999");
    AuthCodeResult result = service.createCode("uuid-linked", "Alex", 6, 60_000L, "23456789");
    assertEquals(AuthCodeResult.Status.ALREADY_LINKED, result.status());
    assertEquals("discord-999", result.discordUserId());
  }

  @Test
  void createCode_codesOnlyContainAllowedChars() throws Exception {
    for (int i = 0; i < 20; i++) {
      AuthCodeResult result = service.createCode("uuid-" + i, "Player" + i, 6, 60_000L, "AB");
      for (char c : result.code().toCharArray()) {
        assertTrue(c == 'A' || c == 'B', "Unexpected char: " + c);
      }
    }
  }

  @Test
  void createCode_withInvalidCharacterSet_usesDefault() throws Exception {
    AuthCodeResult result = service.createCode("uuid-1", "Steve", 6, 60_000L, "!@#");
    assertEquals(AuthCodeResult.Status.CREATED, result.status());
    assertNotNull(result.code());
    assertEquals(6, result.code().length());
  }

  @Test
  void createCode_lengthIsClampedMin4() throws Exception {
    AuthCodeResult result = service.createCode("uuid-1", "Steve", 1, 60_000L, "23456789");
    assertEquals(4, result.code().length());
  }

  @Test
  void createCode_lengthIsClampedMax16() throws Exception {
    AuthCodeResult result = service.createCode("uuid-1", "Steve", 100, 60_000L, "23456789");
    assertEquals(16, result.code().length());
  }

  @Test
  void linkedDiscordId_whenDisabled_returnsNull() throws Exception {
    AuthService disabled = new AuthService(db, () -> false);
    assertNull(disabled.linkedDiscordId("any-uuid"));
  }

  @Test
  void linkedDiscordId_whenNoLink_returnsNull() throws Exception {
    assertNull(service.linkedDiscordId("no-such-uuid"));
  }

  @Test
  void linkedDiscordId_whenLinked_returnsId() throws Exception {
    linkPlayer("uuid-1", "Steve", "discord-123");
    assertEquals("discord-123", service.linkedDiscordId("uuid-1"));
  }

  @Test
  void unlinkByMinecraftName_whenNotLinked_returnsNull() throws Exception {
    assertNull(service.unlinkByMinecraftName("Unknown"));
  }

  @Test
  void unlinkByMinecraftName_whenLinked_returnsLink() throws Exception {
    linkPlayer("uuid-1", "Steve", "discord-123");
    AuthLink link = service.unlinkByMinecraftName("Steve");
    assertNotNull(link);
    assertEquals("discord-123", link.discordUserId());
    assertEquals("uuid-1", link.minecraftUuid());
    assertEquals("Steve", link.minecraftName());
    assertNull(service.linkedDiscordId("uuid-1"));
  }

  @Test
  void hashCode_isSha256Hex() {
    String hash = AuthService.hashCode("ABC");
    assertEquals(64, hash.length());
    assertTrue(hash.matches("[0-9a-f]+"));
  }

  @Test
  void hashCode_normalizesBeforeHashing() {
    assertEquals(AuthService.hashCode("ABC"), AuthService.hashCode("A B C"));
    assertEquals(AuthService.hashCode("ABC"), AuthService.hashCode("A-B-C"));
    assertEquals(AuthService.hashCode("ABC"), AuthService.hashCode("abc"));
  }

  private void linkPlayer(String uuid, String name, String discordId) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO players(uuid, name, discord_user_id, points, level, wins, kills, games, updated_at) VALUES(?,?,?,0,0,0,0,0,0)")) {
        ps.setString(1, uuid);
        ps.setString(2, name);
        ps.setString(3, discordId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO discord_accounts(discord_user_id, minecraft_uuid, minecraft_name, created_at, updated_at) VALUES(?,?,?,0,0)")) {
        ps.setString(1, discordId);
        ps.setString(2, uuid);
        ps.setString(3, name);
        ps.executeUpdate();
      }
    }
  }
}
