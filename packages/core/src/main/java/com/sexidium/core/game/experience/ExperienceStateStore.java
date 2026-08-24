package com.sexidium.core.game.experience;

import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.model.EffectSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * File-backed persistence for an experience's volatile state, written as flat {@code .yml} INSIDE the
 * experience's own (never-deleted) world folder:
 *
 * <pre>
 *   &lt;worlds&gt;/experience/&lt;nick&gt;/&lt;map&gt;_&lt;id&gt;/sexidium/state.yml          # shared challenge state + stats
 *   &lt;worlds&gt;/experience/&lt;nick&gt;/&lt;map&gt;_&lt;id&gt;/sexidium/players/&lt;uuid&gt;.yml  # one player's position + inventory
 * </pre>
 *
 * <p>State therefore shares the world's lifecycle: it travels with a world copy and is removed when the
 * owner deletes the world. The format is the same self-contained flat YAML core already uses for NPCs
 * (no platform YAML library). Only PERSISTENT experiences have a folder under {@code experiencesSubdir}
 * — transient (temp-world) experiences have none, so every method no-ops for them, matching the old
 * "no database, no persistence" behaviour.</p>
 */
public final class ExperienceStateStore {
  private final Path experiencesSubdir;
  private final LoggerAdapter logger;

  public ExperienceStateStore(Path experiencesSubdir, LoggerAdapter logger) {
    this.experiencesSubdir = experiencesSubdir;
    this.logger = logger;
  }

  /** True when this world has a persistent folder we may store state beside. */
  public boolean persistent(String worldName) {
    Path folder = worldFolder(worldName);
    return folder != null && Files.isDirectory(folder);
  }

  // ----- shared challenge state ----------------------------------------------------------------

  public ExperienceState loadSharedState(String worldName) {
    Path file = stateDir(worldName) == null ? null : stateDir(worldName).resolve("state.yml");
    if (file == null || !Files.isRegularFile(file)) {
      return ExperienceState.empty();
    }
    return ExperienceState.fromValues(readFlat(file));
  }

  /**
   * The run's counters as the {@code challenge_state} column stores them, read from the FILE — or null
   * when this world has no state file at all.
   *
   * <p>Exists because the column is not the truth and cannot be treated as one. It is a crash net,
   * written only when a reset is about to delete the folder the real file lives in, so on a
   * long-running world it is arbitrarily stale: measured on the live server it was a day behind, short
   * by six and a half hours of playtime, and missing the day count and the blocks broken entirely.
   * Anything that copies or displays a run's statistics reads this; nothing reads the column.</p>
   *
   * <p>Null rather than {@code ""} on purpose: "there is nothing on disk to carry" and "the run has
   * genuinely recorded nothing" are different, and only the first should leave a copy's column empty
   * so the file inside it stays the only answer.</p>
   */
  public String encodedSharedState(String worldName) {
    Path dir = stateDir(worldName);
    if (dir == null || !Files.isRegularFile(dir.resolve("state.yml"))) {
      return null;
    }
    return loadSharedState(worldName).encode();
  }

  /** @return true when the counters are on disk; false when nothing was written. */
  public boolean saveSharedState(String worldName, ExperienceState state) {
    if (state == null || !persistent(worldName)) {
      return false;
    }
    Map<String, String> values = new LinkedHashMap<>(state.values());
    return writeFlat(stateDir(worldName).resolve("state.yml"),
        "# experience challenge state + stats", values);
  }

  // ----- per-player snapshot -------------------------------------------------------------------

  public boolean hasPlayerSnapshot(String worldName, UUID playerId) {
    Path file = playerFile(worldName, playerId);
    return file != null && Files.isRegularFile(file);
  }

  public PlayerSnapshot loadPlayerSnapshot(String worldName, UUID playerId, String playerName) {
    Path file = playerFile(worldName, playerId);
    if (file == null || !Files.isRegularFile(file)) {
      return null;
    }
    Map<String, String> map = readFlat(file);
    PlayerSnapshot snapshot = new PlayerSnapshot(playerId, map.getOrDefault("name", playerName), null);
    snapshot.worldName = map.get("world");
    snapshot.coordinateX = parseDouble(map.get("x"));
    snapshot.coordinateY = parseDouble(map.get("y"));
    snapshot.coordinateZ = parseDouble(map.get("z"));
    snapshot.yaw = (float) parseDouble(map.get("yaw"));
    snapshot.pitch = (float) parseDouble(map.get("pitch"));
    snapshot.gameMode = map.getOrDefault("gamemode", snapshot.gameMode);
    snapshot.health = map.containsKey("health") ? parseDouble(map.get("health")) : snapshot.health;
    snapshot.foodLevel = map.containsKey("food") ? (int) parseDouble(map.get("food")) : snapshot.foodLevel;
    snapshot.xp = map.containsKey("xp") ? (int) parseDouble(map.get("xp")) : snapshot.xp;
    snapshot.inventoryPayload = map.get("inv");
    snapshot.effects = decodeEffects(map.get("effects"));
    return snapshot;
  }

  /**
   * Writes one player's position + inventory into the world folder.
   *
   * @return true when the file on disk now holds this snapshot. The callers act on it: a leave, a
   *     disconnect and a match stop all CLEAR the live inventory once the items are known to be saved,
   *     so a write that failed must never read as one that worked.
   */
  public boolean savePlayerSnapshot(String worldName, PlayerSnapshot snapshot) {
    if (snapshot == null || !persistent(worldName)) {
      return false;
    }
    Map<String, String> map = new LinkedHashMap<>();
    map.put("name", snapshot.playerName == null ? "" : snapshot.playerName);
    map.put("world", snapshot.worldName == null ? "" : snapshot.worldName);
    map.put("x", Double.toString(snapshot.coordinateX));
    map.put("y", Double.toString(snapshot.coordinateY));
    map.put("z", Double.toString(snapshot.coordinateZ));
    map.put("yaw", Float.toString(snapshot.yaw));
    map.put("pitch", Float.toString(snapshot.pitch));
    map.put("gamemode", snapshot.gameMode == null ? "" : snapshot.gameMode);
    map.put("health", Double.toString(snapshot.health));
    map.put("food", Integer.toString(snapshot.foodLevel));
    map.put("xp", Integer.toString(snapshot.xp));
    map.put("inv", snapshot.inventoryPayload == null ? "" : snapshot.inventoryPayload);
    map.put("effects", encodeEffects(snapshot.effects));
    return writeFlat(playerFile(worldName, snapshot.playerId), "# experience player snapshot", map);
  }

  // ----- effect list codec ---------------------------------------------------------------------
  // Compact "key:amp:dur;key:amp:dur" so the whole list fits one quoted flat-YAML value. Effect keys
  // are simple lowercase paths (e.g. speed, night_vision), so ':' and ';' are safe separators.

  private static String encodeEffects(List<EffectSnapshot> effects) {
    if (effects == null || effects.isEmpty()) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (EffectSnapshot effect : effects) {
      if (effect == null || effect.effectKey() == null || effect.effectKey().isBlank()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(';');
      }
      builder.append(effect.effectKey()).append(':').append(effect.amplifier()).append(':').append(effect.durationTicks());
    }
    return builder.toString();
  }

  private static List<EffectSnapshot> decodeEffects(String encoded) {
    List<EffectSnapshot> effects = new ArrayList<>();
    if (encoded == null || encoded.isBlank()) {
      return effects;
    }
    for (String token : encoded.split(";")) {
      if (token.isBlank()) {
        continue;
      }
      String[] parts = token.split(":");
      if (parts.length < 3 || parts[0].isBlank()) {
        continue;
      }
      effects.add(new EffectSnapshot(parts[0], (int) parseDouble(parts[1]), (int) parseDouble(parts[2])));
    }
    return effects;
  }

  /** Removes a player's saved snapshot (e.g. on owner reset). */
  public void deletePlayerSnapshot(String worldName, UUID playerId) {
    Path file = playerFile(worldName, playerId);
    if (file != null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException ignored) {
        // best effort
      }
    }
  }

  /**
   * Copy every stored player snapshot from one generation of an experience to the next.
   *
   * <p>A reset carries the ONLINE participants across by saving them into the new world as they are
   * moved. Everyone else was simply left behind: their {@code players/<uuid>.yml} stayed in the folder
   * phase D then deletes, so a player who happened to be offline when somebody else died came back to
   * a world that exists, is the right one, and is empty of everything they owned. Their position, their
   * inventory and their XP were on a disk nobody kept.</p>
   *
   * <p>Copy, never move: the old folder is still the live world until the swap completes, and a reset
   * that aborts after this point must leave it exactly as it was. Existing files in the destination win
   * — an online player has already been written there with fresher state than this copy holds.</p>
   *
   * @return how many snapshots were carried, for the caller to log
   */
  /** What a carry does with the contents of the snapshots it moves. */
  public enum Carry {
    /** The run's contents travel with it: a regenerated world, the same run. */
    KEEP_CONTENTS,
    /**
     * The contents do not travel. For a mode whose whole premise is that a death costs everyone
     * everything, carrying an inventory across the wipe is the bug, not the feature.
     */
    WIPE_CONTENTS
  }

  /** Carries snapshots with their contents intact. */
  public int carryPlayerSnapshots(String fromWorldName, String toWorldName) {
    return carryPlayerSnapshots(fromWorldName, toWorldName, Carry.KEEP_CONTENTS);
  }

  public int carryPlayerSnapshots(String fromWorldName, String toWorldName, Carry carry) {
    Path source = playersDir(fromWorldName);
    Path target = playersDir(toWorldName);
    if (source == null || target == null || !Files.isDirectory(source)) {
      return 0;
    }
    int carried = 0;
    try {
      Files.createDirectories(target);
      try (var files = Files.list(source)) {
        for (Path file : files.toList()) {
          if (!file.toString().endsWith(".yml")) {
            continue;
          }
          Path destination = target.resolve(file.getFileName().toString());
          if (Files.exists(destination)) {
            continue;
          }
          // Re-written, not copied byte-for-byte. A verbatim copy carried the PREDECESSOR's world
          // name and coordinates into a world whose terrain is entirely new, so a position that was
          // fine in _r9 could be solid rock in _r10. The run's contents travel; where the player was
          // standing does not, because that place no longer exists.
          UUID playerId = playerIdOf(file);
          PlayerSnapshot snapshot = playerId == null
              ? null : loadPlayerSnapshot(fromWorldName, playerId, null);
          if (snapshot == null) {
            if (carry == Carry.WIPE_CONTENTS) {
              // A snapshot that cannot be read cannot be wiped, and copying it verbatim would carry a
              // full inventory across a wipe through the error path -- the same hole this argument
              // exists to close, just by a quieter route. The mode's contract is that nothing survives.
              logger.warning("Could not read the player snapshot '" + file.getFileName()
                  + "' while wiping '" + fromWorldName + "' into '" + toWorldName
                  + "'; it was left behind rather than copied across intact.");
              continue;
            }
            // Unparseable name or unreadable file: copying it verbatim is still better than dropping
            // the player's inventory on the floor.
            Files.copy(file, destination);
            carried++;
            continue;
          }
          snapshot.worldName = null;
          snapshot.coordinateX = 0;
          snapshot.coordinateY = 0;
          snapshot.coordinateZ = 0;
          snapshot.yaw = 0f;
          snapshot.pitch = 0f;
          if (carry == Carry.WIPE_CONTENTS) {
            // Field for field what PlayerAdapter.resetStatuses() does to somebody who IS online, so the
            // player who happened to be logged out and the player who happened to be standing there land
            // in the new world in the same state. `role`, `status` and `data` are kept on purpose: they
            // are per-player RUN state, and the world is replaced, not the run.
            snapshot.inventoryPayload = null;
            snapshot.xp = 0;
            snapshot.effects = java.util.List.of();
            snapshot.health = 20.0;
            snapshot.foodLevel = 20;
            snapshot.gameMode = com.sexidium.core.platform.model.GameModeType.SURVIVAL.name();
          }
          savePlayerSnapshot(toWorldName, snapshot);
          carried++;
        }
      }
    } catch (IOException failed) {
      // Partial is better than none: whatever was copied stays, and the ones that did not are exactly
      // the players who would have lost their run anyway.
      logger.warning("Could not carry every player snapshot from '" + fromWorldName + "' to '"
          + toWorldName + "': " + failed);
    }
    return carried;
  }

  /**
   * Point every copied player snapshot in a world folder at the world that now HOLDS it, keeping the
   * coordinates exactly as they are.
   *
   * <p>The exact opposite of {@link #carryPlayerSnapshots}, and deliberately so. A reset builds new
   * terrain, so a position that was fine in {@code _r9} can be solid rock in {@code _r10} and the
   * coordinates must not travel. A BACKUP is the same terrain, block for block — so the coordinates are
   * the one thing that must survive, and the only value in the file that is now a lie is {@code world:},
   * which still names the world the snapshot was copied FROM.</p>
   *
   * <p>Leaving it would not be cosmetic: {@code ExperiencePersistence.resolveEntryPosition} refuses a
   * saved position whose world is not this experience (that guard is what stops lobby coordinates being
   * force-applied inside a world), so every single player would be dumped at the backup's safe spawn
   * with their saved spot silently discarded.</p>
   *
   * <p>Only values that name the SOURCE experience are rewritten, and the {@code _nether}/{@code _end}
   * suffix is preserved, so a player who logged out in the Nether comes back to the copy's Nether. A
   * value naming anything else (a lobby coordinate captured mid-transition) is left alone: it is
   * already foreign, and making it point INTO the backup would teleport somebody to lobby coordinates
   * inside a world. The file's spelling of the name is preserved too — the platform resolves the world
   * by that string, and this is not the place to decide which of its two spellings is canonical.</p>
   *
   * @param worldFolder the world folder holding {@code sexidium/players/}; may be a staging folder that
   *     has not been published yet, which is exactly when this should run
   * @param fromKey the source experience's world key
   * @param toKey the copy's world key
   * @return how many snapshots were re-pointed
   */
  public int rehomePlayerSnapshots(Path worldFolder, String fromKey, String toKey) {
    if (worldFolder == null || fromKey == null || fromKey.isBlank() || toKey == null || toKey.isBlank()) {
      return 0;
    }
    Path players = worldFolder.resolve("sexidium").resolve("players");
    if (!Files.isDirectory(players)) {
      return 0;
    }
    int rehomed = 0;
    try (var files = Files.list(players)) {
      for (Path file : files.toList()) {
        if (!file.getFileName().toString().endsWith(".yml")) {
          continue;
        }
        Map<String, String> values = readFlat(file);
        String moved = rehomedWorldValue(values.get("world"), fromKey, toKey);
        if (moved == null) {
          continue;
        }
        values.put("world", moved);
        writeFlat(file, "# experience player snapshot", values);
        rehomed++;
      }
    } catch (IOException failed) {
      if (logger != null) {
        logger.warning("Could not re-point every player snapshot in '" + worldFolder + "' from '"
            + fromKey + "' to '" + toKey + "': " + failed);
      }
    }
    return rehomed;
  }

  /** {@link #rehomePlayerSnapshots(Path, String, String)} addressed by world name instead of folder. */
  public int rehomePlayerSnapshots(String worldName, String fromKey, String toKey) {
    return rehomePlayerSnapshots(worldFolder(worldName), fromKey, toKey);
  }

  /**
   * {@code value} with its {@code fromKey} swapped for {@code toKey}, or null when it does not name the
   * source experience at all.
   *
   * <p>Matched on the flattened tail, because the same world is spelled {@code experiences/<key>} by
   * this system and {@code experiences_<key>} by Bukkit, and both end up in these files. The character
   * before the key must be a separator, so a key that merely happens to be a suffix of a longer one
   * cannot match.</p>
   */
  private static String rehomedWorldValue(String value, String fromKey, String toKey) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String flat = value.replace('\\', '_').replace('/', '_').replace(':', '_');
    for (String suffix : new String[] {"",
        com.sexidium.core.world.WorldNaming.NETHER_SUFFIX,
        com.sexidium.core.world.WorldNaming.END_SUFFIX}) {
      String tail = fromKey + suffix;
      int start = flat.length() - tail.length();
      if (start < 0 || !flat.regionMatches(true, start, tail, 0, tail.length())) {
        continue;
      }
      if (start > 0 && flat.charAt(start - 1) != '_') {
        continue;
      }
      // Separator flattening is one character for one character, so the index is valid in the original.
      return value.substring(0, start) + toKey + suffix;
    }
    return null;
  }

  /** The player a snapshot file belongs to, or null when the name is not a UUID we wrote. */
  private static UUID playerIdOf(Path snapshotFile) {
    String name = snapshotFile.getFileName().toString();
    if (!name.endsWith(".yml")) {
      return null;
    }
    try {
      return UUID.fromString(name.substring(0, name.length() - 4));
    } catch (IllegalArgumentException notOurs) {
      return null;
    }
  }

  // ----- paths ---------------------------------------------------------------------------------

  private Path worldFolder(String worldName) {
    if (experiencesSubdir == null || worldName == null || worldName.isBlank()) {
      return null;
    }
    Path folder = experiencesSubdir;
    for (String segment : relativeKey(worldName).split("/")) {
      if (!segment.isBlank()) {
        folder = folder.resolve(segment);
      }
    }
    return folder;
  }

  /**
   * The experience's path relative to {@code experiencesSubdir}, e.g. {@code <nick>/<map>_<id>}. A Paper
   * persistent world registers under the runtime slash path {@code worlds/experience/<nick>/<map>_<id>};
   * strip everything up to and including the subdir segment so the store resolves the same nested folder
   * whether it is handed the stable key or the runtime slash-path name. A name with no subdir marker is
   * used verbatim (the key itself, or a bare single-segment name).
   */
  private String relativeKey(String worldName) {
    String normalized = worldName.replace('\\', '/');
    String subdir = experiencesSubdir == null || experiencesSubdir.getFileName() == null
        ? null : experiencesSubdir.getFileName().toString();
    if (subdir != null && !subdir.isBlank()) {
      String marker = "/" + subdir + "/";
      int index = normalized.indexOf(marker);
      if (index >= 0) {
        return normalized.substring(index + marker.length());
      }
      if (normalized.startsWith(subdir + "/")) {
        return normalized.substring(subdir.length() + 1);
      }
    }
    return normalized;
  }

  private Path stateDir(String worldName) {
    Path folder = worldFolder(worldName);
    return folder == null ? null : folder.resolve("sexidium");
  }

  private Path playersDir(String worldName) {
    Path dir = stateDir(worldName);
    return dir == null ? null : dir.resolve("players");
  }

  private Path playerFile(String worldName, UUID playerId) {
    Path dir = stateDir(worldName);
    return dir == null || playerId == null ? null : dir.resolve("players").resolve(playerId + ".yml");
  }

  // ----- flat YAML I/O (mirrors NpcDefinitionStore) --------------------------------------------

  /**
   * Writes a flat-YAML file WHOLE or not at all, and says which.
   *
   * <p>Two things are load-bearing and both used to be missing.</p>
   *
   * <p><b>It answers.</b> This returned void and swallowed the IOException, so a full disk, a read-only
   * mount or a permission fault was indistinguishable from a clean write all the way up to {@code
   * ExperiencePersistence.capturePlayerState}, which then told its callers "stored" unconditionally —
   * and those callers clear the player's live inventory on the strength of that word. The items went
   * nowhere and were deleted anyway.</p>
   *
   * <p><b>It is atomic.</b> {@code Files.writeString} opens with TRUNCATE_EXISTING, so a failure part
   * way through has already emptied the file that held the good snapshot: the previous save is
   * destroyed by the attempt to replace it. Writing a sibling temp file and renaming it over the target
   * means a reader ever only sees one complete version — the old one or the new one — which is the same
   * publish-by-rename rule the world copies use.</p>
   *
   * @return true when the file on disk now holds exactly these values
   */
  private boolean writeFlat(Path file, String header, Map<String, String> values) {
    if (file == null) {
      return false;
    }
    Path temporary = null;
    try {
      Files.createDirectories(file.getParent());
      StringBuilder builder = new StringBuilder();
      builder.append(header).append('\n');
      for (Map.Entry<String, String> entry : values.entrySet()) {
        builder.append(entry.getKey()).append(": ").append(quote(entry.getValue())).append('\n');
      }
      // A SIBLING, so the publish below is a rename within one directory and not a cross-device copy.
      temporary = file.resolveSibling(file.getFileName() + ".writing-"
          + Long.toHexString(System.nanoTime()));
      Files.writeString(temporary, builder.toString(), StandardCharsets.UTF_8);
      try {
        Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException | UnsupportedOperationException notAtomic) {
        // Same-directory renames are atomic everywhere we run; the fallback is for exotic filesystems.
        Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return true;
    } catch (IOException exception) {
      if (logger != null) {
        logger.warning("Failed to write experience state file " + file, exception);
      }
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The half-written temp file is named for what it is and collected by the next clean write.
        }
      }
      return false;
    }
  }

  private Map<String, String> readFlat(Path file) {
    Map<String, String> values = new LinkedHashMap<>();
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (String rawLine : lines) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        // Keys never contain ':'; the first colon is the key/value separator (the value is quoted).
        int separator = line.indexOf(':');
        if (separator < 0) {
          continue;
        }
        String key = line.substring(0, separator).strip();
        String value = unquote(line.substring(separator + 1).strip());
        if (!key.isEmpty()) {
          values.put(key, value);
        }
      }
    } catch (IOException exception) {
      if (logger != null) {
        logger.warning("Failed to read experience state file " + file, exception);
      }
    }
    return values;
  }

  private static String quote(String value) {
    String safe = value == null ? "" : value;
    return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static String unquote(String value) {
    if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
      return value == null ? "" : value;
    }
    String inner = value.substring(1, value.length() - 1);
    StringBuilder builder = new StringBuilder(inner.length());
    boolean escaped = false;
    for (int i = 0; i < inner.length(); i++) {
      char current = inner.charAt(i);
      if (escaped) {
        builder.append(current == 'n' ? '\n' : current);
        escaped = false;
      } else if (current == '\\') {
        escaped = true;
      } else {
        builder.append(current);
      }
    }
    return builder.toString();
  }

  private static double parseDouble(String value) {
    if (value == null || value.isBlank()) {
      return 0.0;
    }
    try {
      return Double.parseDouble(value.strip());
    } catch (NumberFormatException exception) {
      return 0.0;
    }
  }
}
