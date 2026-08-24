package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Asserts the repo's committed menu-art ships a real, non-empty PNG for <i>every</i> declared
 * {@link MenuArt} glyph and icon — the on-disk counterpart to {@link MenuArtCoverageTest} (which guards
 * the id tables) and {@code SexidiumResourcePackTest} (which proves the generated zip always has an entry
 * per id, via a placeholder fallback). Without this test a deleted/renamed PNG under
 * {@code assets/} would silently degrade to a generated placeholder with a green build; here it
 * is a red one.
 *
 * <p>Locates {@code assets/} by walking up from the test working directory. If it is not present
 * (e.g. an asset-less CI checkout that only assembles jars) the test self-skips rather than failing, so
 * it is a guard when the assets exist and a no-op when they do not.</p>
 */
class MenuArtAssetsTest {

  // The repo `assets/` dir: button icons live under assets/item/**, chest-frame backgrounds under
  // assets/ui/chest/**. Glyph/icon texturePaths are pack-relative (item/.., ui/chest/..), so both
  // resolve against this single root.
  private static File assetsDir() {
    File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    while (dir != null) {
      File candidate = new File(dir, "assets");
      if (new File(candidate, "item").isDirectory() && new File(candidate, "ui/chest").isDirectory()) {
        return candidate;
      }
      dir = dir.getParentFile();
    }
    return null;
  }

  @Test
  void everyDeclaredArtIdHasACommittedPng() {
    File assets = assetsDir();
    assumeTrue(assets != null, "assets/ not found from the test working dir; skipping on-disk check");

    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      // The calibration overlay is generated procedurally at pack-build time, so it ships no committed PNG.
      if (glyph.id().equals(MenuArt.CALIBRATION_GLYPH_ID)) {
        continue;
      }
      // chest frames at assets/ui/chest/<...> — glyph.texturePath() is "ui/chest/chest_<rows>.png".
      assertNonEmptyPng(new File(assets, glyph.texturePath()), "background glyph '" + glyph.id() + "'");
    }
    for (MenuArt.IconModel icon : MenuArt.icons()) {
      assertNonEmptyPng(iconPng(assets, icon.texturePath()), "icon '" + icon.id() + "'");
    }
    File root = assets;

    // The pack also ships the FULL assets/icons set as bare textures (no model) — verify every committed
    // sprite on disk, not just the modeled subset, so a truncated import is a red build, not a
    // half-empty pack. (At least one purely-extra section must be present, e.g. elo_ranks.)
    File itemRoot = new File(root, "item");
    assertTrue(new File(itemRoot, "elo_ranks").isDirectory(),
        "extra icon sections (e.g. elo_ranks) must ship on disk, not just the modeled icons");
    // Legacy sheet sections under assets/item, plus the minigame/experience/ui sets under assets/icons.
    assertAllPngNonEmpty(itemRoot);
    assertAllPngNonEmpty(new File(root, "icons"));
  }

  private static void assertAllPngNonEmpty(File dir) {
    if (!dir.isDirectory()) {
      return;
    }
    try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(dir.toPath())) {
      paths.filter(java.nio.file.Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".png"))
          .forEach(path -> assertNonEmptyPng(path.toFile(), "bundled sprite '" + path.getFileName() + "'"));
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  /**
   * Every background-glyph texture is a square {@code SCREEN_TEXTURE_HEIGHT}px (768 = 3× the 256 GUI cell)
   * font canvas — the source resolution baked by {@code art.py bake-medieval} (chest frames) and
   * {@link com.sexidium.core.menu.scene.bake.SceneBaker} (screens). A cropped/oversized canvas loses the
   * GUI-art anchoring (or, below 256, the sharpness). This guards the SOURCE canvas size for both bakers.
   *
   * <p>This 768px file is the slice SOURCE, not a shipped glyph: a single &gt;256px glyph cannot stitch
   * into Minecraft's 256×256 font atlas (renders blank), so {@code art.py tile-backgrounds} splits it into
   * a {@code TILE_GRID}×{@code TILE_GRID} grid of ≤256px row-strip glyphs that the pack actually ships
   * (guarded by {@link MenuArtTilingTest}). Keeping the source at exactly 768px keeps the tiling exact.</p>
   */
  @Test
  void everyGlyphTextureIsTheExpectedFontCanvasSize() {
    int cell = com.sexidium.core.menu.scene.bake.SceneBaker.SCREEN_TEXTURE_HEIGHT;
    File assets = assetsDir();
    assumeTrue(assets != null, "assets/ not found from the test working dir; skipping atlas-size check");
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      if (glyph.id().equals(MenuArt.CALIBRATION_GLYPH_ID)) {
        continue; // procedural overlay — no committed PNG (the generator emits its grid directly)
      }
      File png = new File(assets, glyph.texturePath());
      assumeTrue(png.isFile() && png.length() > 0, "missing glyph png " + png); // presence is the other test
      BufferedImage image = readPng(png);
      assertEquals(cell, image.getWidth(), "glyph '" + glyph.id() + "' texture width");
      assertEquals(cell, image.getHeight(), "glyph '" + glyph.id() + "' texture height");
    }
  }

  // An icon texturePath is pack-relative "item/<section>/<name>.png". Legacy sheet sections live on disk at
  // assets/item/...; the minigames/experiences/ui sets (incl. *_disabled) ship straight from assets/icons/...
  // (the build assembly maps both onto pack path item/...). Resolve assets/item first, else assets/icons.
  private static File iconPng(File assets, String texturePath) {
    File inItem = new File(assets, texturePath);
    if (inItem.isFile()) {
      return inItem;
    }
    return new File(assets, texturePath.replaceFirst("^item/", "icons/"));
  }

  private static BufferedImage readPng(File file) {
    try {
      BufferedImage image = ImageIO.read(file);
      if (image == null) {
        throw new UncheckedIOException(new IOException("not a readable PNG: " + file));
      }
      return image;
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void assertNonEmptyPng(File file, String label) {
    assertTrue(file.isFile() && file.length() > 0,
        "missing/empty committed PNG for " + label + " at " + file
            + " — re-run scripts/art.py gen-menu-art");
  }
}
