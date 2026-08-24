package com.sexidium.core.network.transfer;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I6: one player has at most one live transfer ticket, and re-requesting the same
 * destination inside its TTL returns the same token and moves nobody.
 *
 * <p>This one rule collapses the observed eight transfers into one. {@code PlayerRouteService.request}
 * explicitly made "the newest intent win", so a repeated request was indistinguishable from a first
 * one and pass eight of a loop looked exactly like pass one to everything downstream.</p>
 */
class TransferDispatcherIdempotencyTest {

  @TempDir
  Path tmp;

  private Database database;
  private DbTransferService transfers;
  private final UUID player = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    database = TransferTestSupport.database(tmp, "idempotency");
    transfers = new DbTransferService(database, new TransferTestSupport.RecordingLogger(),
        "lobby", 30_000L, 3, 60_000L, 3);
  }

  @Test
  @DisplayName("asking twice for the same destination returns the SAME ticket")
  void repeatedRequestsAreOneTicket() {
    Optional<TransferTicket> first =
        transfers.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "death_resets_ab12");
    Optional<TransferTicket> second =
        transfers.request(player, "worker-1", 42L, TransferReason.EXPERIENCE, "death_resets_ab12");

    assertTrue(first.isPresent());
    assertTrue(second.isPresent());
    assertEquals(first.get().token(), second.get().token(),
        "a repeated request is the same intent, not a second transfer");
    assertEquals(1, transfers.inFlight().size(), "and there is exactly one row for the player");
  }

  @Test
  @DisplayName("the schema itself enforces one live transfer per player")
  void oneLiveTransferPerPlayer() {
    transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12");
    transfers.request(player, "worker-2", 2L, TransferReason.EXPERIENCE, "b_cd34");

    // The primary key is player_uuid. Invariant I6 lives in the schema, not in a code path that has
    // to remember it.
    assertEquals(1, transfers.inFlight().size());
    assertEquals("worker-2", transfers.inFlight().get(0).targetNode(),
        "a genuinely different destination does replace the old one — once the breaker allows it");
  }

  @Test
  @DisplayName("a DIFFERENT destination mints a new token")
  void aDifferentDestinationIsANewTicket() {
    String first = transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12")
        .orElseThrow().token();
    String second = transfers.request(player, "worker-2", 2L, TransferReason.EXPERIENCE, "b_cd34")
        .orElseThrow().token();

    assertNotEquals(first, second);
  }

  @Test
  @DisplayName("the same node for a different WORLD is a different intent")
  void aDifferentWorldOnTheSameNodeIsANewTicket() {
    String first = transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12")
        .orElseThrow().token();
    String second = transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "b_cd34")
        .orElseThrow().token();

    assertNotEquals(first, second, "the player asked to go somewhere else, on the same machine");
  }

  @Test
  @DisplayName("inFlight is what the launcher asks before reporting a world failure")
  void inFlightTracksTheTicket() {
    assertFalse(transfers.inFlight(player));

    TransferTicket ticket =
        transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12").orElseThrow();
    assertTrue(transfers.inFlight(player),
        "the acquisition legitimately FAILS locally when a world lives elsewhere; the launcher must"
            + " not announce that to a player who is at that moment being moved to it");

    transfers.complete(ticket.token(), TransferState.LANDED, "arrived");
    assertFalse(transfers.inFlight(player), "a landed transfer is over");
  }

  @Test
  @DisplayName("cancel drops the ticket, so a disconnect does not move anyone later")
  void cancelDropsTheTicket() {
    transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12");

    transfers.cancel(player);

    assertFalse(transfers.inFlight(player));
    assertTrue(transfers.inFlight().isEmpty());
  }

  @Test
  @DisplayName("a request with nothing to address is refused rather than written")
  void anUnaddressableRequestIsRefused() {
    assertTrue(transfers.request(null, "worker-1", 1L, TransferReason.LOBBY, null).isEmpty());
    assertTrue(transfers.request(player, null, 1L, TransferReason.LOBBY, null).isEmpty());
    assertTrue(transfers.request(player, "  ", 1L, TransferReason.LOBBY, null).isEmpty());
    assertTrue(transfers.inFlight().isEmpty());
  }

  @Test
  @DisplayName("statusOf is the acknowledgement the requester never used to get")
  void statusOfReportsTerminalState() {
    TransferTicket ticket =
        transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, "a_ab12").orElseThrow();

    assertEquals(TransferState.PENDING, transfers.statusOf(ticket.token()).orElseThrow().state());

    transfers.complete(ticket.token(), TransferState.FAILED, "the node refused");

    TransferTicket after = transfers.statusOf(ticket.token()).orElseThrow();
    assertEquals(TransferState.FAILED, after.state());
    assertEquals("the node refused", after.detail(),
        "every failure after the DELETE used to be lost silently, and the requester never told");
  }
}
