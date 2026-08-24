package com.sexidium.core.menu.scene;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * Wiring helper that builds a fully-configured {@link SceneRenderer} from the repo's committed art under
 * the {@code assets/} root: the {@code item/} sprite tree (icons), the medieval window frame, the
 * {@code item/font_title} display caps, and a logical body font. One place so the baker, the tests, and any
 * future preview tool render with identical fonts/atlas — i.e. the offline PNG always matches what the
 * in-game compiler targets.
 */
public final class SceneAssets {

  private SceneAssets() {
  }

  /**
   * A renderer over {@code assetsDir} (= the repo {@code assets/} root). The {@code frame} sprite is the
   * medieval six-row window ({@code ui/chest/chest_6.png}, baked by {@code art.py bake-medieval}); if that
   * is absent it falls back to the first PNG in {@code frameDir}. Display caps come from the medieval title
   * font ({@code assetsDir/item/font_title}); body text from a small logical font (it needs lowercase,
   * which the caps-only medieval sheets lack).
   */
  public static SceneRenderer renderer(Path assetsDir, Path frameDir) {
    ComponentAtlas atlas = ComponentAtlas.fromDir(assetsDir);
    Path framePng = medievalFrame(assetsDir);
    if (framePng == null) {
      framePng = firstPng(frameDir);
    }
    if (framePng != null) {
      try {
        atlas.register(Components.SPRITE_FRAME, cropOpaque(ImageIO.read(framePng.toFile())));
      } catch (IOException exception) {
        throw new UncheckedIOException("Failed to read frame " + framePng, exception);
      }
    }
    BitmapFont display = BitmapFont.fromCharPngDir(assetsDir.resolve("item/font_title"), false, 1);
    // Body labels (hub card captions): a BOLD, OUTLINED monospaced face — the even widths keep long labels
    // inside the card, the bold weight + dark outline give a thick, high-contrast label that reads on the
    // wooden frame (the old thin PLAIN tintable mono read weak). Light cream fill + near-black-brown outline;
    // colours baked (non-tintable). Rasterised large so the outline survives the renderer's downscale.
    BitmapFont body = BitmapFont.fromAwtFontOutlined("Monospaced", Font.BOLD, 36, 0,
        0xFFF6E7C4, 0xFF241405, 3.0f);
    return new SceneRenderer(atlas, Map.of(
        Components.FONT_DISPLAY, display,
        Components.FONT_BODY, body));
  }

  /** The baked medieval window frame ({@code <assets>/ui/chest/chest_6.png}), or {@code null} if absent. */
  private static Path medievalFrame(Path assetsDir) {
    if (assetsDir == null) {
      return null;
    }
    Path frame = assetsDir.resolve("ui/chest/chest_6.png");
    return Files.isRegularFile(frame) ? frame : null;
  }

  /** Scene composition wants the visible frame, not the transparent 768px font canvas around it. */
  private static BufferedImage cropOpaque(BufferedImage image) {
    if (image == null) {
      return null;
    }
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
    if (maxX < minX || maxY < minY) {
      return image;
    }
    return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
  }

  /** The first {@code *.png} in {@code dir} (the frame file name carries spaces), or {@code null}. */
  private static Path firstPng(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) {
      return null;
    }
    try (Stream<Path> stream = Files.list(dir)) {
      return stream.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
          .endsWith(".png")).sorted().findFirst().orElse(null);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to list " + dir, exception);
    }
  }
}
