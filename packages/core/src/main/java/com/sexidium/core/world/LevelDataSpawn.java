package com.sexidium.core.world;

import com.sexidium.core.platform.model.WorldPosition;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Reads the world spawn out of a downloaded map's {@code level.dat}.
 *
 * <p>A bundled lobby map is seeded into a DIMENSION folder, and a dimension folder must not carry a
 * {@code level.dat} — {@link com.sexidium.core.world.LobbyBundle} consumers delete it right after
 * extraction. That file is the only place the map's own spawn lives, so without reading it first the
 * server falls back to vanilla's initial-spawn search, which lands players hundreds of blocks away from
 * the build (a biome probe, not the origin). This reader captures the spawn while the file is still
 * there so the caller can persist it as the lobby's spawn point.</p>
 *
 * <p>Both level.dat spawn layouts are supported: the MC 26.1+ {@code Data.spawn} compound
 * ({@code pos} int-array + {@code yaw}/{@code pitch}) and the classic {@code Data.SpawnX/Y/Z} ints with
 * {@code SpawnAngle}. Anything unreadable yields {@link Optional#empty()} — a missing spawn is never
 * fatal, the caller just keeps the server's own.</p>
 */
public final class LevelDataSpawn {
  /** Guards against a malformed file describing absurdly deep nesting. */
  private static final int MAX_DEPTH = 64;

  private LevelDataSpawn() {
  }

  /**
   * Reads the world spawn stored in {@code levelDat}, centred on the spawn block.
   *
   * @param levelDat  the map's {@code level.dat} (gzip-compressed NBT; raw NBT is also accepted)
   * @param worldName world name stamped onto the returned position
   * @return the spawn point, or empty when the file is absent, unreadable or carries no spawn
   */
  public static Optional<WorldPosition> read(Path levelDat, String worldName) {
    if (levelDat == null || !Files.isRegularFile(levelDat)) {
      return Optional.empty();
    }
    Map<String, Object> data;
    try (InputStream file = Files.newInputStream(levelDat)) {
      data = levelData(readRoot(file));
    } catch (IOException | RuntimeException exception) {
      return Optional.empty();
    }
    if (data == null) {
      return Optional.empty();
    }
    Optional<WorldPosition> modern = modernSpawn(data, worldName);
    return modern.isPresent() ? modern : legacySpawn(data, worldName);
  }

  /** The {@code Data} compound of a level.dat root (the root itself when a map stores it flat). */
  private static Map<String, Object> levelData(Object root) {
    if (!(root instanceof Map<?, ?> compound)) {
      return null;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> typed = (Map<String, Object>) compound;
    Object data = typed.get("Data");
    if (data instanceof Map<?, ?> nested) {
      @SuppressWarnings("unchecked")
      Map<String, Object> nestedTyped = (Map<String, Object>) nested;
      return nestedTyped;
    }
    return typed;
  }

  /** MC 26.1+: {@code Data.spawn = {pos: [x, y, z], yaw: f, pitch: f}}. */
  private static Optional<WorldPosition> modernSpawn(Map<String, Object> data, String worldName) {
    if (!(data.get("spawn") instanceof Map<?, ?> spawn)) {
      return Optional.empty();
    }
    int[] pos = intTriple(spawn.get("pos"));
    if (pos == null) {
      return Optional.empty();
    }
    return Optional.of(position(worldName, pos[0], pos[1], pos[2],
        floatValue(spawn.get("yaw")), floatValue(spawn.get("pitch"))));
  }

  /** Classic: {@code Data.SpawnX/SpawnY/SpawnZ} (+ optional {@code SpawnAngle}). */
  private static Optional<WorldPosition> legacySpawn(Map<String, Object> data, String worldName) {
    if (!(data.get("SpawnX") instanceof Number x)
        || !(data.get("SpawnY") instanceof Number y)
        || !(data.get("SpawnZ") instanceof Number z)) {
      return Optional.empty();
    }
    return Optional.of(position(worldName, x.intValue(), y.intValue(), z.intValue(),
        floatValue(data.get("SpawnAngle")), 0.0f));
  }

  /** Centres the position on the spawn block, so a player lands on it rather than on its corner. */
  private static WorldPosition position(String worldName, int x, int y, int z, float yaw, float pitch) {
    return new WorldPosition(worldName == null ? "" : worldName, x + 0.5, y, z + 0.5, yaw, pitch);
  }

  /** Three ints from either an int-array tag or a list of numbers; null when the shape does not match. */
  private static int[] intTriple(Object value) {
    if (value instanceof int[] array && array.length >= 3) {
      return new int[]{array[0], array[1], array[2]};
    }
    if (value instanceof List<?> list && list.size() >= 3
        && list.get(0) instanceof Number x && list.get(1) instanceof Number y && list.get(2) instanceof Number z) {
      return new int[]{x.intValue(), y.intValue(), z.intValue()};
    }
    return null;
  }

  private static float floatValue(Object value) {
    return value instanceof Number number ? number.floatValue() : 0.0f;
  }

  // ===== minimal NBT reader ======================================================================

  /** Reads the root tag of an NBT stream (gzip-compressed or raw), returning its payload. */
  private static Object readRoot(InputStream raw) throws IOException {
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(decompressed(raw)))) {
      int type = in.readUnsignedByte();
      if (type == 0) {
        return null;
      }
      in.skipNBytes(in.readUnsignedShort()); // root name, always empty in practice
      return readPayload(in, type, 0);
    }
  }

  /** Wraps the stream in a gzip decoder when it carries the gzip magic; level.dat always does. */
  private static InputStream decompressed(InputStream raw) throws IOException {
    BufferedInputStream buffered = new BufferedInputStream(raw);
    buffered.mark(2);
    int first = buffered.read();
    int second = buffered.read();
    buffered.reset();
    boolean gzip = first == 0x1f && second == 0x8b;
    return gzip ? new GZIPInputStream(buffered) : buffered;
  }

  private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
    if (depth > MAX_DEPTH) {
      throw new IOException("NBT nesting deeper than " + MAX_DEPTH);
    }
    return switch (type) {
      case 1 -> in.readByte();
      case 2 -> in.readShort();
      case 3 -> in.readInt();
      case 4 -> in.readLong();
      case 5 -> in.readFloat();
      case 6 -> in.readDouble();
      case 7 -> in.readNBytes(nonNegative(in.readInt()));
      case 8 -> in.readUTF();
      case 9 -> readList(in, depth);
      case 10 -> readCompound(in, depth);
      case 11 -> readIntArray(in);
      case 12 -> readLongArray(in);
      default -> throw new IOException("Unknown NBT tag type " + type);
    };
  }

  private static List<Object> readList(DataInputStream in, int depth) throws IOException {
    int elementType = in.readUnsignedByte();
    int length = nonNegative(in.readInt());
    List<Object> list = new ArrayList<>(Math.min(length, 1024));
    for (int index = 0; index < length; index++) {
      list.add(elementType == 0 ? null : readPayload(in, elementType, depth + 1));
    }
    return list;
  }

  private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
    Map<String, Object> compound = new HashMap<>();
    int type;
    while ((type = in.readUnsignedByte()) != 0) {
      compound.put(in.readUTF(), readPayload(in, type, depth + 1));
    }
    return compound;
  }

  private static int[] readIntArray(DataInputStream in) throws IOException {
    int[] values = new int[nonNegative(in.readInt())];
    for (int index = 0; index < values.length; index++) {
      values[index] = in.readInt();
    }
    return values;
  }

  private static long[] readLongArray(DataInputStream in) throws IOException {
    long[] values = new long[nonNegative(in.readInt())];
    for (int index = 0; index < values.length; index++) {
      values[index] = in.readLong();
    }
    return values;
  }

  /** A negative length means the file is corrupt; refuse rather than allocate nonsense. */
  private static int nonNegative(int length) throws IOException {
    if (length < 0) {
      throw new IOException("Negative NBT array length " + length);
    }
    return length;
  }
}
