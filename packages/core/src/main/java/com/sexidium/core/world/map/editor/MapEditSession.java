package com.sexidium.core.world.map.editor;

import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.map.BattleMap;
import com.sexidium.core.world.map.TeamZone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * One admin's live map-edit session: the map being edited (held in memory and mutated a corner/spawn at
 * a time), the loaded template world they are editing in, the team they are currently focused on, the
 * repeating debug-render task, an undo history of map snapshots, and the admin's pre-edit inventory (so the
 * hotbar tools are restored on exit). Owned exclusively by {@link MapEditorService}.
 */
final class MapEditSession {
  /** Cap on undo depth so a long edit session never grows the history without bound. */
  private static final int MAX_UNDO = 25;

  private final UUID adminId;
  private final String modeId;
  private final String mapId;
  private final String worldName;
  private final WorldAdapter world;
  private final WorldLease lease;
  private final BattleMap battleMap;
  private final GameModeType priorGameMode;
  private final Deque<List<ZoneSnap>> undoStack = new ArrayDeque<>();
  private List<ItemStackData> savedInventory;
  private int currentTeam;
  private ScheduledTask renderTask;

  MapEditSession(UUID adminId, String modeId, String mapId, String worldName, WorldAdapter world,
      WorldLease lease, BattleMap battleMap, GameModeType priorGameMode) {
    this.adminId = adminId;
    this.modeId = modeId;
    this.mapId = mapId;
    this.worldName = worldName;
    this.world = world;
    this.lease = lease;
    this.battleMap = battleMap;
    this.priorGameMode = priorGameMode;
  }

  /** Snapshot of one team zone, used by the undo history. */
  private record ZoneSnap(int index, TeamColor color, BlockPosition corner1, BlockPosition corner2,
      List<WorldPosition> spawns) {
  }

  /** Captures the current map onto the undo stack BEFORE a mutating edit (corner/spawn/delete). */
  void pushUndo() {
    List<ZoneSnap> snapshot = new ArrayList<>();
    for (TeamZone zone : battleMap.zones()) {
      snapshot.add(new ZoneSnap(zone.index(), zone.color(), zone.corner1(), zone.corner2(),
          List.copyOf(zone.spawns())));
    }
    undoStack.push(snapshot);
    while (undoStack.size() > MAX_UNDO) {
      undoStack.removeLast();
    }
  }

  /** Reverts the most recent edit, rebuilding every zone from the snapshot. Returns false when empty. */
  boolean undo() {
    if (undoStack.isEmpty()) {
      return false;
    }
    List<ZoneSnap> snapshot = undoStack.pop();
    battleMap.clearZones();
    for (ZoneSnap snap : snapshot) {
      TeamZone zone = battleMap.ensureZone(snap.index(), snap.color());
      zone.setCorner(1, snap.corner1());
      zone.setCorner(2, snap.corner2());
      for (WorldPosition spawn : snap.spawns()) {
        zone.addSpawn(spawn);
      }
    }
    return true;
  }

  List<ItemStackData> savedInventory() {
    return savedInventory;
  }

  void setSavedInventory(List<ItemStackData> savedInventory) {
    this.savedInventory = savedInventory;
  }

  UUID adminId() {
    return adminId;
  }

  String modeId() {
    return modeId;
  }

  String mapId() {
    return mapId;
  }

  String worldName() {
    return worldName;
  }

  WorldAdapter world() {
    return world;
  }

  WorldLease lease() {
    return lease;
  }

  BattleMap battleMap() {
    return battleMap;
  }

  GameModeType priorGameMode() {
    return priorGameMode;
  }

  int currentTeam() {
    return currentTeam;
  }

  void setCurrentTeam(int currentTeam) {
    this.currentTeam = Math.max(0, currentTeam);
  }

  ScheduledTask renderTask() {
    return renderTask;
  }

  void setRenderTask(ScheduledTask renderTask) {
    this.renderTask = renderTask;
  }
}
