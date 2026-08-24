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

class AuthRepositoryTest {
  private Database db;
  private AuthRepository repo;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("sexidium-repo-test");
    db = new Database(dir.resolve("repo.db").toFile());
    repo = new AuthRepository(db);
  }

  @AfterEach
  void tearDown() throws Exception {
    db.close();
  }

  @Test
  void linkedDiscordId_whenNone_returnsNull() throws Exception {
    assertNull(repo.linkedDiscordId("no-such-uuid"));
  }

  @Test
  void linkedDiscordId_afterLink_returnsId() throws Exception {
    insertLink("uuid-1", "Steve", "discord-1");
    assertEquals("discord-1", repo.linkedDiscordId("uuid-1"));
  }

  @Test
  void createPendingCode_insertsPlayerAndCode() throws Exception {
    long now = System.currentTimeMillis();
    repo.createPendingCode("hash-abc", "uuid-1", "Steve", now, now + 60_000L);

    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT COUNT(*) FROM auth_codes WHERE code_hash = ?")) {
      ps.setString(1, "hash-abc");
      var rs = ps.executeQuery();
      rs.next();
      assertEquals(1, rs.getInt(1));
    }
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT name FROM players WHERE uuid = ?")) {
      ps.setString(1, "uuid-1");
      var rs = ps.executeQuery();
      assertTrue(rs.next());
      assertEquals("Steve", rs.getString("name"));
    }
  }

  @Test
  void createPendingCode_deletesOldUnconsumedCodes() throws Exception {
    long now = System.currentTimeMillis();
    repo.createPendingCode("hash-old", "uuid-1", "Steve", now - 1000, now + 10_000L);
    repo.createPendingCode("hash-new", "uuid-1", "Steve", now, now + 60_000L);

    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT COUNT(*) FROM auth_codes WHERE minecraft_uuid = ? AND consumed_at IS NULL")) {
      ps.setString(1, "uuid-1");
      var rs = ps.executeQuery();
      rs.next();
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void linkByMinecraftName_whenNotLinked_returnsNull() throws Exception {
    assertNull(repo.linkByMinecraftName("Unknown"));
  }

  @Test
  void linkByMinecraftName_whenLinked_returnsLink() throws Exception {
    insertLink("uuid-1", "Steve", "discord-42");
    AuthLink link = repo.linkByMinecraftName("Steve");
    assertNotNull(link);
    assertEquals("discord-42", link.discordUserId());
    assertEquals("uuid-1", link.minecraftUuid());
  }

  @Test
  void linkByMinecraftName_isCaseInsensitive() throws Exception {
    insertLink("uuid-1", "Steve", "discord-42");
    assertNotNull(repo.linkByMinecraftName("steve"));
    assertNotNull(repo.linkByMinecraftName("STEVE"));
  }

  @Test
  void unlinkByMinecraftName_whenNotLinked_returnsNull() throws Exception {
    assertNull(repo.unlinkByMinecraftName("Nobody"));
  }

  @Test
  void unlinkByMinecraftName_whenLinked_removesLink() throws Exception {
    insertLink("uuid-1", "Alex", "discord-99");
    AuthLink link = repo.unlinkByMinecraftName("Alex");
    assertNotNull(link);
    assertEquals("discord-99", link.discordUserId());
    assertNull(repo.linkedDiscordId("uuid-1"));
  }

  @Test
  void createPendingCode_remembersTheNetworkThatAskedForIt() throws Exception {
    long now = System.currentTimeMillis();
    repo.createPendingCode("hash-abc", "uuid-1", "Steve", "ip-hash-1", now, now + 60_000L);

    AuthRepository.CodeBinding binding = repo.bindingOf("hash-abc");
    assertNotNull(binding);
    assertEquals("uuid-1", binding.minecraftUuid());
    assertEquals("Steve", binding.minecraftName());
    // This is what lets consuming the code mint that network's first session on the spot.
    assertEquals("ip-hash-1", binding.ipHash());
  }

  @Test
  void createPendingCode_withoutANetwork_bindsNothing() throws Exception {
    long now = System.currentTimeMillis();
    repo.createPendingCode("hash-abc", "uuid-1", "Steve", now, now + 60_000L);

    assertNull(repo.bindingOf("hash-abc").ipHash());
    assertNull(repo.bindingOf("no-such-code"));
  }

  @Test
  void unlinkByMinecraftName_alsoRevokesEverySessionAndRequest() throws Exception {
    insertLink("uuid-1", "Alex", "discord-99");
    insertSession("uuid-1", "hash-a");
    insertRequest("req-1", "uuid-1");

    repo.unlinkByMinecraftName("Alex");

    // A session is "this Discord user approved this network"; leaving one alive after the approval
    // is withdrawn would let that network keep walking in on an account nobody owns.
    assertEquals(0, countRows("SELECT COUNT(*) FROM auth_sessions WHERE identity_id = 'uuid-1'"));
    assertEquals(0, countRows("SELECT COUNT(*) FROM auth_requests WHERE identity_id = 'uuid-1'"));
  }

  private int countRows(String sql) throws Exception {
    try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
      var rs = ps.executeQuery();
      rs.next();
      return rs.getInt(1);
    }
  }

  private void insertSession(String identityId, String ipHash) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO auth_sessions(identity_id, ip_hash, session_id, name_lower, device,"
              + " created_at, last_seen_at, expires_at, absolute_expires_at)"
              + " VALUES(?,?,?,'alex','java',0,0,0,0)")) {
        ps.setString(1, identityId);
        ps.setString(2, ipHash);
        ps.setString(3, "session-" + ipHash);
        ps.executeUpdate();
      }
    }
  }

  private void insertRequest(String requestId, String identityId) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO auth_requests(request_id, identity_id, name_lower, display_name,"
              + " discord_user_id, ip_hash, kind, state, hold, claim_expires_at, attempts,"
              + " created_at, expires_at) VALUES(?,?,'alex','Alex','discord-99','hash-a',"
              + " 'session','pending',0,0,0,0,0)")) {
        ps.setString(1, requestId);
        ps.setString(2, identityId);
        ps.executeUpdate();
      }
    }
  }

  private void insertLink(String uuid, String name, String discordId) throws Exception {
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
