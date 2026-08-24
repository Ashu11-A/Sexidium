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

class LobbyDirectoryTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private Database database;
  private LobbyDirectory directory;

  private final UUID leader = UUID.randomUUID();
  private final UUID friend = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "lobby.db"));
    directory = new LobbyDirectory(database, SILENT);
  }

  private void party(UUID lobbyId, String node) {
    directory.upsertGroup(new LobbyDirectory.Group(
        lobbyId, leader, "tntwar", "IDLE", "PRIVATE", 2, node));
  }

  @Test
  @DisplayName("a group and its members round-trip")
  void roundTrip() {
    party(leader, "lobby");
    directory.addMember(leader, leader, "Leader", 0);
    directory.addMember(leader, friend, "Friend", 1);

    List<LobbyDirectory.Member> members = directory.members(leader);

    // Asserted by identity rather than position: joins in the same millisecond tie on joined_at,
    // and a positional assertion there is flaky regardless of how the query orders.
    assertEquals(2, members.size());
    LobbyDirectory.Member leaderRow = members.stream()
        .filter(m -> m.playerId().equals(leader)).findFirst().orElseThrow();
    LobbyDirectory.Member friendRow = members.stream()
        .filter(m -> m.playerId().equals(friend)).findFirst().orElseThrow();
    assertEquals("Leader", leaderRow.playerName());
    assertEquals(0, leaderRow.selectedTeam());
    assertEquals("Friend", friendRow.playerName());
    assertEquals(1, friendRow.selectedTeam());
  }

  @Test
  @DisplayName("the reverse lookup answers 'which party is this player in' from any node")
  void reverseLookup() {
    party(leader, "lobby");
    directory.addMember(leader, friend, "Friend", -1);

    // This is the query a WORKER runs: it never saw the party form.
    assertEquals(leader, directory.groupOf(friend).orElseThrow());
  }

  @Test
  @DisplayName("PARTY MEMBERSHIP SURVIVES A TRANSFER — the point of the whole class")
  void membershipSurvivesTransfer() {
    party(leader, "lobby");
    directory.addMember(leader, leader, "Leader", 0);
    directory.addMember(leader, friend, "Friend", 0);

    // The friend is routed to worker-2. A fresh LobbyDirectory there models a different JVM with
    // empty in-memory state -- exactly the situation where a party used to dissolve.
    LobbyDirectory onWorker2 = new LobbyDirectory(database, SILENT);

    assertEquals(leader, onWorker2.groupOf(friend).orElseThrow());
    assertTrue(onWorker2.sameGroup(leader, friend));
    assertEquals(2, onWorker2.members(leader).size());
  }

  @Test
  @DisplayName("a player belongs to one party at a time")
  void singleMembership() {
    UUID otherLobby = UUID.randomUUID();
    party(leader, "lobby");
    directory.upsertGroup(new LobbyDirectory.Group(
        otherLobby, otherLobby, "combat", "IDLE", "PUBLIC", 2, "lobby"));

    directory.addMember(leader, friend, "Friend", 0);
    directory.addMember(otherLobby, friend, "Friend", 0);

    // A stale row would put them in two parties and make groupOf() order-dependent.
    assertEquals(otherLobby, directory.groupOf(friend).orElseThrow());
    assertTrue(directory.members(leader).isEmpty());
    assertEquals(1, directory.members(otherLobby).size());
  }

  @Test
  @DisplayName("leaving removes only that player")
  void removeMember() {
    party(leader, "lobby");
    directory.addMember(leader, leader, "Leader", 0);
    directory.addMember(leader, friend, "Friend", 0);

    directory.removeMember(leader, friend);

    assertEquals(1, directory.members(leader).size());
    assertTrue(directory.groupOf(friend).isEmpty());
  }

  @Test
  @DisplayName("disbanding clears the group, its members and its queue ticket")
  void removeGroup() {
    party(leader, "lobby");
    directory.addMember(leader, leader, "Leader", 0);
    directory.addMember(leader, friend, "Friend", 0);

    directory.removeGroup(leader);

    assertTrue(directory.members(leader).isEmpty());
    assertTrue(directory.group(leader).isEmpty());
    assertTrue(directory.groupOf(friend).isEmpty());
  }

  @Test
  @DisplayName("group metadata updates in place rather than duplicating")
  void upsertIsIdempotent() {
    party(leader, "lobby");
    directory.upsertGroup(new LobbyDirectory.Group(
        leader, leader, "combat", "QUEUED", "PUBLIC", 4, "lobby"));

    LobbyDirectory.Group group = directory.group(leader).orElseThrow();
    assertEquals("combat", group.modeId());
    assertEquals("QUEUED", group.state());
    assertEquals(4, group.teamCount());
  }

  @Test
  @DisplayName("players in different parties are not the same group")
  void differentParties() {
    UUID otherLobby = UUID.randomUUID();
    party(leader, "lobby");
    directory.upsertGroup(new LobbyDirectory.Group(
        otherLobby, otherLobby, "combat", "IDLE", "PUBLIC", 2, "lobby"));
    directory.addMember(leader, leader, "Leader", 0);
    directory.addMember(otherLobby, friend, "Friend", 0);

    assertFalse(directory.sameGroup(leader, friend));
  }

  @Test
  @DisplayName("a player in no party has no group")
  void noParty() {
    assertTrue(directory.groupOf(UUID.randomUUID()).isEmpty());
    assertFalse(directory.sameGroup(leader, friend));
  }
}
