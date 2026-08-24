package com.sexidium.core.menu.scene.bake;

import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.scene.Scene;
import com.sexidium.core.menu.scene.SceneRenderer;
import com.sexidium.core.menu.scene.SceneTemplates;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The headless build step of the automated screen-generation system: renders every {@link SceneTemplates}
 * screen (with sample data) to a preview PNG. In M1 this produces the human-review / golden previews; in
 * M2 the same renderer output is fed into the resource pack as the baked background/atlas textures.
 *
 * <p>Pure file output, no Bukkit/AWT-display dependency, so it runs from a Gradle task or a test. Run:
 * {@code SceneBaker.bakePreviews(menuArtDir, frameDir, outDir, scale)}.</p>
 */
public final class SceneBaker {

  private SceneBaker() {
  }

  /**
   * On-screen cell size in GUI pixels — the bitmap-font {@code height} the glyph renders at (see
   * {@link MenuArt#SCREEN_GLYPH_HEIGHT} / {@code backgrounds.yml}). UNCHANGED by the resolution bump: the
   * art still occupies the same on-screen box, only the source texture behind it carries more detail.
   */
  public static final int GUI_CELL = 256;

  /**
   * Resolution multiplier for the baked SOURCE textures: each background is stored at {@code SOURCE_SCALE ×
   * GUI_CELL} px (768). Because the font {@code height} stays {@link #GUI_CELL} (256), the glyph renders at
   * scale {@code 256/768 = 1/3}, so every feature lands pixel-identically to the old 256 art — just sharper
   * on high-DPI / high-GUI-scale clients. The 768px source exceeds the 256px per-glyph font-atlas ceiling,
   * so it is NOT shipped as one glyph: {@code art.py tile-backgrounds} splits it into ≤256px tiles the pack
   * ships (see {@code MenuArt.TILE_*} / {@code PaperMenuArt}). Keep this a multiple of the tile grid.
   */
  public static final int SOURCE_SCALE = 3;

  /**
   * Internal supersample factor: a screen is rendered at {@code GUI px × PACK_SCALE} for clean antialiased
   * text/edges, then reduced to the {@link #SOURCE_SCALE}× source canvas before it is written. Kept a
   * multiple of {@code SOURCE_SCALE} so the reduction is a clean ~4× supersample. Do NOT write the
   * supersampled image straight to the pack.
   */
  public static final int PACK_SCALE = SOURCE_SCALE * 4;

  /**
   * Texture canvas size (px) a screen background bakes at: {@link #GUI_CELL} × {@link #SOURCE_SCALE} = 768.
   * SOURCE resolution only; on-screen size is set by {@link MenuArt#SCREEN_GLYPH_HEIGHT} (still 256).
   */
  public static final int SCREEN_TEXTURE_HEIGHT = GUI_CELL * SOURCE_SCALE;

  /**
   * Bakes each slice screen at {@link #PACK_SCALE} into the resource-pack texture path
   * {@code <assetsDir>/ui/screens/<scene-id>.png} — the path {@code MenuArt}'s {@code screen/<id>} glyph
   * reads. Run this and commit the PNGs so the shipped pack carries the real composed art (otherwise the
   * generator falls back to a placeholder for that glyph). Returns the written paths.
   */
  public static Map<String, Path> bakePackTextures(Path assetsDir, Path frameDir) {
    SceneRenderer renderer = com.sexidium.core.menu.scene.SceneAssets.renderer(assetsDir, frameDir);
    // Glyph texturePaths are pack-relative (item/.., ui/chest/.., ui/screens/..) and all resolve under the
    // single assets/ root, so screen backgrounds land at assets/ui/screens/<id>.png.
    Path outDir = assetsDir.resolve("ui/screens");
    Map<String, Path> written = new LinkedHashMap<>();
    try {
      Files.createDirectories(outDir);
      for (Scene scene : sliceScenes()) {
        // Supersample for AA, then reduce to the SOURCE_SCALE× source. The scene's GUI-px content is reduced
        // to (sceneW × SOURCE_SCALE, sceneH × SOURCE_SCALE) and pasted at the top-left of a 768px font cell;
        // placement is controlled by backgrounds.yml, not transparent margins.
        BufferedImage supersampled = renderer.render(scene, PACK_SCALE);
        int contentW = scene.width() * SOURCE_SCALE;
        int contentH = scene.height() * SOURCE_SCALE;
        BufferedImage content = reduceSupersample(supersampled, contentW, contentH);
        BufferedImage image = padToFontCanvas(content, SCREEN_TEXTURE_HEIGHT);
        Path out = outDir.resolve(scene.id() + ".png");
        Files.write(out, SceneRenderer.toPng(image));
        written.put(scene.id(), out);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to bake pack textures", exception);
    }
    return written;
  }

  /**
   * Reduces a supersampled render to the requested source size ({@code width}×{@code height}) with bicubic
   * resampling — clean text/frame edges at the {@link #SOURCE_SCALE}× source resolution. A no-op if the
   * render is already at that size.
   */
  private static BufferedImage reduceSupersample(BufferedImage source, int width, int height) {
    if (source.getWidth() == width && source.getHeight() == height) {
      return source;
    }
    BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.drawImage(source, 0, 0, width, height, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  /**
   * Pads the reduced screen render into a {@code canvasSize}×{@code canvasSize} bitmap-font cell (768 for the
   * tripled source), on a fully TRANSPARENT canvas. The visible content starts at (0,0) so Minecraft's
   * bitmap-font width detection cannot strip a positioning margin. The canvas is NOT pre-filled: the
   * transparent hub-card overlay stays see-through so the chest frame painted behind it shows. (A scene that
   * wants an opaque background lays its own fill in-scene.)
   */
  private static BufferedImage padToFontCanvas(BufferedImage content, int canvasSize) {
    BufferedImage out = new BufferedImage(canvasSize, canvasSize, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = out.createGraphics();
    try {
      g.drawImage(content, 0, 0, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  /** The baked hub scenes (normal + operator), in a stable order. */
  public static java.util.List<Scene> sliceScenes() {
    return java.util.List.of(
        SceneTemplates.mainHub(SceneTemplates.sampleHubTabsNormal()),
        SceneTemplates.mainHubOp(SceneTemplates.sampleHubTabsOp()));
  }

  /** Renders the M1 slice scenes (sample data) to {@code <outDir>/<scene-id>.png} at the given scale. */
  public static Map<String, Path> bakePreviews(Path menuArtDir, Path frameDir, Path outDir, int scale) {
    SceneRenderer renderer = com.sexidium.core.menu.scene.SceneAssets.renderer(menuArtDir, frameDir);
    Map<String, Scene> scenes = new LinkedHashMap<>();
    Scene hub = SceneTemplates.mainHub(SceneTemplates.sampleHubTabsNormal());
    Scene hubOp = SceneTemplates.mainHubOp(SceneTemplates.sampleHubTabsOp());
    scenes.put(hub.id(), hub);
    scenes.put(hubOp.id(), hubOp);

    Map<String, Path> written = new LinkedHashMap<>();
    try {
      Files.createDirectories(outDir);
      for (Map.Entry<String, Scene> entry : scenes.entrySet()) {
        BufferedImage image = renderer.render(entry.getValue(), scale);
        Path out = outDir.resolve(entry.getKey() + ".png");
        Files.write(out, SceneRenderer.toPng(image));
        written.put(entry.getKey(), out);
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to bake scene previews", exception);
    }
    return written;
  }

  /**
   * CLI entry. Two modes:
   * <ul>
   *   <li>{@code pack <menuArtDir> <frameDir>} — bake the shipped pack textures into
   *       {@code <menuArtDir>/ui/screens/} (commit these).</li>
   *   <li>{@code preview <menuArtDir> <frameDir> <outDir> [scale]} — dump review previews.</li>
   * </ul>
   */
  public static void main(String[] args) {
    if (args.length >= 3 && args[0].equals("pack")) {
      bakePackTextures(Path.of(args[1]), Path.of(args[2]))
          .forEach((id, path) -> System.out.println("baked pack texture " + id + " -> " + path));
      return;
    }
    if (args.length >= 4 && args[0].equals("preview")) {
      int scale = args.length >= 5 ? Integer.parseInt(args[4]) : 4;
      bakePreviews(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), scale)
          .forEach((id, path) -> System.out.println("baked preview " + id + " -> " + path));
      return;
    }
    System.err.println("usage: SceneBaker pack <menuArtDir> <frameDir>");
    System.err.println("   or: SceneBaker preview <menuArtDir> <frameDir> <outDir> [scale]");
    System.exit(2);
  }
}
