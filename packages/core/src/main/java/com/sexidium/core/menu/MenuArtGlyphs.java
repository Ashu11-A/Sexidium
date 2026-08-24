package com.sexidium.core.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sexidium.core.menu.MenuArt.Glyph;

/**
 * The background-glyph catalog of {@link MenuArt}: reads the {@link BackgroundCatalog} registry and
 * builds the chest-frame / per-screen glyph table (one Private-Use code point per glyph, tiled blocks
 * for the 768px sources), plus the per-row chest-def lookup and the baked-screen def list the geometry
 * constants derive from. Package-private implementation detail of {@link MenuArt}, which exposes the
 * public {@code glyphs}/{@code chestGlyph*}/{@code SCREEN_*} facade.
 */
final class MenuArtGlyphs {
  // First Private-Use code point used for glyphs; each glyph gets the next one.
  private static final char GLYPH_BASE = '\uE200';

  private MenuArtGlyphs() {
  }

  // Background glyphs are declared in the yml registry (menu/backgrounds.yml -> BackgroundCatalog): one
  // per chest size (1..6 rows, the medieval frames) and one per baked per-screen scene. Each becomes a
  // code point in GLYPH_FONT, assigned in registry order — that order is load-bearing, since
  // SexidiumResourcePack serialises the font providers in it and the pack SHA-1 must stay byte-stable
  // across JVM restarts.
  static List<Glyph> buildGlyphs() {
    List<Glyph> glyphs = new ArrayList<>();
    char codepoint = GLYPH_BASE;
    for (BackgroundCatalog.BackgroundDef def : BackgroundCatalog.all()) {
      // The 768px chest/screen backgrounds exceed Minecraft's 256px per-glyph atlas ceiling, so each is
      // TILED into a TILE_GRID² block of ≤256px glyphs (one code point per tile). The ≤256px calibration
      // overlay (kind:debug) stays a single glyph.
      boolean tiled = def.isChest() || def.isScreen();
      glyphs.add(new Glyph(def.id(), codepoint, def.height(), def.ascent(), def.leftX(),
          def.renderWidth(), def.path(), tiled));
      codepoint += tiled ? (MenuArt.TILE_GRID * MenuArt.TILE_GRID) : 1;
    }
    return List.copyOf(glyphs);
  }

  // The chest defs (kind: chest) keyed by row count, for the per-size height/geometry lookups. The
  // registry must cover rows minRows..maxRows exactly once each.
  static Map<Integer, BackgroundCatalog.BackgroundDef> chestDefsByRow(int minRows, int maxRows) {
    Map<Integer, BackgroundCatalog.BackgroundDef> byRow = new LinkedHashMap<>();
    for (BackgroundCatalog.BackgroundDef def : BackgroundCatalog.all()) {
      if (def.isChest()) {
        byRow.put(def.rows(), def);
      }
    }
    for (int rows = minRows; rows <= maxRows; rows++) {
      if (!byRow.containsKey(rows)) {
        throw new IllegalStateException("backgrounds.yml missing chest frame for " + rows + " rows");
      }
    }
    return Collections.unmodifiableMap(byRow);
  }

  // The baked per-screen scene defs (kind: screen), in registry order.
  static List<BackgroundCatalog.BackgroundDef> screenDefs() {
    List<BackgroundCatalog.BackgroundDef> defs = new ArrayList<>();
    for (BackgroundCatalog.BackgroundDef def : BackgroundCatalog.all()) {
      if (def.isScreen()) {
        defs.add(def);
      }
    }
    if (defs.isEmpty()) {
      throw new IllegalStateException("backgrounds.yml declares no baked screen backgrounds");
    }
    return List.copyOf(defs);
  }

  // Scene ids of the baked screens (the screen/<id> glyph id with the "screen/" prefix stripped).
  static String[] screenBgIds(List<BackgroundCatalog.BackgroundDef> screenDefs) {
    return screenDefs.stream().map(def -> def.id().substring("screen/".length())).toArray(String[]::new);
  }
}
