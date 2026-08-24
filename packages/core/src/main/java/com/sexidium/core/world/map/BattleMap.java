package com.sexidium.core.world.map;

import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The persisted layout of one team battle map: which template world holds it and, per team, the
 * region cuboid plus spawn points. Generalises TNT War's two-team {@code TntWarMap} to any number of
 * teams so the same model drives TNT War, Gather &amp; Duel and Combat Item Mode (every mode extending
 * {@link com.sexidium.core.game.modes.BattleMode}). Edited a corner/spawn at a time by the map editor
 * and serialised to {@code sexidium-battlemap.yml} inside the map's world folder by
 * {@link BattleMapStore}.
 */
public final class BattleMap {
  private final String id;
  private String world;
  // Ordered by team index so render/teleport iteration is stable (team 0 = red, team 1 = blue, …).
  private final Map<Integer, TeamZone> zones = new LinkedHashMap<>();

  public BattleMap(String id, String world) {
    this.id = id;
    this.world = world == null ? "" : world;
  }

  public String id() {
    return id;
  }

  public String world() {
    return world;
  }

  public void setWorld(String world) {
    this.world = world == null ? "" : world;
  }

  /** Returns the zone for {@code index}, creating it (with the palette colour for that index) if absent. */
  public TeamZone ensureZone(int index) {
    return ensureZone(index, null);
  }

  public TeamZone ensureZone(int index, TeamColor color) {
    return zones.computeIfAbsent(index, i -> new TeamZone(i, color));
  }

  /** The zone for {@code index}, or null when that team has not been configured. */
  public TeamZone zone(int index) {
    return zones.get(index);
  }

  /** All configured zones, ordered by team index. */
  public Collection<TeamZone> zones() {
    return zones.values();
  }

  /** Drops every configured zone. Used by the editor's undo to rebuild the map from a snapshot. */
  public void clearZones() {
    zones.clear();
  }

  public int teamCount() {
    return zones.size();
  }

  public void setCorner(int teamIndex, int corner, BlockPosition position) {
    ensureZone(teamIndex).setCorner(corner, position);
  }

  public void addSpawn(int teamIndex, WorldPosition spawn) {
    ensureZone(teamIndex).addSpawn(spawn);
  }

  public void clearSpawns(int teamIndex) {
    TeamZone zone = zones.get(teamIndex);
    if (zone != null) {
      zone.clearSpawns();
    }
  }

  /** The region box for a team, or null when not (fully) set. */
  public Cuboid teamRegion(int teamIndex) {
    TeamZone zone = zones.get(teamIndex);
    return zone == null ? null : zone.region();
  }

  /** True once at least two teams are configured and every configured zone is ready. */
  public boolean isReady() {
    if (zones.size() < 2) {
      return false;
    }
    for (TeamZone zone : zones.values()) {
      if (!zone.isReady()) {
        return false;
      }
    }
    return true;
  }
}
