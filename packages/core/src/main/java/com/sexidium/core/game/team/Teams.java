package com.sexidium.core.game.team;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The runtime team assignment for a match: the list of {@link Team}s plus lookups a minigame needs
 * (which team a player is on, their teammates, how many teams still have a member). Built by
 * {@link TeamAllocator}. A minigame that has no teams simply never creates one.
 */
public final class Teams {
  private final List<Team> teams;

  public Teams(List<Team> teams) {
    this.teams = teams == null ? new ArrayList<>() : new ArrayList<>(teams);
  }

  public static Teams empty() {
    return new Teams(new ArrayList<>());
  }

  public List<Team> all() {
    return new ArrayList<>(teams);
  }

  public int count() {
    return teams.size();
  }

  public boolean isEmpty() {
    return teams.isEmpty();
  }

  public Team team(int index) {
    return index >= 0 && index < teams.size() ? teams.get(index) : null;
  }

  public Team teamOf(UUID playerId) {
    if (playerId == null) {
      return null;
    }
    for (Team team : teams) {
      if (team.contains(playerId)) {
        return team;
      }
    }
    return null;
  }

  public boolean sameTeam(UUID first, UUID second) {
    Team team = teamOf(first);
    return team != null && team.contains(second);
  }

  public List<UUID> teammates(UUID playerId) {
    Team team = teamOf(playerId);
    if (team == null) {
      return List.of();
    }
    List<UUID> mates = new ArrayList<>(team.members());
    mates.remove(playerId);
    return mates;
  }

  public void remove(UUID playerId) {
    for (Team team : teams) {
      team.remove(playerId);
    }
  }

  /** Teams that still contain at least one of the given still-alive players. */
  public List<Team> teamsWithAnyOf(Iterable<UUID> alivePlayers) {
    List<Team> alive = new ArrayList<>();
    for (Team team : teams) {
      for (UUID playerId : alivePlayers) {
        if (team.contains(playerId)) {
          alive.add(team);
          break;
        }
      }
    }
    return alive;
  }
}
