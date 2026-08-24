package com.sexidium.core.world.map;

import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * One team's side of a {@link BattleMap}: the team colour, the two opposite corners of its region
 * (kept separately so the editor can set them one click at a time) and its spawn points. Mutable
 * because it is edited a corner/spawn at a time by the map editor; serialised by {@link BattleMapStore}.
 */
public final class TeamZone {
  private final int index;
  private TeamColor color;
  private BlockPosition corner1;
  private BlockPosition corner2;
  private final List<WorldPosition> spawns = new ArrayList<>();

  public TeamZone(int index, TeamColor color) {
    this.index = index;
    this.color = color == null ? TeamColor.values()[Math.floorMod(index, TeamColor.maxTeams())] : color;
  }

  public int index() {
    return index;
  }

  public TeamColor color() {
    return color;
  }

  public void setColor(TeamColor color) {
    if (color != null) {
      this.color = color;
    }
  }

  public BlockPosition corner1() {
    return corner1;
  }

  public BlockPosition corner2() {
    return corner2;
  }

  /** Sets corner {@code 1} or {@code 2} (anything {@code <= 1} is corner 1). */
  public void setCorner(int corner, BlockPosition position) {
    if (corner <= 1) {
      corner1 = position;
    } else {
      corner2 = position;
    }
  }

  /** This zone's region box, or null until both corners are set. */
  public Cuboid region() {
    return Cuboid.between(corner1, corner2);
  }

  public List<WorldPosition> spawns() {
    return List.copyOf(spawns);
  }

  public void addSpawn(WorldPosition spawn) {
    if (spawn != null) {
      spawns.add(spawn);
    }
  }

  public void clearSpawns() {
    spawns.clear();
  }

  /** The spawn assigned to the {@code i}-th player on this team, wrapping around, or null when none. */
  public WorldPosition spawnForIndex(int i) {
    return spawns.isEmpty() ? null : spawns.get(Math.floorMod(i, spawns.size()));
  }

  /** True once this zone has a full region box and at least one spawn. */
  public boolean isReady() {
    return region() != null && !spawns.isEmpty();
  }
}
