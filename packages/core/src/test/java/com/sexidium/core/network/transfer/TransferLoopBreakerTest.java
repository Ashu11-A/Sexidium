package com.sexidium.core.network.transfer;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I7, replayed against the live trace.
 *
 * <p>The proxy log, one player, 45 seconds:</p>
 *
 * <pre>
 *   01:10:04  Routing Ashu11a to 'worker-1' for experience (…_r14)
 *   01:10:17  Routing Ashu11a to 'lobby'    for lobby ()
 *   01:10:21  Routing Ashu11a to 'worker-1' for experience (…_r14)
 *   01:10:29  Routing Ashu11a to 'lobby'    for lobby ()
 *   01:10:30  Routing Ashu11a to 'worker-1' for experience (…_r14)
 *   01:10:37  Routing Ashu11a to 'lobby'    for lobby ()
 *   01:10:41  Routing Ashu11a to 'worker-1' for experience (…_r14)
 *   01:10:49  Routing Ashu11a to 'lobby'    for lobby ()
 * </pre>
 *
 * <p>Eight transfers, and nothing in the system was aware it was happening: the requester was never
 * told an outcome, the route row was deleted before the connect was even attempted, and
 * {@code PlayerRouteService.request} explicitly made the newest intent win, so pass eight was
 * indistinguishable from pass one. Stages 2 and 3 remove the causes we found; this bounds whatever
 * cause we did not.</p>
 */
class TransferLoopBreakerTest {

  private static final String WORLD = "death_resets_002a7816_r14";

  @TempDir
  Path tmp;

  private Database database;
  private TransferTestSupport.RecordingLogger logger;
  private DbTransferService transfers;
  private final UUID player = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    database = TransferTestSupport.database(tmp, "breaker");
    logger = new TransferTestSupport.RecordingLogger();
    // The shipped bound: 3 transfers of one player to one node per 60 s.
    transfers = new DbTransferService(database, logger, "lobby", 30_000L, 3, 60_000L, 3);
  }

  @Test
  @DisplayName("an EXIT to the lobby is never refused, however many times it is asked for")
  void leavingIsNeverBounded() {
    // Refusing to let a player LEAVE cannot break a loop -- a loop needs somewhere to send them --
    // and it strands them instead. Live, once the (player, lobby) window filled, /leave on a worker
    // had nowhere to route, the worker has no lobby world of its own, and every attempt answered
    // "You are not in a match" while the player sat on a node they could not get off.
    for (int attempt = 1; attempt <= 20; attempt++) {
      // A different destination each time so the idempotent fast path cannot be what is passing this.
      transfers.request(player, "worker-" + attempt, 0L, TransferReason.EXPERIENCE, WORLD);
      assertTrue(transfers.request(player, "lobby", 0L, TransferReason.LOBBY, null).isPresent(),
          "attempt " + attempt + ": leaving must always be possible");
    }
  }

  @Test
  @DisplayName("the live 8-transfer trace is bounded to 3, with a trip logged and a refusal returned")
  void theLiveTraceIsBounded() {
    List<String> accepted = new ArrayList<>();
    List<Integer> refusedAtPass = new ArrayList<>();

    for (int pass = 1; pass <= 8; pass++) {
      // Each pass is the pair the trace shows: out to the worker, then back to the lobby.
      Optional<TransferTicket> out =
          transfers.request(player, "worker-1", 1786482599387L, TransferReason.EXPERIENCE, WORLD);
      if (out.isEmpty()) {
        refusedAtPass.add(pass);
      } else {
        accepted.add(out.get().token());
        // The player lands, is ejected, and the worker sends them back — the loop's return leg.
        transfers.claimArrival(player, "worker-1", 1786482599387L);
        transfers.request(player, "lobby", 0L, TransferReason.LOBBY, null);
      }
    }

    assertEquals(3, accepted.size(),
        "at most three transfers of one player to one node per minute; got " + accepted.size());
    assertFalse(refusedAtPass.isEmpty(), "the remaining passes must be REFUSED, not merely slower");
    assertEquals(4, refusedAtPass.get(0),
        "the fourth attempt is where the bound bites; got " + refusedAtPass);
    assertTrue(transfers.trips() >= 1, "a trip has to be countable for the admin surface");

    String trip = logger.severe.stream()
        .filter(line -> line.contains("TRANSFER LOOP BREAKER"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("the trip must be logged: " + logger.severe));
    assertTrue(trip.contains("worker-1") && trip.contains("lobby"),
        "with BOTH node ids, or an operator cannot tell which pair is bouncing: " + trip);
    assertTrue(trip.contains(player.toString()), "and the player: " + trip);
  }

  @Test
  @DisplayName("a refused request is an answer, so the caller can tell the player something")
  void aRefusalIsReported() {
    for (int i = 0; i < 3; i++) {
      assertTrue(transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, WORLD)
          .isPresent());
      transfers.claimArrival(player, "worker-1", 1L);
    }

    // Empty, not a silently-written row. The whole point is that the caller LEARNS it was refused —
    // the old design had no way to express "I will not do this", so nothing could react to a loop.
    assertTrue(transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, WORLD).isEmpty());
  }

  @Test
  @DisplayName("the bound is PER PLAYER, so a party of six entering one world is fine")
  void theBoundIsPerPlayer() {
    for (int i = 0; i < 6; i++) {
      UUID member = UUID.randomUUID();
      assertTrue(transfers.request(member, "worker-1", 1L, TransferReason.EXPERIENCE, WORLD)
              .isPresent(),
          "six players with one transfer each is the ordinary case, not a loop");
    }
    assertEquals(0, transfers.trips());
  }

  @Test
  @DisplayName("the bound is PER NODE, so a different destination is not collateral damage")
  void theBoundIsPerNode() {
    for (int i = 0; i < 3; i++) {
      transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, WORLD);
      transfers.claimArrival(player, "worker-1", 1L);
    }
    assertTrue(transfers.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, WORLD).isEmpty());

    assertTrue(transfers.request(player, "worker-2", 1L, TransferReason.EXPERIENCE, "other_cd34")
            .isPresent(),
        "a player blocked from one node must still be able to go somewhere else");
  }

  @Test
  @DisplayName("the window rolls over, so a blocked player is not blocked forever")
  void theWindowRollsOver() throws Exception {
    // A one-second window (the minimum the breaker accepts), waited out between attempts: the bound
    // still applies WITHIN a window, and every attempt here starts a fresh one. Without a rollover a
    // player who tripped the breaker once could never enter a world again.
    DbTransferService brief = new DbTransferService(database, logger, "lobby", 30_000L, 3, 1L, 3);
    for (int i = 0; i < 5; i++) {
      assertTrue(brief.request(player, "worker-1", 1L, TransferReason.EXPERIENCE, WORLD).isPresent(),
          "attempt " + i + " should be a fresh window");
      brief.claimArrival(player, "worker-1", 1L);
      Thread.sleep(1_050L);
    }
  }

  @Test
  @DisplayName("a breaker that cannot read the database allows rather than strands everyone")
  void aBrokenBreakerFailsOpen() throws Exception {
    // A database blip must not become "nobody may ever be transferred again": that would strand every
    // player on whatever node they happened to be standing on.
    Database closed = TransferTestSupport.database(tmp, "closed");
    DbTransferService broken =
        new DbTransferService(closed, logger, "lobby", 30_000L, 3, 60_000L, 3);
    closed.close();

    assertTrue(broken.allow(player, "worker-1"));
  }
}
