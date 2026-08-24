package com.sexidium.velocity.adapter;

import com.sexidium.core.platform.ConfigurationAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the same {@code config.yml} shape a backend uses, so the proxy is configured from one file
 * format rather than a second one nobody remembers.
 *
 * <p>Velocity ships no configuration API and this project's defining property is zero runtime
 * dependencies in core — vendoring snakeyaml to read a handful of keys would be the first, and it
 * would then need relocating for the same classloader reason JDBC does. So this parses the subset the
 * config actually uses: nested block mappings, {@code #} comments, scalars, and simple
 * {@code - item} sequences. It does not handle flow mappings, anchors or multi-line scalars, and it
 * says so rather than guessing.</p>
 *
 * <p>Values are flattened to dotted paths on load ({@code network.node.role}), which is exactly how
 * {@link ConfigurationAdapter} addresses them.</p>
 */
public final class YamlConfigurationAdapter implements ConfigurationAdapter {

  private final Path file;
  private final Map<String, Object> values = new HashMap<>();
  private final Map<String, List<String>> lists = new HashMap<>();

  public YamlConfigurationAdapter(Path file) {
    this.file = file;
    reload();
  }

  @Override
  public void reload() {
    values.clear();
    lists.clear();
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (IOException failed) {
      throw new IllegalStateException("Could not read " + file, failed);
    }
  }

  private void parse(List<String> lines) {
    // (indent, key) of each open parent, so a dotted path can be rebuilt at any depth.
    Deque<int[]> indents = new ArrayDeque<>();
    Deque<String> keys = new ArrayDeque<>();
    String lastPath = null;

    for (String raw : lines) {
      String line = stripComment(raw);
      if (line.isBlank()) {
        continue;
      }
      int indent = indentOf(line);
      String trimmed = line.trim();

      if (trimmed.startsWith("- ")) {
        if (lastPath != null) {
          lists.computeIfAbsent(lastPath, unused -> new ArrayList<>()).add(unquote(trimmed.substring(2).trim()));
        }
        continue;
      }

      int colon = trimmed.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = trimmed.substring(0, colon).trim();
      String value = trimmed.substring(colon + 1).trim();

      while (!indents.isEmpty() && indents.peek()[0] >= indent) {
        indents.pop();
        keys.pop();
      }

      String path = keys.isEmpty() ? key : String.join(".", reversed(keys)) + "." + key;
      lastPath = path;

      if (value.isEmpty()) {
        // A section header, or a key whose value is a following sequence.
        indents.push(new int[] {indent});
        keys.push(key);
      } else if ("[]".equals(value)) {
        lists.put(path, new ArrayList<>());
      } else {
        values.put(path, unquote(value));
      }
    }
  }

  private static List<String> reversed(Deque<String> keys) {
    List<String> out = new ArrayList<>(keys);
    java.util.Collections.reverse(out);
    return out;
  }

  /** Strips a trailing comment, but not a '#' inside quotes (MiniMessage uses <#ff5f6d>). */
  private static String stripComment(String line) {
    boolean single = false;
    boolean doubleQuote = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '\'' && !doubleQuote) {
        single = !single;
      } else if (c == '"' && !single) {
        doubleQuote = !doubleQuote;
      } else if (c == '#' && !single && !doubleQuote) {
        // Only a comment when it starts a token, so <#ff5f6d> survives.
        if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
          return line.substring(0, i);
        }
      }
    }
    return line;
  }

  private static int indentOf(String line) {
    int i = 0;
    while (i < line.length() && line.charAt(i) == ' ') {
      i++;
    }
    return i;
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("'") && value.endsWith("'"))
            || (value.startsWith("\"") && value.endsWith("\"")))) {
      return value.substring(1, value.length() - 1).replace("''", "'");
    }
    return value;
  }

  @Override
  public boolean getBoolean(String path, boolean defaultValue) {
    Object value = values.get(path);
    return value == null ? defaultValue : Boolean.parseBoolean(value.toString());
  }

  @Override
  public int getInt(String path, int defaultValue) {
    try {
      Object value = values.get(path);
      return value == null ? defaultValue : Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException notANumber) {
      return defaultValue;
    }
  }

  @Override
  public long getLong(String path, long defaultValue) {
    try {
      Object value = values.get(path);
      return value == null ? defaultValue : Long.parseLong(value.toString().trim());
    } catch (NumberFormatException notANumber) {
      return defaultValue;
    }
  }

  @Override
  public double getDouble(String path, double defaultValue) {
    try {
      Object value = values.get(path);
      return value == null ? defaultValue : Double.parseDouble(value.toString().trim());
    } catch (NumberFormatException notANumber) {
      return defaultValue;
    }
  }

  @Override
  public String getString(String path, String defaultValue) {
    Object value = values.get(path);
    return value == null ? defaultValue : value.toString();
  }

  @Override
  public List<String> getStringList(String path) {
    return lists.getOrDefault(path, List.of());
  }

  @Override
  public List<Map<String, Object>> getMapList(String path) {
    return List.of();
  }

  @Override
  public Set<String> keys(String path) {
    String prefix = path.isEmpty() ? "" : path + ".";
    Set<String> out = new LinkedHashSet<>();
    for (String key : values.keySet()) {
      if (key.startsWith(prefix)) {
        String rest = key.substring(prefix.length());
        int dot = rest.indexOf('.');
        out.add(dot < 0 ? rest : rest.substring(0, dot));
      }
    }
    return out;
  }

  @Override
  public Object get(String path) {
    return values.get(path);
  }

  @Override
  public boolean contains(String path) {
    return values.containsKey(path) || lists.containsKey(path);
  }

  @Override
  public void set(String path, Object value) {
    values.put(path, value);
  }

  /**
   * Not implemented on purpose. Writing YAML back would either drop every comment in config.yml —
   * which is where this project keeps its operational documentation — or require a round-tripping
   * serializer. The provisioner (scripts/lib/py/yamlkv.py) owns writes; the proxy only reads.
   */
  @Override
  public void save() {
    throw new UnsupportedOperationException(
        "The proxy does not write config.yml; scripts/lib/py/yamlkv.py owns writes");
  }
}
