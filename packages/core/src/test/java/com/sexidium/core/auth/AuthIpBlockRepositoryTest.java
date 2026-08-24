package com.sexidium.core.auth;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The durable half of Deny, including the part that makes it per-account rather than per-address. */
class AuthIpBlockRepositoryTest {

  private Database db;
  private AuthIpBlockRepository repo;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-block-test");
    db = new Database(dir.resolve("blocks.db").toFile());
    repo = new AuthIpBlockRepository(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("a block applies to one identity on one network, and to nobody else on it")
  void blockIsPerIdentityAndNetwork() throws Exception {
    repo.block("id-1", "hash-a", "denied", 0L, 10_000L);

    assertTrue(repo.blocked("id-1", "hash-a", 1_000L));
    assertFalse(repo.blocked("id-2", "hash-a", 1_000L),
        "under CGNAT a per-address block would punish a whole neighbourhood");
    assertFalse(repo.blocked("id-1", "hash-b", 1_000L));
  }

  @Test
  @DisplayName("a block stops applying once it expires")
  void blocksExpire() throws Exception {
    repo.block("id-1", "hash-a", "denied", 0L, 10_000L);
    assertFalse(repo.blocked("id-1", "hash-a", 20_000L));
  }

  @Test
  @DisplayName("blocking the same pair again extends it rather than failing on the key")
  void blockIsAnUpsert() throws Exception {
    repo.block("id-1", "hash-a", "denied", 0L, 10_000L);
    repo.block("id-1", "hash-a", "denied again", 5_000L, 90_000L);

    assertTrue(repo.blocked("id-1", "hash-a", 50_000L));
  }

  @Test
  @DisplayName("the sweep removes only what has expired")
  void deleteExpired() throws Exception {
    repo.block("id-1", "hash-a", "denied", 0L, 10_000L);
    repo.block("id-1", "hash-b", "denied", 0L, 90_000L);

    assertEquals(1, repo.deleteExpired(50_000L));
    assertTrue(repo.blocked("id-1", "hash-b", 60_000L));
  }

  @Test
  @DisplayName("staff can clear every block on an account after a Deny that was not the player's fault")
  void clear() throws Exception {
    repo.block("id-1", "hash-a", "denied", 0L, 90_000L);
    repo.block("id-1", "hash-b", "denied", 0L, 90_000L);

    assertEquals(2, repo.clear("id-1"));
    assertEquals(0, repo.clear(null));
    assertFalse(repo.blocked("id-1", "hash-a", 1_000L));
  }

  @Test
  @DisplayName("null arguments answer 'not blocked' and write nothing, rather than throwing")
  void nullsAreInert() throws Exception {
    repo.block(null, "hash-a", "r", 0L, 1L);
    repo.block("id-1", null, "r", 0L, 1L);
    assertFalse(repo.blocked(null, "hash-a", 1L));
    assertFalse(repo.blocked("id-1", null, 1L));
  }
}
