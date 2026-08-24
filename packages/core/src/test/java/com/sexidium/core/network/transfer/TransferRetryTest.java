package com.sexidium.core.network.transfer;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim-then-complete, and the bounded retry behind it.
 *
 * <p>The old consumer SELECTed and DELETEd in one breath and only then attempted the connect, so a
 * failed connect logged one warning and lost the intent, a full or draining backend produced a red
 * chat line and nothing, and a proxy restart between the two dropped the transfer entirely — with the
 * requesting node told nothing in every case.</p>
 */
class TransferRetryTest {

  @TempDir
  Path tmp;

  private Database database;
  private DbTransferService transfers;
  private final UUID player = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    database = TransferTestSupport.database(tmp, "retry");
    transfers = new DbTransferService(database, new TransferTestSupport.RecordingLogger(),
        "lobby", 30_000L, 3, 60_000L, 3);
  }

  @Test
  @DisplayName("claiming leases rather than deleting, so a dead proxy loses nothing")
  void claimingLeases() {
    transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12");

    List<TransferTicket> claimed = transfers.claim("proxy-1", 10, 60_000L);
    assertEquals(1, claimed.size());
    assertEquals(TransferState.DISPATCHED, claimed.get(0).state());
    assertEquals("proxy-1", claimed.get(0).claimedBy());

    // Still there. A delete would mean a proxy that dies here has silently eaten the player's intent.
    assertEquals(1, transfers.inFlight().size());
  }

  @Test
  @DisplayName("a second proxy cannot steal a live claim")
  void aLiveClaimIsExclusive() {
    transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12");

    assertEquals(1, transfers.claim("proxy-1", 10, 60_000L).size());
    assertTrue(transfers.claim("proxy-2", 10, 60_000L).isEmpty(),
        "\"safe because there is only one proxy\" is an argument that collapses at two");
  }

  @Test
  @DisplayName("an EXPIRED claim is reclaimable by another proxy")
  void anExpiredClaimIsReclaimable() throws Exception {
    transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12");
    assertEquals(1, transfers.claim("proxy-1", 10, 1L).size());
    Thread.sleep(10L);

    assertEquals(1, transfers.claim("proxy-2", 10, 60_000L).size(),
        "a proxy that died mid-transfer must not hold a player's ticket hostage");
  }

  @Test
  @DisplayName("retry requeues, and the attempt bound turns the last one into a FAILED")
  void retryIsBounded() {
    String token = transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12")
        .orElseThrow().token();

    for (int attempt = 1; attempt <= 3; attempt++) {
      assertEquals(1, transfers.claim("proxy-1", 10, 60_000L).size(), "attempt " + attempt);
      transfers.retry(token, "the target refused the connection");
    }

    TransferTicket after = transfers.statusOf(token).orElseThrow();
    assertEquals(TransferState.FAILED, after.state(),
        "without a bound, 'keep trying' is the same failure the loop breaker exists for");
    assertTrue(after.detail().contains("gave up"), after.detail());
    assertTrue(transfers.claim("proxy-1", 10, 60_000L).isEmpty(), "and it stops being actionable");
  }

  @Test
  @DisplayName("a ticket nobody acted on EXPIRES rather than staying actionable forever")
  void anUnactionedTicketExpires() throws Exception {
    DbTransferService brief = new DbTransferService(database,
        new TransferTestSupport.RecordingLogger(), "lobby", 1L, 3, 60_000L, 3);
    String token = brief.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12")
        .orElseThrow().token();
    Thread.sleep(10L);

    assertTrue(brief.claim("proxy-1", 10, 60_000L).isEmpty());
    assertEquals(TransferState.EXPIRED, brief.statusOf(token).orElseThrow().state(),
        "an intent that timed out unread used to disappear with no log and no record");
  }

  @Test
  @DisplayName("complete is the acknowledgement: a terminal state and a reason")
  void completeIsAnAcknowledgement() {
    String token = transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12")
        .orElseThrow().token();
    transfers.claim("proxy-1", 10, 60_000L);

    transfers.complete(token, TransferState.FAILED, "no backend registered as 'worker-1'");

    TransferTicket after = transfers.statusOf(token).orElseThrow();
    assertEquals(TransferState.FAILED, after.state());
    assertEquals("no backend registered as 'worker-1'", after.detail());
    assertEquals(null, after.claimedBy(), "and the claim is released with it");
  }

  @Test
  @DisplayName("complete refuses a non-terminal state")
  void completeRefusesANonTerminalState() {
    String token = transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12")
        .orElseThrow().token();

    transfers.complete(token, TransferState.PENDING, "nonsense");

    assertEquals(TransferState.PENDING, transfers.statusOf(token).orElseThrow().state());
  }
}
