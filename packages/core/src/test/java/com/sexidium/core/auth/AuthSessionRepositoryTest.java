package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthSessionRepository.SessionRow;
import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The session store, and in particular the two bounds that make "remember this network" safe: the
 * absolute cap a sliding renewal cannot cross, and the per-identity ceiling.
 */
class AuthSessionRepositoryTest {

  private static final long HOUR = 3_600_000L;

  private Database db;
  private AuthSessionRepository repo;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-session-test");
    db = new Database(dir.resolve("sessions.db").toFile());
    repo = new AuthSessionRepository(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("a session round-trips through the dialect's upsert")
  void upsertAndFind() throws Exception {
    repo.upsert(row("id-1", "hash-a", 1_000L, 1_000L + 18 * HOUR, 1_000L + 72 * HOUR));

    SessionRow found = repo.find("id-1", "hash-a");
    assertNotNull(found);
    assertEquals("steve", found.nameLower());
    assertEquals("187.61.*.*", found.ipPrefix());
    assertEquals("java", found.device());
    assertTrue(found.live(2_000L));
  }

  @Test
  @DisplayName("a second upsert on the same (identity, network) refreshes rather than duplicating")
  void upsertIsIdempotentPerNetwork() throws Exception {
    repo.upsert(row("id-1", "hash-a", 1_000L, 1_000L + HOUR, 1_000L + 72 * HOUR));
    repo.upsert(row("id-1", "hash-a", 1_000L, 1_000L + 5 * HOUR, 1_000L + 72 * HOUR));

    assertEquals(1, repo.byIdentity("id-1").size());
    assertEquals(1_000L + 5 * HOUR, repo.find("id-1", "hash-a").expiresAt());
  }

  @Test
  @DisplayName("sliding renewal moves the TTL forward while the hard cap is still ahead")
  void renewSlidesTheTtl() throws Exception {
    repo.upsert(row("id-1", "hash-a", 0L, 10 * HOUR, 72 * HOUR));

    repo.renew("id-1", "hash-a", 5 * HOUR, 5 * HOUR + 18 * HOUR);

    assertEquals(23 * HOUR, repo.find("id-1", "hash-a").expiresAt());
    assertEquals(5 * HOUR, repo.find("id-1", "hash-a").lastSeenAt());
  }

  @Test
  @DisplayName("sliding renewal is CLAMPED by the absolute cap: after 72h you approve again, period")
  void renewCannotCrossTheAbsoluteCap() throws Exception {
    repo.upsert(row("id-1", "hash-a", 0L, 10 * HOUR, 72 * HOUR));

    repo.renew("id-1", "hash-a", 70 * HOUR, 70 * HOUR + 18 * HOUR);

    assertEquals(72 * HOUR, repo.find("id-1", "hash-a").expiresAt(),
        "an unclamped renewal would make the hard cap a promise the code does not keep");
  }

  @Test
  @DisplayName("a row past either TTL is not live, whatever the other one says")
  void livenessHonoursBothTtls() throws Exception {
    SessionRow expired = row("id-1", "hash-a", 0L, HOUR, 72 * HOUR);
    assertFalse(expired.live(2 * HOUR));

    SessionRow capped = row("id-1", "hash-b", 0L, 100 * HOUR, 72 * HOUR);
    assertFalse(capped.live(73 * HOUR));

    SessionRow revoked = new SessionRow("id-1", "hash-c", "s", "steve", null, "java", null, null,
        0L, 0L, 100 * HOUR, 100 * HOUR, 5L);
    assertFalse(revoked.live(1L));
  }

  @Test
  @DisplayName("the sweep deletes expired, capped and revoked rows and leaves live ones alone")
  void deleteExpired() throws Exception {
    repo.upsert(row("id-1", "hash-a", 0L, HOUR, 72 * HOUR));
    repo.upsert(row("id-1", "hash-b", 0L, 50 * HOUR, 72 * HOUR));

    assertEquals(1, repo.deleteExpired(2 * HOUR));
    assertNull(repo.find("id-1", "hash-a"));
    assertNotNull(repo.find("id-1", "hash-b"));
  }

  @Test
  @DisplayName("revokeAll takes every network of one identity — what Deny and unlink both do")
  void revokeAll() throws Exception {
    repo.upsert(row("id-1", "hash-a", 0L, 50 * HOUR, 72 * HOUR));
    repo.upsert(row("id-1", "hash-b", 0L, 50 * HOUR, 72 * HOUR));
    repo.upsert(row("id-2", "hash-c", 0L, 50 * HOUR, 72 * HOUR));

    assertEquals(2, repo.revokeAll("id-1"));
    assertEquals(0, repo.revokeAll(null));
    assertTrue(repo.byIdentity("id-1").isEmpty());
    assertEquals(1, repo.byIdentity("id-2").size());
  }

  @Test
  @DisplayName("revokeOne addresses a single session by its own id")
  void revokeOne() throws Exception {
    repo.upsert(row("id-1", "hash-a", 0L, 50 * HOUR, 72 * HOUR));

    assertEquals(0, repo.revokeOne("  "));
    assertEquals(0, repo.revokeOne("no-such-session"));
    assertEquals(1, repo.revokeOne("session-hash-a"));
    assertTrue(repo.byIdentity("id-1").isEmpty());
  }

  @Test
  @DisplayName("trimTo evicts the LEAST recently used, so the network you actually use survives")
  void trimEvictsByLastSeen() throws Exception {
    repo.upsert(seen("id-1", "hash-old", 1_000L));
    repo.upsert(seen("id-1", "hash-mid", 5_000L));
    repo.upsert(seen("id-1", "hash-new", 9_000L));

    assertEquals(1, repo.trimTo("id-1", 2));

    List<SessionRow> left = repo.byIdentity("id-1");
    assertEquals(2, left.size());
    assertEquals("hash-new", left.get(0).ipHash());
    assertEquals("hash-mid", left.get(1).ipHash());
  }

  @Test
  @DisplayName("trimming below the ceiling, or with a nonsense ceiling, changes nothing")
  void trimIsANoOpWhenUnderTheCeiling() throws Exception {
    repo.upsert(seen("id-1", "hash-a", 1_000L));
    assertEquals(0, repo.trimTo("id-1", 5));
    assertEquals(0, repo.trimTo("id-1", 0));
    assertEquals(0, repo.trimTo(null, 5));
  }

  @Test
  @DisplayName("a missing session, and a lookup with nulls, answer null instead of throwing")
  void missingLookups() throws Exception {
    assertNull(repo.find("nobody", "nothing"));
    assertNull(repo.find(null, "hash"));
    assertNull(repo.find("id", null));
    assertTrue(repo.byIdentity(null).isEmpty());
  }

  @Test
  @DisplayName("Discord-facing reads go through discord_accounts, never through a caller's claim")
  void byDiscordUserJoinsTheLink() throws Exception {
    link("id-1", "steve", "discord-1");
    repo.upsert(row("id-1", "hash-a", 0L, 50 * HOUR, 72 * HOUR));
    repo.upsert(row("id-9", "hash-z", 0L, 50 * HOUR, 72 * HOUR));

    List<SessionRow> mine = repo.byDiscordUser("discord-1", HOUR);
    assertEquals(1, mine.size());
    assertEquals("hash-a", mine.get(0).ipHash());

    assertTrue(repo.byDiscordUser("discord-2", HOUR).isEmpty());
    assertTrue(repo.byDiscordUser(null, HOUR).isEmpty());
    assertTrue(repo.byDiscordUser("  ", HOUR).isEmpty());
  }

  @Test
  @DisplayName("an expired session is not listed to its owner")
  void byDiscordUserSkipsExpired() throws Exception {
    link("id-1", "steve", "discord-1");
    repo.upsert(row("id-1", "hash-a", 0L, HOUR, 72 * HOUR));

    assertTrue(repo.byDiscordUser("discord-1", 2 * HOUR).isEmpty());
  }

  @Test
  @DisplayName("ownership is answered from the row, so somebody else's session id is refused")
  void ownedBy() throws Exception {
    link("id-1", "steve", "discord-1");
    repo.upsert(row("id-1", "hash-a", 0L, 50 * HOUR, 72 * HOUR));

    assertTrue(repo.ownedBy("session-hash-a", "discord-1"));
    assertFalse(repo.ownedBy("session-hash-a", "discord-2"));
    assertFalse(repo.ownedBy(null, "discord-1"));
    assertFalse(repo.ownedBy("session-hash-a", " "));
  }

  @Test
  @DisplayName("a session view carries no hash and no raw address")
  void viewIsSafeToShow() throws Exception {
    SessionRow session = row("id-1", "hash-a", 1L, 2L, 3L);
    assertEquals("session-hash-a", session.view().sessionId());
    assertEquals("187.61.*.*", session.view().ipPrefix());
    assertEquals("steve", session.view().minecraftName());
  }

  @Test
  @DisplayName("a revoked-at timestamp round-trips as null when it is absent")
  void revokedAtRoundTrips() throws Exception {
    repo.upsert(new SessionRow("id-1", "hash-a", "s1", "steve", null, "java", "node", "discord-1",
        1L, 2L, 3L, 4L, 99L));
    assertEquals(99L, repo.find("id-1", "hash-a").revokedAt());

    repo.upsert(row("id-2", "hash-b", 1L, 2L, 3L));
    assertNull(repo.find("id-2", "hash-b").revokedAt());
  }

  private static SessionRow row(String identityId, String ipHash, long createdAt, long expiresAt,
      long absoluteExpiresAt) {
    return new SessionRow(identityId, ipHash, "session-" + ipHash, "steve", "187.61.*.*", "java",
        "node-1", "discord-1", createdAt, createdAt, expiresAt, absoluteExpiresAt, null);
  }

  private static SessionRow seen(String identityId, String ipHash, long lastSeenAt) {
    return new SessionRow(identityId, ipHash, "session-" + ipHash, "steve", "187.61.*.*", "java",
        "node-1", "discord-1", 0L, lastSeenAt, 100 * HOUR, 100 * HOUR, null);
  }

  private void link(String identityId, String name, String discordUserId) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO player_identities(name_lower, identity_id, display_name, account_type,"
              + " premium_state, premium_checked_at, first_seen_at, last_seen_at, updated_at)"
              + " VALUES(?,?,?,'cracked','unknown',0,0,0,0)")) {
        ps.setString(1, name);
        ps.setString(2, identityId);
        ps.setString(3, name);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO discord_accounts(minecraft_uuid, discord_user_id, minecraft_name,"
              + " created_at, updated_at) VALUES(?,?,?,0,0)")) {
        ps.setString(1, identityId);
        ps.setString(2, discordUserId);
        ps.setString(3, name);
        ps.executeUpdate();
      }
    }
  }
}
