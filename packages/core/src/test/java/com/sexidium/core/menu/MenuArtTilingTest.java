package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Guards the background TILING layer — the workaround for Minecraft's 256×256 per-glyph font-atlas
 * ceiling (a single &gt;256px glyph cell renders blank). Each 768px chest/screen background is split into a
 * {@link MenuArt#TILE_GRID}×{@code TILE_GRID} grid of ≤256px font tiles, reassembled on screen by the
 * title-trick. This asserts the offline-verifiable invariants: code-point allocation, exact (gapless,
 * non-overlapping) on-screen tiling, cursor-neutral per-tile shifts, and that the committed row-strip PNGs
 * exist at the expected ≤256px cell size. (The one thing only an in-client check can confirm — Minecraft's
 * exact per-glyph advance, i.e. {@link MenuArt#TILE_ADVANCE} — is documented on that constant.)
 */
class MenuArtTilingTest {

  @Test
  void everyChestAndScreenGlyphIsTiledAndCalibrationIsNot() {
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      boolean expectedTiled = !MenuArt.CALIBRATION_GLYPH_ID.equals(glyph.id());
      assertEquals(expectedTiled, glyph.tiled(), glyph.id() + " tiled flag");
      if (glyph.tiled()) {
        // tileRows() is the emitter view: always TILE_GRID rows × TILE_GRID code points (incl. transparent
        // cells). tiles() is the title-trick view: fully-transparent tiles are SKIPPED, so 1..GRID² remain.
        assertEquals(MenuArt.TILE_GRID, MenuArt.tileRows(glyph).size(), glyph.id() + " row count");
        int max = MenuArt.TILE_GRID * MenuArt.TILE_GRID;
        int n = MenuArt.tiles(glyph).size();
        assertTrue(n > 0 && n <= max, glyph.id() + " emits 1.." + max + " tiles (was " + n + ")");
      } else {
        assertTrue(MenuArt.tiles(glyph).isEmpty(), glyph.id() + " non-tiled has no tiles");
      }
    }
  }

  @Test
  void allGlyphAndTileCodePointsAreUniqueInsidePuaAndClearOfTheSentinel() {
    Set<Character> seen = new HashSet<>();
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      List<MenuArt.Tile> tiles = MenuArt.tiles(glyph);
      // A tiled glyph renders via its tiles; a non-tiled glyph renders its own code point.
      List<Character> codepoints = tiles.isEmpty()
          ? List.of(glyph.codepoint())
          : tiles.stream().map(MenuArt.Tile::codepoint).toList();
      for (char cp : codepoints) {
        assertTrue(seen.add(cp), "duplicate glyph code point U+" + Integer.toHexString(cp));
        assertTrue(cp >= '\uE000' && cp <= '\uF8FF', "code point U+" + Integer.toHexString(cp)
            + " must be in the Private Use Area");
        assertTrue(cp != MenuSentinel.MARKER, "tile code point must not collide with the menu sentinel");
      }
    }
  }

  @Test
  void emittedTilesArePlacedOnTheGridCursorNeutralWithRealAdvances() {
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      if (!glyph.tiled()) {
        continue;
      }
      int grid = MenuArt.TILE_GRID;
      int tile = glyph.height() / grid;
      // The emitter view: TILE_GRID row providers, each a full row of code points, ascent stepping down
      // exactly one tile-height per row (this is what stacks the rows seamlessly).
      List<MenuArt.TileRow> rows = MenuArt.tileRows(glyph);
      assertEquals(grid, rows.size(), glyph.id() + " row count");
      for (int r = 0; r < grid; r++) {
        assertEquals(glyph.ascent() - r * tile, rows.get(r).ascent(), glyph.id() + " row " + r + " ascent");
        assertEquals(grid, rows.get(r).codepoints().length, glyph.id() + " row " + r + " cell count");
      }
      // The title-trick view: each emitted (non-transparent) tile sits on the on-screen tile grid, carries a
      // real content-derived advance (1.. tile+1), and is cursor-neutral — shift in, advance, shift back == 0
      // — so successive tiles neither drift nor gap regardless of how much of each cell is opaque.
      for (MenuArt.Tile t : MenuArt.tiles(glyph)) {
        int off = t.leftX() - glyph.leftX();
        assertTrue(off >= 0 && off % tile == 0 && off / tile < grid,
            glyph.id() + " tile leftX off-grid (" + t.leftX() + ")");
        assertTrue(t.renderWidth() > 0 && t.renderWidth() <= tile + 1,
            glyph.id() + " tile advance out of range (" + t.renderWidth() + ")");
        assertEquals(0, MenuArt.tileFrameShift(t.leftX()) + t.renderWidth()
            + MenuArt.tileReturnShift(t.leftX(), t.renderWidth()),
            glyph.id() + " tile must be cursor-neutral");
      }
      assertEquals(glyph.height(), grid * tile, glyph.id() + " grid must tile the on-screen cell exactly");
    }
  }

  @Test
  void committedRowStripsExistAtTheExpectedCellSize() {
    File assets = assetsDir();
    assumeTrue(assets != null, "assets/ not found from the test working dir; skipping strip-file check");
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      if (!glyph.tiled()) {
        continue;
      }
      // The strip cell is in SOURCE px (768/grid = 192), not on-screen px — the committed whole-file source
      // is 768² and is sliced into a TILE_GRID-wide 1×N strip of square cells.
      int sourceCell = read(new File(assets, glyph.texturePath())).getHeight() / MenuArt.TILE_GRID;
      for (MenuArt.TileRow row : MenuArt.tileRows(glyph)) {
        File png = new File(assets, row.texturePath());
        assertTrue(png.isFile() && png.length() > 0, "missing committed strip " + png);
        BufferedImage image = read(png);
        int cell = image.getHeight();                       // a TILE_GRID-wide 1×N strip → square cells
        assertEquals(sourceCell, cell, row.texturePath() + " strip cell height (source px)");
        assertEquals(cell * MenuArt.TILE_GRID, image.getWidth(), row.texturePath() + " strip width");
        assertTrue(cell <= 256, row.texturePath() + " glyph cell must be <=256px (font-atlas ceiling)");
      }
    }
  }

  private static File assetsDir() {
    File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    while (dir != null) {
      File candidate = new File(dir, "assets");
      if (new File(candidate, "ui/chest/chest_6.row0.png").isFile()) {
        return candidate;
      }
      dir = dir.getParentFile();
    }
    return null;
  }

  private static BufferedImage read(File file) {
    try {
      return ImageIO.read(file);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }
}
