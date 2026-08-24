package com.sexidium.core.world.npc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads/writes one {@code <id>.yml} per NPC under a folder (e.g.
 * {@code plugins/Sexidium/fakeplayers/}). Uses a small, self-contained YAML subset (flat scalars plus
 * a single {@code hologram:} string list) so the format is owned by core and identical on every
 * platform — no platform YAML library is involved.
 */
public final class NpcDefinitionStore {
  private NpcDefinitionStore() {
  }

  public static List<NpcDefinition> loadAll(Path folder) {
    List<NpcDefinition> definitions = new ArrayList<>();
    if (folder == null || !Files.isDirectory(folder)) {
      return definitions;
    }
    try (var paths = Files.list(folder)) {
      paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
          .sorted()
          .forEach(path -> {
            NpcDefinition definition = read(path);
            if (definition != null) {
              definitions.add(definition);
            }
          });
    } catch (IOException ignored) {
      // Missing/unreadable folder yields an empty list; the caller logs context if needed.
    }
    return definitions;
  }

  public static NpcDefinition read(Path file) {
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      String id = null;
      String world = "lobby";
      double x = 0;
      double y = 0;
      double z = 0;
      float yaw = 0;
      float pitch = 0;
      String skin = "";
      String name = "";
      String clickCommand = "";
      boolean followPlayerHead = false;
      String minigameMode = "";
      List<String> hologram = new ArrayList<>();
      boolean inHologram = false;
      for (String rawLine : lines) {
        String line = rawLine.stripTrailing();
        if (line.isBlank() || line.stripLeading().startsWith("#")) {
          continue;
        }
        String trimmed = line.strip();
        if (inHologram && trimmed.startsWith("- ")) {
          hologram.add(unquote(trimmed.substring(2).strip()));
          continue;
        }
        inHologram = false;
        int separator = trimmed.indexOf(':');
        if (separator < 0) {
          continue;
        }
        String key = trimmed.substring(0, separator).strip().toLowerCase(Locale.ROOT);
        String value = trimmed.substring(separator + 1).strip();
        switch (key) {
          case "id" -> id = unquote(value);
          case "world" -> world = unquote(value);
          case "x" -> x = parseDouble(value);
          case "y" -> y = parseDouble(value);
          case "z" -> z = parseDouble(value);
          case "yaw" -> yaw = parseFloat(value);
          case "pitch" -> pitch = parseFloat(value);
          case "skin" -> skin = unquote(value);
          case "name" -> name = unquote(value);
          case "click-command" -> clickCommand = unquote(value);
          case "follow-player-head" -> followPlayerHead = Boolean.parseBoolean(value);
          case "minigame-mode" -> minigameMode = unquote(value);
          case "hologram" -> inHologram = true;
          default -> {
            // Unknown key — ignore for forward compatibility.
          }
        }
      }
      if (id == null || id.isBlank()) {
        id = stripExtension(file.getFileName().toString());
      }
      return new NpcDefinition(id, world, x, y, z, yaw, pitch, skin, name, clickCommand, followPlayerHead,
          hologram, minigameMode);
    } catch (IOException exception) {
      return null;
    }
  }

  public static void save(Path folder, NpcDefinition definition) throws IOException {
    Files.createDirectories(folder);
    Path file = folder.resolve(sanitize(definition.id()) + ".yml");
    StringBuilder builder = new StringBuilder();
    builder.append("id: ").append(quote(definition.id())).append('\n');
    builder.append("world: ").append(quote(definition.world())).append('\n');
    builder.append("x: ").append(definition.x()).append('\n');
    builder.append("y: ").append(definition.y()).append('\n');
    builder.append("z: ").append(definition.z()).append('\n');
    builder.append("yaw: ").append(definition.yaw()).append('\n');
    builder.append("pitch: ").append(definition.pitch()).append('\n');
    builder.append("skin: ").append(quote(definition.skin())).append('\n');
    builder.append("name: ").append(quote(definition.name())).append('\n');
    builder.append("click-command: ").append(quote(definition.clickCommand())).append('\n');
    builder.append("follow-player-head: ").append(definition.followPlayerHead()).append('\n');
    builder.append("minigame-mode: ").append(quote(definition.minigameMode())).append('\n');
    builder.append("hologram:\n");
    for (String hologramLine : definition.hologram()) {
      builder.append("  - ").append(quote(hologramLine)).append('\n');
    }
    Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
  }

  public static boolean delete(Path folder, String id) {
    try {
      return Files.deleteIfExists(folder.resolve(sanitize(id) + ".yml"));
    } catch (IOException exception) {
      return false;
    }
  }

  public static String sanitize(String id) {
    if (id == null || id.isBlank()) {
      return "npc";
    }
    String cleaned = id.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    return cleaned.isBlank() ? "npc" : cleaned;
  }

  private static String stripExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static String quote(String value) {
    String safe = value == null ? "" : value;
    return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String unquote(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.strip();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      String inner = trimmed.substring(1, trimmed.length() - 1);
      return inner.replace("\\\"", "\"").replace("\\\\", "\\");
    }
    return trimmed;
  }

  private static double parseDouble(String value) {
    try {
      return Double.parseDouble(unquote(value));
    } catch (NumberFormatException exception) {
      return 0.0;
    }
  }

  private static float parseFloat(String value) {
    try {
      return Float.parseFloat(unquote(value));
    } catch (NumberFormatException exception) {
      return 0.0f;
    }
  }
}
