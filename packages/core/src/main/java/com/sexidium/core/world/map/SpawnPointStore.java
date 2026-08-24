package com.sexidium.core.world.map;

import com.sexidium.core.platform.model.WorldPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Reads/writes a {@link SpawnPoints} list as a flat {@code sexidium-<key>.yml} sidecar living inside a
 * map's world folder, on top of the shared {@link FlatYaml} primitive. One store type serves every mode
 * whose only per-map setting is "where do players spawn" — the lobby and the Combat arena maps — so the
 * spawn-capture {@code /sx} commands and the in-game placement read/write through exactly one format.
 *
 * <p>The file name is the constant {@code sexidium-<key>.yml} (e.g. {@code sexidium-combat.yml}), so a
 * cloned match world carries its own copy and the lobby keeps its spawns beside its world data.</p>
 */
public final class SpawnPointStore {
  /** {@code sexidium-lobby.yml} in the persistent lobby world folder. */
  public static final String LOBBY = "lobby";
  /** {@code sexidium-combat.yml} in each Combat arena map folder. */
  public static final String COMBAT = "combat";

  private final String fileName;

  public SpawnPointStore(String key) {
    this.fileName = "sexidium-" + key + ".yml";
  }

  public Path fileIn(Path worldFolder) {
    return worldFolder.resolve(fileName);
  }

  /** Loads the spawn list for {@code worldName} from {@code worldFolder}; an absent file yields an empty list. */
  public SpawnPoints load(Path worldFolder, String worldName) {
    SpawnPoints points = new SpawnPoints(worldName);
    Map<String, String> values = FlatYaml.read(fileIn(worldFolder));
    if (values.isEmpty()) {
      return points;
    }
    String storedWorld = FlatYaml.unquote(values.getOrDefault("world", ""));
    if (!storedWorld.isBlank()) {
      points.setWorld(storedWorld);
    }
    Integer count = FlatYaml.intValue(values, "spawn-count");
    int total = count == null ? 0 : Math.max(0, count);
    for (int index = 1; index <= total; index++) {
      WorldPosition spawn = readSpawn(values, points.world(), "spawn-" + index);
      if (spawn != null) {
        points.add(spawn);
      }
    }
    return points;
  }

  public void save(Path worldFolder, SpawnPoints points) throws IOException {
    StringBuilder builder = new StringBuilder();
    builder.append("# Sexidium ").append(fileName.replace("sexidium-", "").replace(".yml", ""))
        .append(" spawn points - edit with the in-game admin commands.\n");
    builder.append("world: ").append(FlatYaml.quote(points.world())).append('\n');
    builder.append("spawn-count: ").append(points.size()).append('\n');
    int index = 1;
    for (WorldPosition spawn : points.all()) {
      writeSpawn(builder, "spawn-" + index++, spawn);
    }
    FlatYaml.write(fileIn(worldFolder), builder.toString());
  }

  private static WorldPosition readSpawn(Map<String, String> values, String world, String prefix) {
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
}
