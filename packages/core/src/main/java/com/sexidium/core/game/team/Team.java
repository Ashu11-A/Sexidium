package com.sexidium.core.game.team;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** A single team in a match: a stable index, a colour, a display name and its member set. */
public final class Team {
  private final int index;
  private final TeamColor color;
  private final String name;
  private final Set<UUID> members = new LinkedHashSet<>();

  public Team(int index, TeamColor color, String name) {
    this.index = index;
    this.color = color;
    this.name = name == null || name.isBlank() ? color.displayName() : name;
  }

  public int index() {
    return index;
  }

  public TeamColor color() {
    return color;
  }

  public String name() {
    return name;
  }

  /** The team name rendered in its colour for MiniMessage. */
  public String coloredName() {
    return color.colorize(name);
  }

  public Set<UUID> members() {
    return members;
  }

  public boolean contains(UUID playerId) {
    return playerId != null && members.contains(playerId);
  }

  public void add(UUID playerId) {
    if (playerId != null) {
      members.add(playerId);
    }
  }

  public void remove(UUID playerId) {
    members.remove(playerId);
  }

  public int size() {
    return members.size();
  }
}
