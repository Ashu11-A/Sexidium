package com.sexidium.core.game.team;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamAllocatorTest {
  private static List<UUID> players(int count) {
    List<UUID> players = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      players.add(UUID.randomUUID());
    }
    return players;
  }

  @Test
  void teamCount_isCeilOverSize_clampedToAtLeastTwo() {
    assertEquals(2, TeamAllocator.teamCountFor(1, 2));   // tiny match still has two sides
    assertEquals(2, TeamAllocator.teamCountFor(4, 2));   // 4 / 2 = 2
    assertEquals(3, TeamAllocator.teamCountFor(5, 2));   // ceil(5/2) = 3
    assertEquals(5, TeamAllocator.teamCountFor(10, 2));  // ceil(10/2) = 5
    assertEquals(2, TeamAllocator.teamCountFor(6, 3));   // 6 / 3 = 2
  }

  @Test
  void teamCount_neverExceedsPalette() {
    assertEquals(TeamColor.maxTeams(), TeamAllocator.teamCountFor(1000, 1));
  }

  @Test
  void allocate_distributesEvenly_andEveryPlayerHasExactlyOneTeam() {
    List<UUID> roster = players(7);
    Teams teams = TeamAllocator.allocate(roster, 2);

    assertEquals(4, teams.count()); // ceil(7/2) = 4
    int total = teams.all().stream().mapToInt(Team::size).sum();
    assertEquals(7, total);
    for (UUID player : roster) {
      assertNotNull(teams.teamOf(player), "every player is on a team");
    }
    // round-robin => sizes differ by at most one
    int min = teams.all().stream().mapToInt(Team::size).min().orElse(0);
    int max = teams.all().stream().mapToInt(Team::size).max().orElse(0);
    assertTrue(max - min <= 1);
  }

  @Test
  void teammates_andSameTeam_work() {
    List<UUID> roster = players(4);
    Teams teams = TeamAllocator.allocate(roster, 2); // 2 teams of 2, round-robin: t0={0,2}, t1={1,3}
    UUID p0 = roster.get(0);
    UUID p2 = roster.get(2);
    UUID p1 = roster.get(1);
    assertTrue(teams.sameTeam(p0, p2));
    assertTrue(!teams.sameTeam(p0, p1));
    assertEquals(List.of(p2), teams.teammates(p0));
  }

  @Test
  void balanceGroups_keepsGroupsIntact_andBalancesAcrossTeams() {
    List<UUID> a = players(3);
    List<UUID> b = players(2);
    List<UUID> c = players(1);
    Map<UUID, Integer> assign = TeamAllocator.balanceGroups(List.of(a, b, c), 2);

    assertEquals(6, assign.size(), "every member is assigned");
    assertSameTeam(assign, a);
    assertSameTeam(assign, b);
    assertSameTeam(assign, c);
    assertEquals(2, assign.values().stream().distinct().count(), "both teams used");
    // Largest group (3) alone on one side; the 2- and 1-groups fill the other => 3 vs 3.
    long team0 = assign.values().stream().filter(value -> value == 0).count();
    long team1 = assign.values().stream().filter(value -> value == 1).count();
    assertEquals(3, team0);
    assertEquals(3, team1);
  }

  @Test
  void balanceGroups_neverSplitsAGroupEvenWhenUnbalanced() {
    List<UUID> big = players(5);
    List<UUID> small = players(1);
    Map<UUID, Integer> assign = TeamAllocator.balanceGroups(List.of(big, small), 2);

    assertSameTeam(assign, big);   // the 5-group is never split, even though it unbalances the sides
    assertSameTeam(assign, small);
    assertTrue(!assign.get(big.get(0)).equals(assign.get(small.get(0))), "groups land on different sides");
  }

  private static void assertSameTeam(Map<UUID, Integer> assign, List<UUID> group) {
    Integer team = assign.get(group.get(0));
    assertNotNull(team);
    for (UUID id : group) {
      assertEquals(team, assign.get(id), "a queued group must stay together on one team");
    }
  }

  @Test
  void allocateAssigned_honorsExplicitAssignments_evenWhenUnbalanced() {
    List<UUID> roster = players(4);
    UUID p0 = roster.get(0);
    UUID p1 = roster.get(1);
    UUID p2 = roster.get(2);
    UUID p3 = roster.get(3);

    Teams teams = TeamAllocator.allocateAssigned(roster, 3, Map.of(p0, 0, p1, 1, p2, 1, p3, 2));

    assertTrue(teams.team(0).contains(p0));
    assertTrue(teams.team(1).contains(p1));
    assertTrue(teams.team(1).contains(p2));
    assertTrue(teams.team(2).contains(p3));
  }
}
