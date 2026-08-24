package com.sexidium.core.world.lobby;
import com.sexidium.core.data.FriendGraph;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.world.lobby.LobbyEnums.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyTest {

  @Test
  void constructor_leaderIsAutoMember_andIdleSolo() {
    UUID leader = UUID.randomUUID();
    Lobby lobby = new Lobby(leader);
    assertEquals(leader, lobby.leader());
    assertEquals(leader, lobby.id());
    assertTrue(lobby.isMember(leader));
    assertTrue(lobby.isLeader(leader));
    assertEquals(1, lobby.size());
    assertTrue(lobby.isIdle());
    assertEquals(LobbyVisibility.PUBLIC, lobby.visibility());
  }

  @Test
  void members_returnsDefensiveCopy() {
    Lobby lobby = new Lobby(UUID.randomUUID());
    List<UUID> members = lobby.members();
    assertThrows(UnsupportedOperationException.class, () -> members.add(UUID.randomUUID()));
  }

  @Test
  void addAndRemove_updateMembership() {
    UUID leader = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Lobby lobby = new Lobby(leader);
    lobby.add(member);
    assertEquals(2, lobby.size());
    assertTrue(lobby.isMember(member));
    lobby.remove(member);
    assertEquals(1, lobby.size());
    assertFalse(lobby.isMember(member));
  }

  @Test
  void transferHost_onlyToMember_keepsStableId() {
    UUID leader = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    Lobby lobby = new Lobby(leader);
    lobby.add(member);
    assertFalse(lobby.transferHost(UUID.randomUUID()));
    assertTrue(lobby.transferHost(member));
    assertEquals(member, lobby.leader());
    assertEquals(leader, lobby.id(), "id stays the creating leader's UUID across host transfer");
  }

  @Test
  void stateTransitions_carryTheMode() {
    Lobby lobby = new Lobby(UUID.randomUUID());
    lobby.toQueued("duel");
    assertTrue(lobby.isQueued());
    assertEquals("duel", lobby.modeId());
    lobby.toConfigured("combat");
    assertTrue(lobby.isConfigured());
    assertEquals("combat", lobby.modeId());
    lobby.toIdle();
    assertTrue(lobby.isIdle());
    assertNull(lobby.modeId());
  }

  @Test
  void canJoin_respectsVisibility() {
    UUID host = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Lobby lobby = new Lobby(host);
    FriendGraph friends = new FriendGraph() {
      @Override public boolean areFriends(UUID a, UUID b) {
        return (a.equals(host) && b.equals(other)) || (a.equals(other) && b.equals(host));
      }
      @Override public List<FriendService.Entry> friends(UUID owner) { return List.of(); }
    };

    assertTrue(lobby.canJoin(host, null, false), "host always");

    lobby.setVisibility(LobbyVisibility.PUBLIC);
    assertTrue(lobby.canJoin(other, null, false));

    lobby.setVisibility(LobbyVisibility.INVITE_ONLY);
    assertFalse(lobby.canJoin(other, friends, false));
    assertTrue(lobby.canJoin(other, friends, true), "an invited player may join an invite-only lobby");

    lobby.setVisibility(LobbyVisibility.FRIENDS_ONLY);
    assertTrue(lobby.canJoin(other, friends, false), "a friend may join a friends-only lobby");
    assertFalse(lobby.canJoin(UUID.randomUUID(), friends, false), "a stranger may not");
  }

  // ----- team config (ported from the old MatchLobbyTest) -----

  @Test
  void selectTeam_togglesAndEnforcesCapacity() {
    UUID host = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    Lobby lobby = new Lobby(host);
    lobby.add(other);
    lobby.setTeamCount(2);
    lobby.setTeamSize(1);

    assertTrue(lobby.selectTeam(host, 0));
    assertFalse(lobby.selectTeam(other, 0));
    assertTrue(lobby.selectTeam(other, 1));
    assertEquals(0, lobby.selectedTeam(host));
    assertEquals(1, lobby.selectedTeam(other));

    lobby.clearTeamSelection(host);
    assertNull(lobby.selectedTeam(host));
  }

  @Test
  void ffaClearsTeamSelections() {
    UUID host = UUID.randomUUID();
    Lobby lobby = new Lobby(host);
    lobby.setTeamCount(2);
    assertTrue(lobby.selectTeam(host, 0));

    lobby.setTeamCount(0);
    assertNull(lobby.selectedTeam(host));
  }

  @Test
  void assignedTeamsForStart_preservesSelectionsAndFillsUnselectedSlots() {
    UUID p0 = UUID.randomUUID();
    UUID p1 = UUID.randomUUID();
    UUID p2 = UUID.randomUUID();
    UUID p3 = UUID.randomUUID();
    Lobby lobby = new Lobby(p0);
    lobby.add(p1);
    lobby.add(p2);
    lobby.add(p3);
    lobby.setTeamCount(2);
    lobby.setTeamSize(2);
    assertTrue(lobby.selectTeam(p0, 0));
    assertTrue(lobby.selectTeam(p1, 1));

    Map<UUID, Integer> assignments = lobby.assignedTeamsForStart(List.of(p0, p1, p2, p3));

    assertEquals(4, assignments.size());
    assertEquals(0, assignments.get(p0));
    assertEquals(1, assignments.get(p1));
    int team0 = 0;
    int team1 = 0;
    for (int teamIndex : assignments.values()) {
      if (teamIndex == 0) {
        team0++;
      } else if (teamIndex == 1) {
        team1++;
      }
    }
    assertEquals(2, team0);
    assertEquals(2, team1);
  }

  @Test
  void requiredPlayersForStart_isMaxOfMinAndTeamCount() {
    Lobby lobby = new Lobby(UUID.randomUUID());
    lobby.setTeamCount(2);
    assertEquals(2, lobby.requiredPlayersForStart());
  }
}
