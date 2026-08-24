package com.sexidium.core.game.team;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds balanced teams from a player list. The team COUNT is derived from the player count and the
 * configured players-per-team (the decided rule): {@code teams = clamp(ceil(players / size), 2,
 * palette)} — at least two teams, never more colours than {@link TeamColor} provides. Players are
 * distributed round-robin so team sizes differ by at most one.
 */
public final class TeamAllocator {
  private TeamAllocator() {
  }

  public static int teamCountFor(int playerCount, int playersPerTeam) {
    int size = Math.max(1, playersPerTeam);
    int bySize = (int) Math.ceil(playerCount / (double) size);
    return Math.max(2, Math.min(TeamColor.maxTeams(), Math.max(bySize, 1)));
  }

  /**
   * Allocates EXACTLY {@code teamCount} balanced teams (clamped to [2, palette]) regardless of player
   * count — for a host-chosen number of teams in a match lobby. Each team gets a distinct
   * {@link TeamColor}; players are distributed round-robin so sizes differ by at most one.
   */
  public static Teams allocateFixed(List<UUID> players, int teamCount) {
    List<UUID> roster = players == null ? List.of() : players;
    int count = Math.max(2, Math.min(TeamColor.maxTeams(), teamCount));
    List<Team> teams = new ArrayList<>(count);
    TeamColor[] palette = TeamColor.values();
    for (int index = 0; index < count; index++) {
      teams.add(new Team(index, palette[index], palette[index].displayName()));
    }
    for (int playerIndex = 0; playerIndex < roster.size(); playerIndex++) {
      teams.get(playerIndex % count).add(roster.get(playerIndex));
    }
    return new Teams(teams);
  }

  /** Allocates fixed teams while honoring explicit player -> team index assignments when present. */
  public static Teams allocateAssigned(List<UUID> players, int teamCount, Map<UUID, Integer> assignments) {
    List<UUID> roster = players == null ? List.of() : players;
    int count = Math.max(2, Math.min(TeamColor.maxTeams(), teamCount));
    if (assignments == null || assignments.isEmpty()) {
      return allocateFixed(roster, count);
    }
    List<Team> teams = new ArrayList<>(count);
    TeamColor[] palette = TeamColor.values();
    for (int index = 0; index < count; index++) {
      teams.add(new Team(index, palette[index], palette[index].displayName()));
    }
    Set<UUID> assigned = new HashSet<>();
    for (UUID playerId : roster) {
      Integer teamIndex = assignments.get(playerId);
      if (teamIndex != null && teamIndex >= 0 && teamIndex < count) {
        teams.get(teamIndex).add(playerId);
        assigned.add(playerId);
      }
    }
    int nextSearchStart = 0;
    for (UUID playerId : roster) {
      if (assigned.contains(playerId)) {
        continue;
      }
      int teamIndex = smallestTeamIndex(teams, nextSearchStart);
      teams.get(teamIndex).add(playerId);
      nextSearchStart = (teamIndex + 1) % count;
    }
    return new Teams(teams);
  }

  /**
   * Allocates balanced teams. {@code playersPerTeam} is the target size (default 2 in config); the
   * actual team count is {@link #teamCountFor}. Returns at least two (possibly empty when there are
   * fewer than two players) teams so a match always has opposing sides.
   */
  public static Teams allocate(List<UUID> players, int playersPerTeam) {
    List<UUID> roster = players == null ? List.of() : players;
    int teamCount = teamCountFor(roster.size(), playersPerTeam);
    List<Team> teams = new ArrayList<>(teamCount);
    TeamColor[] palette = TeamColor.values();
    for (int index = 0; index < teamCount; index++) {
      teams.add(new Team(index, palette[index], palette[index].displayName()));
    }
    for (int playerIndex = 0; playerIndex < roster.size(); playerIndex++) {
      teams.get(playerIndex % teamCount).add(roster.get(playerIndex));
    }
    return new Teams(teams);
  }

  /**
   * Distributes whole groups across {@code teamCount} teams without ever splitting a group, returning a
   * player → team-index assignment. Groups are placed largest-first into the currently-smallest team
   * (bin-packing), so a queued party stays together and team player-counts stay as even as whole groups
   * allow. Drives party-aware matchmaking: each queued lobby is one group, matched against the others.
   */
  public static Map<UUID, Integer> balanceGroups(List<List<UUID>> groups, int teamCount) {
    int count = Math.max(2, Math.min(TeamColor.maxTeams(), teamCount));
    Map<UUID, Integer> assignments = new LinkedHashMap<>();
    if (groups == null) {
      return assignments;
    }
    List<List<UUID>> ordered = new ArrayList<>();
    for (List<UUID> group : groups) {
      if (group != null && !group.isEmpty()) {
        ordered.add(group);
      }
    }
    // Largest groups first keeps the packing balanced (a big group can't be "fixed" by later small ones).
    ordered.sort((first, second) -> Integer.compare(second.size(), first.size()));
    int[] sizes = new int[count];
    for (List<UUID> group : ordered) {
      int target = smallestBin(sizes);
      for (UUID playerId : group) {
        if (playerId != null) {
          assignments.put(playerId, target);
        }
      }
      sizes[target] += group.size();
    }
    return assignments;
  }

  private static int smallestBin(int[] sizes) {
    int best = 0;
    for (int index = 1; index < sizes.length; index++) {
      if (sizes[index] < sizes[best]) {
        best = index;
      }
    }
    return best;
  }

  private static int smallestTeamIndex(List<Team> teams, int searchStart) {
    int best = 0;
    int bestSize = Integer.MAX_VALUE;
    for (int offset = 0; offset < teams.size(); offset++) {
      int index = Math.floorMod(searchStart + offset, teams.size());
      int size = teams.get(index).size();
      if (size < bestSize) {
        best = index;
        bestSize = size;
      }
    }
    return best;
  }
}
