package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Guards the chest-frame background layer: there is exactly one glyph per chest size (1..6 rows), each
 * pointing at {@code ui/chest/chest_<rows>.png}, and — crucially — each committed PNG is the square
 * {@code SCREEN_TEXTURE_HEIGHT}px font canvas (768 = 3× the 256 GUI cell) with registry metrics that place
 * the first slot at the vanilla GUI coordinate {@code (8,18)}. Slot offsets are measured in GUI px and
 * scaled onto the source by {@code scale = canvas/256}; the {@code height}/{@code ascent} metrics are
 * ON-SCREEN GUI px (unchanged by the source resolution). A re-import that crops the canvas or shifts a
 * frame's placement without updating the registry is then a red build, not a silently shifted GUI.
 */
class MenuArtChestTest {

  @Test
  void oneGlyphPerChestSizePointingAtItsFrame() {
    // Six size-keyed chest frames, plus any per-screen baked backgrounds (MenuArt.SCREEN_BG_IDS).
    long chestGlyphs = MenuArt.glyphs().stream().filter(g -> g.id().startsWith("chest_")).count();
    assertEquals(6, chestGlyphs, "one background glyph per chest size (1..6 rows)");
    for (int rows = 1; rows <= 6; rows++) {
      MenuArt.Glyph glyph = MenuArt.chestGlyph(rows);
      assertNotNull(glyph, "chest glyph for " + rows + " rows");
      assertEquals("chest_" + rows, glyph.id());
      assertEquals("ui/chest/chest_" + rows + ".png", glyph.texturePath());
      assertEquals(MenuArt.chestGlyphHeight(rows), glyph.height());
      assertEquals(MenuArt.chestGlyphAscent(rows), glyph.ascent());
    }
  }

  @Test
  void rowCountIsClampedToOneThroughSix() {
    assertEquals(MenuArt.chestGlyphId(1), MenuArt.chestGlyphId(0));
    assertEquals(MenuArt.chestGlyphId(1), MenuArt.chestGlyphId(-5));
    assertEquals(MenuArt.chestGlyphId(6), MenuArt.chestGlyphId(9));
    assertEquals(MenuArt.chestGlyphHeight(1), MenuArt.chestGlyphHeight(0));
    assertEquals(MenuArt.chestGlyphHeight(6), MenuArt.chestGlyphHeight(99));
    assertEquals(MenuArt.chestGlyphAscent(1), MenuArt.chestGlyphAscent(0));
    assertEquals(MenuArt.chestGlyphAscent(6), MenuArt.chestGlyphAscent(99));
  }

  @Test
  void committedFramesKeepOriginalCanvasAndAlignTheSlotGrid() {
    File chestDir = chestDir();
    assumeTrue(chestDir != null, "assets/ui/chest not found from the test working dir; skipping aspect check");
    for (int rows = 1; rows <= 6; rows++) {
      File png = new File(chestDir, "chest_" + rows + ".png");
      assertTrue(png.isFile() && png.length() > 0, "missing committed frame " + png);
      BufferedImage image = read(png);
      int cell = com.sexidium.core.menu.scene.bake.SceneBaker.SCREEN_TEXTURE_HEIGHT;
      assertEquals(cell, image.getWidth(), "chest_" + rows + ".png width");
      assertEquals(cell, image.getHeight(), "chest_" + rows + ".png height");
      int scale = cell / 256; // source px per GUI px (the source is baked at scale× the 256 GUI cell)

      int[] bbox = alphaBounds(image);
      MenuArt.Glyph glyph = MenuArt.chestGlyph(rows);
      assertEquals(MenuArt.CHEST_FRAME_LEFT_X, glyph.leftX(), "chest visible left_x");
      assertEquals(MenuArt.CHEST_FRAME_RENDER_WIDTH, glyph.renderWidth(), "chest glyph text advance");

      // The first slot sits 18 GUI px right / 28 GUI px down of the frame's opaque top-left — i.e.
      // (18*scale, 28*scale) source px. Convert back to GUI px (/scale) before applying the on-screen
      // left_x/ascent metrics, which place it at the vanilla slot (8,18).
      int sourceSlotX = bbox[0] + 18 * scale;
      int sourceSlotY = bbox[1] + 28 * scale;
      int canvasTopY = 13 - glyph.ascent();
      assertEquals(8, glyph.leftX() + sourceSlotX / scale, "chest_" + rows + " slot x alignment");
      assertEquals(18, canvasTopY + sourceSlotY / scale, "chest_" + rows + " slot y alignment");
    }
  }

  @Test
  void hubOverlaysShareTheChestFrameGeometry() {
    File assets = assetsDir();
    assumeTrue(assets != null, "assets/ not found from the test working dir; skipping screen canvas check");
    // The baked hub screens are TRANSPARENT card overlays drawn ON TOP of the chest_6 frame, so they must
    // carry the exact same glyph geometry as that frame to stack 1:1 (each card lands on a painted slot cell).
    MenuArt.Glyph frame = MenuArt.chestGlyph(6); // chest_6 — the six-row frame the hub overlays sit on
    for (String id : MenuArt.SCREEN_BG_IDS) {
      MenuArt.Glyph glyph = MenuArt.glyph(MenuArt.screenGlyphId(id));
      assertNotNull(glyph, "screen glyph for " + id);
      assertEquals(256, glyph.height(), id + " glyph ON-SCREEN render height (unchanged by source resolution)");
      assertEquals(frame.ascent(), glyph.ascent(), id + " glyph ascent matches the chest frame");
      assertEquals(frame.leftX(), glyph.leftX(), id + " glyph left_x matches the chest frame");
      assertEquals(frame.renderWidth(), glyph.renderWidth(), id + " glyph render_width matches the chest frame");

      BufferedImage image = read(new File(assets, glyph.texturePath()));
      int cell = com.sexidium.core.menu.scene.bake.SceneBaker.SCREEN_TEXTURE_HEIGHT;
      assertEquals(cell, image.getWidth(), glyph.id() + " width");
      assertEquals(cell, image.getHeight(), glyph.id() + " height");
      int scale = cell / 256; // source px per GUI px
      // A transparent overlay: it has fully see-through pixels (NOT an opaque board) plus visible card art,
      // and every drawn pixel sits within the chest_6 frame's content region (GUI 198×237, scaled to source).
      assertTrue(hasTransparentPixel(image), glyph.id() + " must be a transparent overlay, not an opaque board");
      int[] bbox = alphaBounds(image);
      assertTrue(bbox[0] >= 0 && bbox[1] >= 0 && bbox[2] <= 198 * scale && bbox[3] <= 237 * scale,
          glyph.id() + " content must sit within the chest_6 frame region (was "
              + java.util.Arrays.toString(bbox) + ", limit " + (198 * scale) + "×" + (237 * scale) + ")");
    }
  }

  private static boolean hasTransparentPixel(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) == 0) {
          return true;
        }
      }
    }
    return false;
  }

  private static int[] alphaBounds(BufferedImage image) {
    int minX = image.getWidth();
    int minY = image.getHeight();
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) != 0) {
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
        }
      }
    }
    assertTrue(maxX >= minX && maxY >= minY, "frame image must contain non-transparent pixels");
    return new int[] {minX, minY, maxX + 1, maxY + 1};
  }

  private static File chestDir() {
    File assets = assetsDir();
    return assets == null ? null : new File(assets, "ui/chest");
  }

  private static File assetsDir() {
    File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    while (dir != null) {
      File candidate = new File(dir, "assets");
      if (new File(candidate, "ui/chest/chest_6.png").isFile()
          && new File(candidate, "ui/screens/main-hub.png").isFile()) {
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
