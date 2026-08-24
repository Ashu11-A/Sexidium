package com.sexidium.core.world.map;

import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Reads/writes a {@link BattleMap} as {@code sexidium-battlemap.yml} inside the map's world folder,
 * using the shared flat {@code key: value} YAML subset ({@link FlatYaml}) so the format is identical on
 * Paper and NeoForge and a cloned match world carries its own copy. Mirrors the load/save shape of the
 * older {@code TntWarMapStore} / {@link SpawnPointStore}, generalised to N teams.
 */
public final class BattleMapStore {
  public static final String FILE_NAME = "sexidium-battlemap.yml";
  /** Legacy TNT-War sidecar, imported once into a {@link BattleMap} when no battlemap file exists yet. */
  public static final String LEGACY_TNTWAR_FILE = "sexidium-tntwar.yml";

  private BattleMapStore() {
  }

  public static Path fileIn(Path worldFolder) {
    return worldFolder.resolve(FILE_NAME);
  }

  public static boolean exists(Path worldFolder) {
    return worldFolder != null && Files.isRegularFile(fileIn(worldFolder));
  }

  public static BattleMap load(Path worldFolder, String id) {
    BattleMap map = new BattleMap(id, "");
    Map<String, String> values = FlatYaml.read(fileIn(worldFolder));
    if (values.isEmpty()) {
      return map;
    }
    map.setWorld(FlatYaml.unquote(values.getOrDefault("world", "")));
    Integer teamCount = FlatYaml.intValue(values, "team-count");
    int count = teamCount == null ? 0 : teamCount;
    for (int i = 0; i < count; i++) {
      String prefix = "team" + i;
      TeamZone zone = map.ensureZone(i, parseColor(values.get(prefix + "-color"), i));
      BlockPosition corner1 = corner(values, map.world(), prefix + "-corner1");
      BlockPosition corner2 = corner(values, map.world(), prefix + "-corner2");
      if (corner1 != null) {
        zone.setCorner(1, corner1);
      }
      if (corner2 != null) {
        zone.setCorner(2, corner2);
      }
      Integer spawnCount = FlatYaml.intValue(values, prefix + "-spawn-count");
      int spawns = spawnCount == null ? 0 : spawnCount;
      for (int s = 0; s < spawns; s++) {
        WorldPosition spawn = spawn(values, map.world(), prefix + "-spawn" + s);
        if (spawn != null) {
          zone.addSpawn(spawn);
        }
      }
    }
    return map;
  }

  public static void save(Path worldFolder, BattleMap map) throws IOException {
    StringBuilder builder = new StringBuilder();
    builder.append("# Sexidium battle map definition - edit with /sx admin map edit commands.\n");
    builder.append("id: ").append(FlatYaml.quote(map.id())).append('\n');
    builder.append("world: ").append(FlatYaml.quote(map.world())).append('\n');
    builder.append("team-count: ").append(map.teamCount()).append('\n');
    for (TeamZone zone : map.zones()) {
      String prefix = "team" + zone.index();
      builder.append(prefix).append("-color: ").append(FlatYaml.quote(zone.color().name())).append('\n');
      writeCorner(builder, prefix + "-corner1", zone.corner1());
      writeCorner(builder, prefix + "-corner2", zone.corner2());
      builder.append(prefix).append("-spawn-count: ").append(zone.spawns().size()).append('\n');
      int s = 0;
      for (WorldPosition spawn : zone.spawns()) {
        writeSpawn(builder, prefix + "-spawn" + s, spawn);
        s++;
      }
    }
    FlatYaml.write(fileIn(worldFolder), builder.toString());
  }

  /**
   * Loads the battle map, falling back to a one-time in-memory import of a legacy {@code sexidium-tntwar.yml}
   * (red → team 0, blue → team 1) when no {@code sexidium-battlemap.yml} exists yet. The next
   * {@code /sx admin map edit … save} writes it out in the new format.
   */
  public static BattleMap loadOrImportTntWar(Path worldFolder, String id) {
    if (exists(worldFolder) || worldFolder == null
        || !Files.isRegularFile(worldFolder.resolve(LEGACY_TNTWAR_FILE))) {
      return load(worldFolder, id);
    }
    Map<String, String> values = FlatYaml.read(worldFolder.resolve(LEGACY_TNTWAR_FILE));
    BattleMap map = new BattleMap(id, FlatYaml.unquote(values.getOrDefault("world", "")));
    importLegacyTeam(map, values, 0, TeamColor.RED, "red");
    importLegacyTeam(map, values, 1, TeamColor.BLUE, "blue");
    return map;
  }

  private static void importLegacyTeam(BattleMap map, Map<String, String> values, int index,
      TeamColor color, String legacy) {
    TeamZone zone = map.ensureZone(index, color);
    BlockPosition corner1 = corner(values, map.world(), legacy + "-corner1");
    BlockPosition corner2 = corner(values, map.world(), legacy + "-corner2");
    if (corner1 != null) {
      zone.setCorner(1, corner1);
    }
    if (corner2 != null) {
      zone.setCorner(2, corner2);
    }
    WorldPosition spawn = spawn(values, map.world(), legacy + "-spawn");
    if (spawn != null) {
      zone.addSpawn(spawn);
    }
  }

  private static TeamColor parseColor(String raw, int index) {
    if (raw != null) {
      try {
        return TeamColor.valueOf(FlatYaml.unquote(raw).toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        // fall through to the palette colour for this index
      }
    }
    return TeamColor.values()[Math.floorMod(index, TeamColor.maxTeams())];
  }

  private static void writeCorner(StringBuilder builder, String prefix, BlockPosition position) {
    if (position == null) {
      return;
    }
    builder.append(prefix).append("-x: ").append(position.blockX()).append('\n');
    builder.append(prefix).append("-y: ").append(position.blockY()).append('\n');
    builder.append(prefix).append("-z: ").append(position.blockZ()).append('\n');
  }

  private static void writeSpawn(StringBuilder builder, String prefix, WorldPosition position) {
    if (position == null) {
      return;
    }
    builder.append(prefix).append("-x: ").append(position.coordinateX()).append('\n');
    builder.append(prefix).append("-y: ").append(position.coordinateY()).append('\n');
    builder.append(prefix).append("-z: ").append(position.coordinateZ()).append('\n');
    builder.append(prefix).append("-yaw: ").append(position.yaw()).append('\n');
    builder.append(prefix).append("-pitch: ").append(position.pitch()).append('\n');
  }

  private static BlockPosition corner(Map<String, String> values, String world, String prefix) {
    Integer x = FlatYaml.intValue(values, prefix + "-x");
    Integer y = FlatYaml.intValue(values, prefix + "-y");
    Integer z = FlatYaml.intValue(values, prefix + "-z");
    if (x == null || y == null || z == null) {
      return null;
    }
    return new BlockPosition(world, x, y, z);
  }

  private static WorldPosition spawn(Map<String, String> values, String world, String prefix) {
    Double x = FlatYaml.doubleValue(values, prefix + "-x");
    Double y = FlatYaml.doubleValue(values, prefix + "-y");
    Double z = FlatYaml.doubleValue(values, prefix + "-z");
    if (x == null || y == null || z == null) {
      return null;
    }
    float yaw = (float) FlatYaml.doubleOr(values, prefix + "-yaw", 0.0);
    float pitch = (float) FlatYaml.doubleOr(values, prefix + "-pitch", 0.0);
    return new WorldPosition(world, x, y, z, yaw, pitch);
  }
}
