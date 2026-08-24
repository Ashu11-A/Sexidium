package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthRequestRepository.RequestRow;
import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The approval queue. Two properties carry the whole design: the claim lease (a courier that dies
 * must free the row rather than swallow it) and the conditional decide (a second button press must
 * change nothing).
 */
class AuthRequestRepositoryTest {

  private Database db;
  private AuthRequestRepository repo;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-request-test");
    db = new Database(dir.resolve("requests.db").toFile());
    repo = new AuthRequestRepository(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("a request round-trips, hold flag and detail included")
  void insertAndRead() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, true, 10_000L));

    RequestRow found = repo.byId("req-1");
    assertNotNull(found);
    assertEquals("steve", found.nameLower());
    assertEquals("Steve", found.displayName());
    assertEquals("discord-1", found.discordUserId());
    assertTrue(found.hold());
    assertEquals("detail", found.detail());
    assertTrue(found.live(1_000L));
    assertTrue(found.firstSeen());
  }

  @Test
  @DisplayName("a live pending request for the same network is found, so ten reconnects send one DM")
  void findLivePendingDeduplicates() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 10_000L));

    assertNotNull(repo.findLivePending("id-1", "hash-a", 1_000L));
    assertNull(repo.findLivePending("id-1", "hash-b", 1_000L), "a different network is a new ask");
    assertNull(repo.findLivePending("id-1", "hash-a", 20_000L), "an expired row is not reused");
    assertNull(repo.findLivePending(null, "hash-a", 1_000L));
  }

  @Test
  @DisplayName("a decided request is no longer 'live pending'")
  void decidedRowsAreNotReused() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_APPROVED, false, 10_000L));
    assertNull(repo.findLivePending("id-1", "hash-a", 1_000L));
  }

  @Test
  @DisplayName("claiming takes the row, and a second claimant sees nothing until the lease lapses")
  void claimIsExclusiveUntilTheLeaseLapses() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 100_000L));

    List<RequestRow> first = repo.claimPending("courier-a", 8, 1_000L, 5_000L);
    assertEquals(1, first.size());

    assertTrue(repo.claimPending("courier-b", 8, 2_000L, 5_000L).isEmpty(),
        "two couriers must not both DM the same player");

    // The first courier died before marking it notified; the lease lapses and the row comes back.
    assertEquals(1, repo.claimPending("courier-b", 8, 7_000L, 5_000L).size());
  }

  @Test
  @DisplayName("every claim counts as an attempt, which is what 'first seen' is derived from")
  void claimingCountsAttempts() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 100_000L));

    repo.claimPending("courier-a", 8, 1_000L, 1L);
    repo.claimPending("courier-a", 8, 3_000L, 1L);

    assertEquals(2, repo.byId("req-1").attempts());
    assertFalse(repo.byId("req-1").firstSeen());
  }

  @Test
  @DisplayName("an expired request is never claimed, so nobody is DM'd about a dead login")
  void expiredRowsAreNotClaimed() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 500L));
    assertTrue(repo.claimPending("courier-a", 8, 1_000L, 5_000L).isEmpty());
  }

  @Test
  @DisplayName("markNotified moves pending -> notified exactly once")
  void markNotifiedIsOneWay() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 100_000L));

    assertTrue(repo.markNotified("req-1", 2_000L));
    assertFalse(repo.markNotified("req-1", 3_000L));
    assertEquals(AuthRequestRepository.STATE_NOTIFIED, repo.byId("req-1").state());
    assertEquals(2_000L, repo.byId("req-1").notifiedAt());
  }

  @Test
  @DisplayName("deciding twice affects nothing the second time — the whole replay defence")
  void decideIsIdempotent() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_NOTIFIED, false, 100_000L));

    assertTrue(repo.decide("req-1", AuthRequestRepository.STATE_APPROVED, "discord-1", 2_000L));
    assertFalse(repo.decide("req-1", AuthRequestRepository.STATE_DENIED, "discord-1", 3_000L));

    RequestRow decided = repo.byId("req-1");
    assertEquals(AuthRequestRepository.STATE_APPROVED, decided.state());
    assertEquals("discord-1", decided.decidedBy());
    assertEquals(2_000L, decided.decidedAt());
  }

  @Test
  @DisplayName("consume marks a decided request as acted upon, and only a decided one")
  void consumeOnlyAppliesToDecidedRows() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 100_000L));
    assertFalse(repo.consume("req-1", 5_000L));

    repo.decide("req-1", AuthRequestRepository.STATE_APPROVED, "discord-1", 2_000L);
    assertTrue(repo.consume("req-1", 5_000L));
    assertEquals(AuthRequestRepository.STATE_CONSUMED, repo.byId("req-1").state());
  }

  @Test
  @DisplayName("the hold lookup finds the newest flagged row and ignores unflagged ones")
  void pendingHoldFor() throws Exception {
    repo.insert(row("req-old", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, true, 100_000L, 1_000L));
    repo.insert(row("req-new", "id-1", "hash-b", AuthRequestRepository.STATE_PENDING, true, 100_000L, 5_000L));
    repo.insert(row("req-flat", "id-2", "hash-c", AuthRequestRepository.STATE_PENDING, false, 100_000L));

    assertEquals("req-new", repo.pendingHoldFor("id-1", 6_000L).requestId());
    assertNull(repo.pendingHoldFor("id-2", 6_000L));
    assertNull(repo.pendingHoldFor(null, 6_000L));
    assertNull(repo.pendingHoldFor("id-1", 200_000L), "an expired hold releases itself");
  }

  @Test
  @DisplayName("a decided hold is still returned, because that IS the release cue")
  void pendingHoldSurvivesTheDecision() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_APPROVED, true, 100_000L));

    AuthRequestRepository.RequestRow hold = repo.pendingHoldFor("id-1", 1_000L);
    assertNotNull(hold);
    assertEquals(AuthRequestRepository.STATE_APPROVED, hold.state());
  }

  @Test
  @DisplayName("the sweep times out unanswered rows and eventually deletes the ancient ones")
  void expireStale() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 1_000L));
    repo.insert(row("req-2", "id-2", "hash-b", AuthRequestRepository.STATE_PENDING, false, 100_000L));

    assertEquals(1, repo.expireStale(5_000L, 86_400_000L));
    assertEquals(AuthRequestRepository.STATE_EXPIRED, repo.byId("req-1").state());
    assertEquals(AuthRequestRepository.STATE_PENDING, repo.byId("req-2").state());

    repo.expireStale(86_400_000L + 10_000L, 86_400_000L);
    assertNull(repo.byId("req-1"), "the audit row is kept for a retention window, then dropped");
  }

  @Test
  @DisplayName("unlinking drops every request of that identity")
  void deleteByIdentity() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 100_000L));
    repo.insert(row("req-2", "id-1", "hash-b", AuthRequestRepository.STATE_PENDING, false, 100_000L));
    repo.insert(row("req-3", "id-2", "hash-c", AuthRequestRepository.STATE_PENDING, false, 100_000L));

    assertEquals(2, repo.deleteByIdentity("id-1"));
    assertEquals(0, repo.deleteByIdentity(null));
    assertNotNull(repo.byId("req-3"));
  }

  @Test
  @DisplayName("counting by column backs the per-identity and per-network spam bounds")
  void countSince() throws Exception {
    repo.insert(row("req-1", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 100_000L, 1_000L));
    repo.insert(row("req-2", "id-1", "hash-a", AuthRequestRepository.STATE_PENDING, false, 100_000L, 5_000L));

    assertEquals(2, repo.countSince("identity_id", "id-1", 0L));
    assertEquals(1, repo.countSince("identity_id", "id-1", 2_000L));
    assertEquals(2, repo.countSince("ip_hash", "hash-a", 0L));
    assertEquals(0, repo.countSince("identity_id", "id-9", 0L));
  }

  @Test
  @DisplayName("a missing or blank request id answers null instead of throwing on a button press")
  void missingIdsAreNull() throws Exception {
    assertNull(repo.byId("nope"));
    assertNull(repo.byId(null));
    assertNull(repo.byId("  "));
  }

  private static RequestRow row(String requestId, String identityId, String ipHash, String state,
      boolean hold, long expiresAt) {
    return row(requestId, identityId, ipHash, state, hold, expiresAt, 0L);
  }

  private static RequestRow row(String requestId, String identityId, String ipHash, String state,
      boolean hold, long expiresAt, long createdAt) {
    return new RequestRow(requestId, identityId, "steve", "Steve", "discord-1", ipHash,
        "187.61.*.*", AuthRequestRepository.KIND_SESSION, state, hold, "node-1", null, 0L,
        null, null, null, 0, createdAt, expiresAt, "detail");
  }
}
