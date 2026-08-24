package com.sexidium.core.menu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the background-glyph registry from {@code menu/backgrounds.yml} on the classpath — the
 * config-driven half of the menu-art system. {@link MenuArt} reads {@link #all()} to build its
 * background-glyph table and {@code SexidiumResourcePack} emits one font provider per entry, so adding a
 * GUI background (a new chest size or a baked screen) is a yml edit, not a code change.
 *
 * <p>The file reuses the ItemsAdder/Nexo {@code font_images} shape (id → {@code path}/{@code y_position}/
 * {@code permission}) and extends each entry with the render geometry the native title-trick needs
 * ({@code height}, {@code ascent}, {@code left_x}, {@code render_width}). The parse preserves declaration
 * order (a {@link LinkedHashMap}); {@link MenuArt} assigns glyph code points in that order, so the order
 * here is load-bearing for the resource pack's byte-stable SHA-1.</p>
 *
 * <p>Parsing is a tiny dependency-free YAML subset (nested 2-space-indented mappings of scalar values;
 * blank lines and {@code #} comment lines ignored) — enough for this fully-owned file, so {@code core}
 * keeps its zero-runtime-dependency footprint rather than pulling in SnakeYAML.</p>
 */
public final class BackgroundCatalog {

  /** Classpath resource the registry is read from. */
  public static final String RESOURCE = "/menu/backgrounds.yml";

  /**
   * One background glyph: a chest frame (one per row count) or a baked per-screen scene. {@code rows} is
   * meaningful only for {@code kind == "chest"} (0 for screens).
   */
  public record BackgroundDef(String id, String path, String kind, int rows, int height, int ascent,
      int leftX, int renderWidth) {
    public boolean isChest() {
      return "chest".equals(kind);
    }

    public boolean isScreen() {
      return "screen".equals(kind);
    }
  }

  /**
   * One bitmap font sliced from a medieval typography sheet: the in-game font key, the pack-relative
   * texture dir its {@code char_<X>.png} glyphs live in, the characters it covers (uppercase Latin only),
   * the GUI-px render {@code height}/{@code ascent}, and the space {@code advance}.
   */
  public record FontDef(String id, String font, String dir, String chars, int height, int ascent,
      int spaceAdvance) {
    /** The pack-relative texture path for a character, e.g. {@code item/font_title/char_A.png}. */
    public String texturePath(char c) {
      return dir + "/char_" + c + ".png";
    }
  }

  private static final Map<String, Object> ROOT = loadRoot(RESOURCE);
  private static final List<BackgroundDef> DEFS = backgrounds();
  private static final List<FontDef> FONTS = fonts();

  private BackgroundCatalog() {
  }

  /** Every background glyph, in declaration order (chest frames first, then baked screens). */
  public static List<BackgroundDef> all() {
    return DEFS;
  }

  /** Every bitmap font declared in the registry, in declaration order. */
  public static List<FontDef> fontsAll() {
    return FONTS;
  }

  private static Map<String, Object> loadRoot(String resource) {
    try (InputStream in = BackgroundCatalog.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing background registry resource " + resource);
      }
      return parse(in);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read " + resource, exception);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(String name, boolean required) {
    Object section = ROOT.get(name);
    if (section instanceof Map) {
      return (Map<String, Object>) section;
    }
    if (required) {
      throw new IllegalStateException(RESOURCE + " has no '" + name + "' mapping");
    }
    return Map.of();
  }

  @SuppressWarnings("unchecked")
  private static List<BackgroundDef> backgrounds() {
    List<BackgroundDef> defs = new ArrayList<>();
    for (Map.Entry<String, Object> entry : section("font_images", true).entrySet()) {
      if (!(entry.getValue() instanceof Map)) {
        throw new IllegalStateException("font_images." + entry.getKey() + " is not a mapping");
      }
      Map<String, Object> f = (Map<String, Object>) entry.getValue();
      String id = entry.getKey();
      String kind = str(f, id, "kind");
      defs.add(new BackgroundDef(id, str(f, id, "path"), kind,
          "chest".equals(kind) ? intVal(f, id, "rows") : 0,
          intVal(f, id, "height"), intVal(f, id, "ascent"),
          intVal(f, id, "left_x"), intVal(f, id, "render_width")));
    }
    if (defs.isEmpty()) {
      throw new IllegalStateException(RESOURCE + " declared no background glyphs");
    }
    return List.copyOf(defs);
  }

  @SuppressWarnings("unchecked")
  private static List<FontDef> fonts() {
    List<FontDef> defs = new ArrayList<>();
    for (Map.Entry<String, Object> entry : section("fonts", false).entrySet()) {
      if (!(entry.getValue() instanceof Map)) {
        throw new IllegalStateException("fonts." + entry.getKey() + " is not a mapping");
      }
      Map<String, Object> f = (Map<String, Object>) entry.getValue();
      String id = entry.getKey();
      defs.add(new FontDef(id, str(f, id, "font"), str(f, id, "dir"), str(f, id, "chars"),
          intVal(f, id, "height"), intVal(f, id, "ascent"), intVal(f, id, "space")));
    }
    return List.copyOf(defs);
  }

  private static String str(Map<String, Object> fields, String id, String key) {
    Object value = fields.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new IllegalStateException("font_images." + id + " missing string field '" + key + "'");
    }
    return s;
  }

  private static int intVal(Map<String, Object> fields, String id, String key) {
    String value = str(fields, id, key);
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("font_images." + id + " field '" + key + "' is not an int: " + value);
    }
  }

  // ----- minimal YAML-subset parser -----------------------------------------------------------
  // Nested mappings only (no sequences / no inline collections), 2-space indentation, scalar values
  // kept as trimmed strings. Blank lines and whole-line `#` comments are skipped. Sufficient for this
  // fully-owned registry file; not a general YAML implementation.
  private static Map<String, Object> parse(InputStream in) throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    Deque<int[]> indents = new ArrayDeque<>(); // stack of open-mapping indents (parallel to maps)
    Deque<Map<String, Object>> maps = new ArrayDeque<>();
    indents.push(new int[] {-1});
    maps.push(root);
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int indent = line.length() - line.stripLeading().length();
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
          continue; // not a key: value line — ignore defensively
        }
        String key = trimmed.substring(0, colon).trim();
        String value = trimmed.substring(colon + 1).trim();
        while (indents.peek()[0] >= indent) {
          indents.pop();
          maps.pop();
        }
        Map<String, Object> parent = maps.peek();
        if (value.isEmpty()) {
          Map<String, Object> child = new LinkedHashMap<>();
          parent.put(key, child);
          indents.push(new int[] {indent});
          maps.push(child);
        } else {
          parent.put(key, value);
        }
      }
    }
    return root;
  }
}
