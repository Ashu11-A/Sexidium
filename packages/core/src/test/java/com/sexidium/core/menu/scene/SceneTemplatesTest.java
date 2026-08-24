package com.sexidium.core.menu.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Guarantees for the baked hub overlays (normal + operator): each builds a 256×256 canvas (matching
 * {@code chest_6}), paints visible card ink from the real committed art while staying a TRANSPARENT overlay
 * (not an opaque board), and renders deterministically. The deterministic test also dumps the previews under
 * the build dir for human review (it does not byte-assert a whole composed screen, which would be brittle to
 * any intentional layout tweak — it asserts structure).
 */
class SceneTemplatesTest {

  @Test
  void mainHubRendersAsTransparentCardOverlay() {
    assertHubOverlayRenders(SceneTemplates.mainHub(SceneTemplates.sampleHubTabsNormal()));
  }

  @Test
  void mainHubOpRendersAsTransparentCardOverlay() {
    assertHubOverlayRenders(SceneTemplates.mainHubOp(SceneTemplates.sampleHubTabsOp()));
  }

  @Test
  void hubPreviewsAreDeterministicAndDumpedForReview() {
    Path menuArt = locate("assets");
    Path frame = locate("UI/frame");
    assumeTrue(menuArt != null, "assets not found from the test dir; skipping");
    Path outDir = new File(System.getProperty("user.dir"), "build/scene-previews").toPath();

    var first = com.sexidium.core.menu.scene.bake.SceneBaker.bakePreviews(menuArt, frame, outDir, 4);
    assertEquals(2, first.size(), "both hub previews baked (normal + operator)");

    // Re-render and compare bytes: the same data + art must produce byte-identical PNGs.
    SceneRenderer renderer = SceneAssets.renderer(menuArt, frame);
    byte[] a = SceneRenderer.toPng(renderer.render(
        SceneTemplates.mainHub(SceneTemplates.sampleHubTabsNormal()), 4));
    byte[] b = SceneRenderer.toPng(renderer.render(
        SceneTemplates.mainHub(SceneTemplates.sampleHubTabsNormal()), 4));
    assertArrayEquals(a, b, "main-hub renders deterministically");
  }

  // ----- helpers ------------------------------------------------------------------------------

  private static void assertHubOverlayRenders(Scene scene) {
    Path menuArt = locate("assets");
    Path frame = locate("UI/frame");
    assumeTrue(menuArt != null, "assets not found from the test dir; skipping");
    SceneRenderer renderer = SceneAssets.renderer(menuArt, frame);
    BufferedImage image = renderer.render(scene, 4);

    assertEquals(scene.width() * 4, image.getWidth());
    assertEquals(scene.height() * 4, image.getHeight());
    double opaque = opaqueFraction(image);
    assertTrue(opaque > 0.1, "scene '" + scene.id() + "' should paint visible card ink (was " + opaque + ")");
    assertTrue(opaque < 0.6, "scene '" + scene.id() + "' must stay a transparent overlay, not an opaque board"
        + " (was " + opaque + ")");
  }

  private static double opaqueFraction(BufferedImage image) {
    long opaque = 0;
    long total = (long) image.getWidth() * image.getHeight();
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) >>> 24) > 0) {
          opaque++;
        }
      }
    }
    return opaque / (double) total;
  }

  private static Path locate(String relative) {
    File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    while (dir != null) {
      File candidate = new File(dir, relative);
      if (candidate.exists()) {
        return candidate.toPath();
      }
      dir = dir.getParentFile();
    }
    return null;
  }
}
