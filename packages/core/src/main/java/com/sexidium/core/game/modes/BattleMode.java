package com.sexidium.core.game.modes;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.team.Team;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.map.BattleMap;
import com.sexidium.core.world.map.BattleMapStore;
import com.sexidium.core.world.map.Cuboid;
import com.sexidium.core.world.map.TeamZone;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Shared base for the team "battle" minigames (TNT War, Gather &amp; Duel, Combat Item Mode). Owns the
 * per-map team layout — each team's region cuboid and spawn points — loaded from the map's
 * {@code sexidium-battlemap.yml} sidecar ({@link BattleMap}/{@link BattleMapStore}) and editable in-world
 * by the {@code /sx admin map edit} editor + region debugger. Subclasses keep their own match logic (lives,
 * phases, kits) and read the team layout through {@link #teamRegion(int)} / {@link #teamSpawn(int, int)}
 * / {@link #teleportTeamsToZones()}.
 */
public abstract class BattleMode extends MinigameMode {
  protected BattleMap battleMap = new BattleMap("", "");
  private ConfiguredMap resolvedMap;
  private boolean mapResolved;

  protected BattleMode(GameContext gameContext, String modeId, String displayName, int minPlayers) {
    super(gameContext, modeId, displayName, minPlayers);
  }

  protected BattleMode(GameContext gameContext, String modeId, String displayName, int minPlayers, List<String> modeArgs) {
    super(gameContext, modeId, displayName, minPlayers, modeArgs);
  }

  /**
   * The template map this match runs on. Defaults to a random configured map
   * ({@link MinigameMode#chooseConfiguredMap()}); a mode with its own rotation (TNT War) overrides this.
   * Resolved once and cached so {@link #worldTemplate()} and {@link #loadBattleMap()} agree.
   */
  protected ConfiguredMap chooseBattleMap() {
    return chooseConfiguredMap();
  }

  protected final ConfiguredMap battleMapTemplate() {
    if (!mapResolved) {
      resolvedMap = chooseBattleMap();
      mapResolved = true;
    }
    return resolvedMap;
  }

  @Override
  public String worldTemplate() {
    ConfiguredMap template = battleMapTemplate();
    return template == null ? null : template.world();
  }

  /**
   * Loads the chosen map's battle-map sidecar (importing a legacy {@code sexidium-tntwar.yml} once when no
   * {@code sexidium-battlemap.yml} exists). Call from {@code start()}. A null/unconfigured template leaves
   * an empty map, so a mode falls back to its generated layout.
   */
  protected void loadBattleMap() {
    ConfiguredMap template = battleMapTemplate();
    if (template == null) {
      battleMap = new BattleMap("", "");
      return;
    }
    Path folder = gameContext.server().worlds().worldRoot().resolve(template.world());
    battleMap = BattleMapStore.loadOrImportTntWar(folder, template.id());
  }

  protected BattleMap battleMap() {
    return battleMap;
  }

  /** The match's runtime world (the cloned arena), or null before any player is placed. */
  protected WorldAdapter matchWorld() {
    for (PlayerAdapter player : online()) {
      if (player.world() != null) {
        return player.world();
      }
    }
    return null;
  }

  /** Runtime name of the match world, or "" when not yet resolvable. */
  protected String arenaWorldName() {
    WorldAdapter world = matchWorld();
    return world == null || world.name() == null ? "" : world.name();
  }

  /** The region box of a team, or null when not (fully) configured. */
  protected Cuboid teamRegion(int teamIndex) {
    return battleMap.teamRegion(teamIndex);
  }

  /**
   * The spawn for the {@code slot}-th member of team {@code teamIndex}, re-stamped to the runtime arena
   * world (the sidecar stores template coordinates). Null when the team or its spawns are unset.
   */
  protected WorldPosition teamSpawn(int teamIndex, int slot) {
    TeamZone zone = battleMap.zone(teamIndex);
    if (zone == null) {
      return null;
    }
    WorldPosition spawn = zone.spawnForIndex(slot);
    if (spawn == null) {
      return null;
    }
    String world = arenaWorldName();
    return world.isBlank() ? spawn : spawn.withWorldName(world);
  }

  /**
   * Teleports each formed team's members round-robin over that team's configured spawns. Returns false
   * when no teams are formed or the map has no zones, so the caller can fall back to a generated layout.
   */
  protected boolean teleportTeamsToZones() {
    if (teams.isEmpty() || battleMap.teamCount() == 0) {
      return false;
    }
    boolean placed = false;
    for (Team team : teams.all()) {
      int slot = 0;
      for (UUID memberId : team.members()) {
        WorldPosition spawn = teamSpawn(team.index(), slot++);
        if (spawn != null) {
          WorldPosition target = spawn;
          gameContext.server().player(memberId).filter(PlayerAdapter::online).ifPresent(player -> player.teleport(target));
          placed = true;
        }
      }
    }
    return placed;
  }
}
