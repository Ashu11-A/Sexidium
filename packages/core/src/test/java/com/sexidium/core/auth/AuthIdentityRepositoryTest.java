package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthIdentity.PremiumState;
import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The identity anchor, and above all its ADOPTION rule — the one behaviour that decides whether
 * turning this feature on quietly orphans every existing player's points, links and worlds.
 */
class AuthIdentityRepositoryTest {

  private Database db;
  private AuthIdentityRepository repo;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-identity-test");
    db = new Database(dir.resolve("identity.db").toFile());
    repo = new AuthIdentityRepository(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("a name nobody has played gets the offline uuid of its LOWERCASE spelling")
  void firstSightDerivesTheOfflineUuid() throws Exception {
    AuthIdentity identity = repo.resolveOrCreate("Steve", 1_000L);
    assertNotNull(identity);
    assertEquals("steve", identity.nameLower());
    assertEquals(AuthIdentity.offlineUuidOf("steve"), identity.identityId());
    assertEquals("Steve", identity.displayName());
    assertEquals(PremiumState.UNKNOWN, identity.premiumState());
  }

  @Test
  @DisplayName("an existing players row is ADOPTED, so no data moves and no link breaks")
  void adoptsAnExistingPlayerUuid() throws Exception {
    insertPlayer("legacy-uuid-1", "Steve", 500L);

    AuthIdentity identity = repo.resolveOrCreate("Steve", 1_000L);

    assertEquals("legacy-uuid-1", identity.identityId(),
        "adoption IS the migration: rewriting uuids across ten tables is the alternative");
    assertNotEquals(AuthIdentity.offlineUuidOf("steve"), identity.identityId());
  }

  @Test
  @DisplayName("two rows for one lowercase name resolve to the most recently updated")
  void adoptionPicksTheNewestRow() throws Exception {
    insertPlayer("uuid-old", "Steve", 100L);
    insertPlayer("uuid-new", "steve", 900L);

    assertEquals("uuid-new", repo.resolveOrCreate("STEVE", 1_000L).identityId());
  }

  @Test
  @DisplayName("Foo and foo are one account, which they were not before this table existed")
  void resolutionIsCaseInsensitive() throws Exception {
    AuthIdentity upper = repo.resolveOrCreate("Foo", 1_000L);
    AuthIdentity lower = repo.resolveOrCreate("foo", 2_000L);

    assertEquals(upper.identityId(), lower.identityId());
    assertEquals(upper.nameLower(), lower.nameLower());
  }

  @Test
  @DisplayName("the identity id is decided once and never recomputed, even as the casing changes")
  void identityIdIsStable() throws Exception {
    String first = repo.resolveOrCreate("Steve", 1_000L).identityId();
    insertPlayer("some-other-uuid", "steve", 5_000L);

    assertEquals(first, repo.resolveOrCreate("sTeVe", 6_000L).identityId());
  }

  @Test
  @DisplayName("the display name follows the spelling the player is actually using")
  void touchRefreshesTheDisplayName() throws Exception {
    repo.resolveOrCreate("Steve", 1_000L);
    repo.resolveOrCreate("STEVE", 2_000L);

    assertEquals("STEVE", repo.find("steve").displayName());
  }

  @Test
  @DisplayName("a premium verdict is recorded durably, which is what survives a Mojang outage")
  void recordsPremiumState() throws Exception {
    repo.resolveOrCreate("Notch", 1_000L);
    repo.recordPremium("notch", "069a79f4", PremiumState.PREMIUM, 4_000L);

    AuthIdentity identity = repo.find("notch");
    assertEquals(PremiumState.PREMIUM, identity.premiumState());
    assertEquals("069a79f4", identity.premiumUuid());
    assertEquals(4_000L, identity.premiumCheckedAt());
    assertEquals(AuthIdentity.TYPE_PREMIUM, identity.accountType());
  }

  @Test
  @DisplayName("a later cracked verdict never erases the Mojang uuid we already learned")
  void premiumUuidIsNotErasedByANullUpdate() throws Exception {
    repo.resolveOrCreate("Notch", 1_000L);
    repo.recordPremium("notch", "069a79f4", PremiumState.PREMIUM, 4_000L);
    repo.recordPremium("notch", null, PremiumState.CRACKED, 5_000L);

    assertEquals("069a79f4", repo.find("notch").premiumUuid());
    assertEquals(PremiumState.CRACKED, repo.find("notch").premiumState());
  }

  @Test
  @DisplayName("a Bedrock XUID is recorded and flips the account type")
  void recordsBedrock() throws Exception {
    repo.resolveOrCreate(".Phone", 1_000L);
    repo.recordBedrock(".phone", "xuid-42", 2_000L);

    AuthIdentity identity = repo.find(".phone");
    assertEquals("xuid-42", identity.bedrockXuid());
    assertEquals(AuthIdentity.TYPE_BEDROCK, identity.accountType());
  }

  @Test
  @DisplayName("blank and null names resolve to nothing rather than to a shared empty identity")
  void blankNamesAreRefused() throws Exception {
    assertNull(repo.resolveOrCreate(null, 1_000L));
    assertNull(repo.resolveOrCreate("   ", 1_000L));
    assertNull(repo.find(""));
    assertNull(repo.find(null));
  }

  @Test
  @DisplayName("writes addressed to a name that does not exist are a no-op, not a failure")
  void writesToUnknownNamesAreNoOps() throws Exception {
    repo.touch("ghost", "Ghost", 1L);
    repo.touch(null, "Ghost", 1L);
    repo.recordPremium("", "x", PremiumState.PREMIUM, 1L);
    repo.recordBedrock(null, "x", 1L);
    assertNull(repo.find("ghost"));
  }

  @Test
  @DisplayName("the canonical uuid parses, so the proxy can actually pin a profile to it")
  void identityUuidParses() throws Exception {
    AuthIdentity derived = repo.resolveOrCreate("Steve", 1_000L);
    assertNotNull(derived.identityUuid());

    insertPlayer("not-a-uuid", "Alex", 1L);
    assertNull(repo.resolveOrCreate("Alex", 2L).identityUuid());
  }

  private void insertPlayer(String uuid, String name, long updatedAt) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO players(uuid, name, points, level, wins, kills, games, updated_at)"
              + " VALUES(?,?,0,0,0,0,0,?)")) {
        ps.setString(1, uuid);
        ps.setString(2, name);
        ps.setLong(3, updatedAt);
        ps.executeUpdate();
      }
    }
  }
}
