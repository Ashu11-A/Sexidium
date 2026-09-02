package com.sexidium.core.world.map;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single flat {@code key: value} YAML subset shared by every in-world settings sidecar Sexidium
 * writes ({@code sexidium-tntwar.yml}, {@code sexidium-combat.yml}, {@code sexidium-lobby.yml}, …). Core
 * deliberately owns this tiny reader/writer instead of pulling a platform YAML library into the
 * platform-agnostic module, so the file format is byte-identical wherever core runs. It is the unify
 * primitive behind {@link SpawnPointStore} and {@code TntWarMapStore}: one place that parses lines into a
 * {@code key -> value} map and quotes/parses scalar values, so the spawn/map stores never re-implement it.
 */
public final class FlatYaml {
  private FlatYaml() {
  }

  /**
   * Reads {@code file} into a lower-cased {@code key -> raw-value} map, skipping blank/comment lines and
   * any line without a {@code :} separator. Returns an empty map when the file is absent or unreadable, so
   * callers treat a missing sidecar as "nothing configured" rather than an error.
   */
  public static Map<String, String> read(Path file) {
    Map<String, String> values = new HashMap<>();
    if (file == null || !Files.isRegularFile(file)) {
      return values;
    }
    try {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (String rawLine : lines) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
          continue;
        }
        int separator = line.indexOf(':');
        if (separator < 0) {
          continue;
        }
        values.put(line.substring(0, separator).strip().toLowerCase(Locale.ROOT),
            line.substring(separator + 1).strip());
      }
    } catch (IOException exception) {
      return values;
    }
    return values;
  }

  /** Writes {@code content} to {@code file}, creating parent folders first. */
  public static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  public static Integer intValue(Map<String, String> values, String key) {
    Double value = doubleValue(values, key);
    return value == null ? null : (int) Math.round(value);
  }

  public static Double doubleValue(Map<String, String> values, String key) {
    String raw = values.get(key);
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Double.parseDouble(unquote(raw));
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  /** Reads {@code key}, defaulting to {@code fallback} when missing/blank/non-numeric. */
  public static double doubleOr(Map<String, String> values, String key, double fallback) {
    Double value = doubleValue(values, key);
    return value == null ? fallback : value;
  }

  public static String quote(String value) {
    String safe = value == null ? "" : value;
    return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  public static String unquote(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.strip();
    if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
      return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    }
    return trimmed;
  }
}
