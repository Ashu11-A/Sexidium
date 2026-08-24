package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.map.FlatYaml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads/writes a TNT-War map's base layout as {@code sexidium-tntwar.yml} living inside the map's world
 * folder. It shares the flat {@code key: value} YAML subset owned by core ({@link FlatYaml}) with every
 * other in-world settings sidecar (lobby/Combat spawns), so the format is identical on Paper and NeoForge.
 * The file name is constant ({@link #FILE_NAME}) so a cloned match world carries its own copy.
 */
public final class TntWarMapStore {
  public static final String FILE_NAME = "sexidium-tntwar.yml";

  private TntWarMapStore() {
  }

  public static Path fileIn(Path worldFolder) {
    return worldFolder.resolve(FILE_NAME);
  }

  public static TntWarMap load(Path worldFolder, String id) {
    TntWarMap map = new TntWarMap(id, "");
    Map<String, String> values = FlatYaml.read(fileIn(worldFolder));
    if (values.isEmpty()) {
      return map;
    }
    map.setWorld(FlatYaml.unquote(values.getOrDefault("world", "")));
    BlockPosition redCorner1 = corner(values, map.world(), "red-corner1");
    BlockPosition redCorner2 = corner(values, map.world(), "red-corner2");
    BlockPosition blueCorner1 = corner(values, map.world(), "blue-corner1");
    BlockPosition blueCorner2 = corner(values, map.world(), "blue-corner2");
    if (redCorner1 != null) {
      map.setCorner("red", 1, redCorner1);
    }
    if (redCorner2 != null) {
      map.setCorner("red", 2, redCorner2);
    }
    if (blueCorner1 != null) {
      map.setCorner("blue", 1, blueCorner1);
    }
    if (blueCorner2 != null) {
      map.setCorner("blue", 2, blueCorner2);
    }
    WorldPosition redSpawn = spawn(values, map.world(), "red-spawn");
    WorldPosition blueSpawn = spawn(values, map.world(), "blue-spawn");
    if (redSpawn != null) {
      map.setSpawn("red", redSpawn);
    }
    if (blueSpawn != null) {
      map.setSpawn("blue", blueSpawn);
    }
    return map;
  }

  public static void save(Path worldFolder, TntWarMap map) throws IOException {
    StringBuilder builder = new StringBuilder();
    builder.append("# TNT War map definition - edit with /sx admin map tntwar commands.\n");
    builder.append("id: ").append(FlatYaml.quote(map.id())).append('\n');
    builder.append("world: ").append(FlatYaml.quote(map.world())).append('\n');
    writeCorner(builder, "red-corner1", map.redCorner1());
    writeCorner(builder, "red-corner2", map.redCorner2());
    writeCorner(builder, "blue-corner1", map.blueCorner1());
    writeCorner(builder, "blue-corner2", map.blueCorner2());
    writeSpawn(builder, "red-spawn", map.redSpawn());
    writeSpawn(builder, "blue-spawn", map.blueSpawn());
    FlatYaml.write(fileIn(worldFolder), builder.toString());
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
