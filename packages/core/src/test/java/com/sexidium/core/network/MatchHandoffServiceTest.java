package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchHandoffServiceTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private MatchHandoffService handoffs;

  private final UUID a = UUID.randomUUID();
  private final UUID b = UUID.randomUUID();
  private final UUID c = UUID.randomUUID();
  private final UUID d = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    handoffs = new MatchHandoffService(new Database(new File(tmp.toFile(), "handoff.db")), SILENT);
  }

  private void reserveFour(long deadline) {
    handoffs.reserve("match-1", List.of(a, b, c, d), "worker-2", 7L, "tntwar", "temp/w", deadline);
  }

  @Test
  @DisplayName("a reserved roster is visible before any player has arrived")
  void reservedBeforeArrival() {
    reserveFour(System.currentTimeMillis() + 30_000L);

    // The row existing before the player does is what lets a worker hold a match open.
    assertEquals(4, handoffs.handoffs("match-1").size());
    assertEquals("match-1", handoffs.expectedMatchOf(a, "worker-2", 7L).orElseThrow().matchId());
    assertEquals("worker-2", handoffs.expectedMatchOf(a, "worker-2", 7L).orElseThrow().nodeId());
  }

  @Test
  @DisplayName("the arrival gate tells a worker which match an incoming player belongs to")
  void arrivalGate() {
    reserveFour(System.currentTimeMillis() + 30_000L);
    handoffs.markTransferring("match-1", a);

    MatchHandoffService.Handoff handoff = handoffs.expectedMatchOf(a, "worker-2", 7L).orElseThrow();

    assertEquals("tntwar", handoff.modeId());
    assertEquals("temp/w", handoff.worldKey());
    assertEquals(MatchHandoffService.STATE_TRANSFERRING, handoff.state());
  }

  @Test
  @DisplayName("a full roster assembles and is viable")
  void fullRoster() {
    reserveFour(System.currentTimeMillis() + 30_000L);
    for (UUID player : List.of(a, b, c, d)) {
      handoffs.markArrived("match-1", player);
    }

    MatchHandoffService.Assembly assembly = handoffs.assemble("match-1", 2);

    assertEquals(4, assembly.arrived().size());
    assertTrue(assembly.missing().isEmpty());
    assertTrue(assembly.viable());
  }

  @Test
  @DisplayName("a match proceeds with whoever made it, when that clears minPlayers")
  void partialRoster_stillStarts() {
    reserveFour(System.currentTimeMillis() + 30_000L);
    handoffs.markArrived("match-1", a);
    handoffs.markArrived("match-1", b);
    handoffs.markArrived("match-1", c);

    MatchHandoffService.Assembly assembly = handoffs.assemble("match-1", 2);

    assertTrue(assembly.viable());
    assertEquals(3, assembly.arrived().size());
    assertEquals(List.of(d), assembly.missing());
  }

  @Test
  @DisplayName("a match below minPlayers is not viable, so the caller aborts rather than starting")
  void tooFew_isNotViable() {
    reserveFour(System.currentTimeMillis() + 30_000L);
    handoffs.markArrived("match-1", a);

    assertFalse(handoffs.assemble("match-1", 2).viable());
  }

  @Test
  @DisplayName("a player who missed the deadline cannot walk into the started match afterwards")
  void lateArrival_isRefused() {
    reserveFour(System.currentTimeMillis() + 30_000L);
    handoffs.markArrived("match-1", a);
    handoffs.markArrived("match-1", b);
    handoffs.assemble("match-1", 2);

    // d was abandoned at assembly; a late connection must not be admitted.
    assertTrue(handoffs.expectedMatchOf(d, "worker-2", 7L).isEmpty());
    handoffs.markArrived("match-1", d);
    assertTrue(handoffs.expectedMatchOf(d, "worker-2", 7L).isEmpty(), "ABANDONED must be terminal");
  }

  @Test
  @DisplayName("an expired reservation stops being an expected match")
  void expiredReservation() {
    reserveFour(System.currentTimeMillis() - 1_000L);

    // Otherwise a player who disconnected mid-handoff is dragged into a dead match on next login.
    assertTrue(handoffs.expectedMatchOf(a, "worker-2", 7L).isEmpty());
  }

  @Test
  @DisplayName("a worker finds the matches whose deadline has passed")
  void dueMatches() {
    handoffs.reserve("late", List.of(a), "worker-2", 1L, "tntwar", "w", System.currentTimeMillis() - 5L);
    handoffs.reserve("early", List.of(b), "worker-2", 1L, "tntwar", "w", System.currentTimeMillis() + 60_000L);

    List<String> due = handoffs.dueMatches();

    assertEquals(List.of("late"), due);
  }

  @Test
  @DisplayName("clearing a finished match removes its rows")
  void clear() {
    reserveFour(System.currentTimeMillis() + 30_000L);

    handoffs.clear("match-1");

    assertTrue(handoffs.handoffs("match-1").isEmpty());
    assertTrue(handoffs.expectedMatchOf(a, "worker-2", 7L).isEmpty());
  }

  @Test
  @DisplayName("the node epoch travels with the reservation, fencing a worker that restarted")
  void epochIsRecorded() {
    reserveFour(System.currentTimeMillis() + 30_000L);

    assertEquals(7L, handoffs.expectedMatchOf(a, "worker-2", 7L).orElseThrow().nodeEpoch());
  }

  // The two "experience handoff" tests that lived here are gone with the prefix helpers. An
  // experience transfer was smuggled through this table behind a synthetic "experience:" match id,
  // which is what let one player hold two rows of different KINDS in the arrival window at once.
  // TransferDispatcherIdempotencyTest and ArrivalGateTest cover the first-class ticket that replaced
  // it; the addressing cases below cover what this table has left to do.

  @Test
  @DisplayName("a handoff addressed to ANOTHER node is not this node's to act on")
  void aHandoffForAnotherNodeIsIgnored() {
    reserveFour(System.currentTimeMillis() + 30_000L);

    // The SELECT had no node predicate at all, so a player standing on worker-3 would act on a
    // handoff addressed to worker-2, ask the placement gate, be told worker-2 owned the world, and
    // be routed there -- a loop that crossed a node which was not even involved.
    assertTrue(handoffs.expectedMatchOf(a, "worker-3", 7L).isEmpty());
    assertTrue(handoffs.expectedMatchOf(a, null, 7L).isEmpty());
  }

  @Test
  @DisplayName("a handoff addressed to a PREVIOUS boot of this node is fenced off")
  void aHandoffForAnEarlierEpochIsIgnored() {
    reserveFour(System.currentTimeMillis() + 30_000L);

    // node_epoch is the column the schema documents as fencing a worker that died mid-handoff. It
    // was never compared to anything.
    assertTrue(handoffs.expectedMatchOf(a, "worker-2", 99L).isEmpty());
    assertEquals("match-1", handoffs.expectedMatchOf(a, "worker-2", 7L).orElseThrow().matchId());
  }

  @Test
  @DisplayName("a zero epoch on either side matches, so standalone and legacy rows still work")
  void aZeroEpochMatchesAnything() {
    handoffs.reserve("legacy", List.of(a), "worker-2", 0L, "tntwar", "temp/w",
        System.currentTimeMillis() + 30_000L);

    assertEquals("legacy", handoffs.expectedMatchOf(a, "worker-2", 7L).orElseThrow().matchId());
    assertEquals("legacy", handoffs.expectedMatchOf(a, "worker-2", 0L).orElseThrow().matchId());
  }

  @Test
  @DisplayName("with two rows in window the NEWEST wins, and only one is returned")
  void theNewestRowWins() {
    long now = System.currentTimeMillis();
    handoffs.reserve("older", List.of(a), "worker-2", 7L, "tntwar", "temp/old", now + 10_000L);
    handoffs.reserve("newer", List.of(a), "worker-2", 7L, "tntwar", "temp/new", now + 40_000L);

    // The untargeted select took rs.next() with no ORDER BY. Live, the row it happened to pick was a
    // stale one nothing had ever deleted, and the arrival was silently swallowed.
    assertEquals("newer", handoffs.expectedMatchOf(a, "worker-2", 7L).orElseThrow().matchId());
  }

  @Test
  @DisplayName("expecting a player twice replaces the expectation instead of colliding")
  void expectIsIdempotent() {
    String world = "diamond_hunt_ab12cd34";
    String id = "match-9";

    handoffs.expect(id, a, "worker-1", 7L, "tntwar", world, System.currentTimeMillis() + 30_000L);
    // Entering the same world a second time is the ordinary case, not an edge one: the row already
    // exists and a plain INSERT would collide on the primary key and silently expect nothing.
    handoffs.expect(id, a, "worker-2", 8L, "tntwar", world, System.currentTimeMillis() + 30_000L);

    var expected = handoffs.expectedMatchOf(a, "worker-2", 8L).orElseThrow();
    assertEquals("worker-2", expected.nodeId());
    assertEquals(world, expected.worldKey());
    assertEquals(1, handoffs.handoffs(id).size());

    handoffs.forget(id, a);
    assertTrue(handoffs.expectedMatchOf(a, "worker-2", 8L).isEmpty());
  }
}
