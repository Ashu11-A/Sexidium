package com.sexidium.core.menu.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Skeleton-level guarantees for the scene engine (M0): the Java2D renderer composes {@link Element.Fill},
 * {@link Element.Sprite}, and {@link Element.Text} into a deterministic ARGB image at the canvas size, with
 * each GUI pixel expanded by the device scale. These are the invariants every later screen template relies
 * on, so they are asserted on tiny hand-built scenes with exact expected pixels.
 */
class SceneRendererTest {

  private static final int RED = 0xFFFF0000;
  private static final int BLUE = 0xFF0000FF;
  private static final int TRANSPARENT = 0x00000000;

  @Test
  void fillRendersAtExactPixelsAndLeavesRestTransparent() {
    Scene scene = Scene.builder("fill", 20, 10)
        .add(new Element.Fill(Box.of(2, 2, 6, 4), RED))
        .build();
    BufferedImage image = renderer(ComponentAtlas.empty()).render(scene);

    assertEquals(20, image.getWidth());
    assertEquals(10, image.getHeight());
    assertEquals(RED, image.getRGB(5, 4), "inside the fill is opaque red");
    assertEquals(RED, image.getRGB(2, 2), "fill includes its top-left corner");
    assertEquals(TRANSPARENT, image.getRGB(0, 0), "outside the fill stays transparent");
    assertEquals(TRANSPARENT, image.getRGB(8, 2), "one past the right edge is transparent");
  }

  @Test
  void deviceScaleExpandsEveryGuiPixel() {
    Scene scene = Scene.builder("scaled", 4, 4)
        .add(new Element.Fill(Box.of(1, 1, 1, 1), RED))
        .build();
    BufferedImage image = renderer(ComponentAtlas.empty()).render(scene, 6);

    assertEquals(24, image.getWidth());
    assertEquals(24, image.getHeight());
    // The single GUI pixel (1,1) becomes the 6×6 device block [6,12)×[6,12).
    assertEquals(RED, image.getRGB(6, 6));
    assertEquals(RED, image.getRGB(11, 11));
    assertEquals(TRANSPARENT, image.getRGB(5, 5));
    assertEquals(TRANSPARENT, image.getRGB(12, 12));
  }

  @Test
  void spriteStretchesIntoItsBox() {
    BufferedImage swatch = solid(2, 2, BLUE);
    ComponentAtlas atlas = ComponentAtlas.empty().register("blue", swatch);
    Scene scene = Scene.builder("sprite", 20, 10)
        .add(new Element.Sprite("blue", Box.of(10, 2, 4, 4), Element.Fit.STRETCH))
        .build();
    BufferedImage image = renderer(atlas).render(scene);

    assertEquals(BLUE, image.getRGB(12, 4), "sprite fills its box");
    assertEquals(TRANSPARENT, image.getRGB(0, 0), "elsewhere is untouched");
  }

  @Test
  void missingSpriteIsSkippedNotThrown() {
    Scene scene = Scene.builder("missing", 8, 8)
        .add(new Element.Sprite("does/not/exist", Box.of(0, 0, 8, 8)))
        .build();
    BufferedImage image = renderer(ComponentAtlas.empty()).render(scene);
    assertEquals(TRANSPARENT, image.getRGB(4, 4), "an absent asset draws nothing");
  }

  @Test
  void renderIsDeterministic() {
    BufferedImage swatch = solid(2, 2, BLUE);
    ComponentAtlas atlas = ComponentAtlas.empty().register("blue", swatch);
    Scene scene = Scene.builder("det", 16, 8)
        .add(new Element.Fill(Box.of(0, 0, 16, 8), 0x80203040))
        .add(new Element.Sprite("blue", Box.of(2, 2, 4, 4), Element.Fit.STRETCH))
        .build();
    byte[] first = SceneRenderer.toPng(renderer(atlas).render(scene, 4));
    byte[] second = SceneRenderer.toPng(renderer(atlas).render(scene, 4));
    assertArrayEquals(first, second, "identical scene renders byte-identical PNG");
  }

  @Test
  void displayFontDrawsInkFromTheRealCharGlyphs() {
    Path fontDir = locate("assets/item/font");
    assumeTrue(fontDir != null, "assets/item/font not found from the test dir; skipping");
    BitmapFont display = BitmapFont.fromCharPngDir(fontDir, false, 2);
    assumeTrue(display.glyph('A') != null, "expected a char_A glyph in the font set");

    Scene scene = Scene.builder("text", 80, 24)
        .add(new Element.Text("ABC", Box.of(2, 2, 76, 20), "display", 0xFFFFFFFF,
            Element.HAlign.LEFT, Element.VAlign.MIDDLE))
        .build();
    BufferedImage image = new SceneRenderer(ComponentAtlas.empty(), Map.of("display", display))
        .render(scene, 2);

    assertTrue(hasOpaquePixel(image), "the title caps should paint visible ink");
  }

  // ----- helpers ------------------------------------------------------------------------------

  private static SceneRenderer renderer(ComponentAtlas atlas) {
    return new SceneRenderer(atlas, Map.of());
  }

  private static BufferedImage solid(int w, int h, int argb) {
    BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        image.setRGB(x, y, argb);
      }
    }
    return image;
  }

  private static boolean hasOpaquePixel(BufferedImage image) {
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) > 0) {
          return true;
        }
      }
    }
    return false;
  }

  /** Walks up from the test working dir to find {@code relative} (mirrors MenuArtChestTest). */
  private static Path locate(String relative) {
    File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    while (dir != null) {
      File candidate = new File(dir, relative);
      if (candidate.isDirectory()) {
        return candidate.toPath();
      }
      dir = dir.getParentFile();
    }
    return null;
  }
}
