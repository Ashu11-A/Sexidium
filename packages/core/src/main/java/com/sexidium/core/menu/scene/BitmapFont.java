package com.sexidium.core.menu.scene;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import javax.imageio.ImageIO;

/**
 * A pixel font built from one PNG per character — the form Minecraft itself wants (a {@code bitmap} font
 * provider) and the only text form that composes in a chest title, so the same font drives both the
 * offline {@link SceneRenderer} and the in-game compiler.
 *
 * <p>Two flavours are supported through one type:
 * <ul>
 *   <li><b>Display fonts</b> ({@code tintable == false}) — the hand-authored, full-colour
 *       {@code item/font/char_*.png} caps used for title plaques. Drawn verbatim; the requested colour is
 *       ignored because the art carries its own.</li>
 *   <li><b>Body fonts</b> ({@code tintable == true}) — a monochrome pixel font whose glyphs are alpha
 *       masks; drawn multiplied by the requested colour so the same font renders white names, grey
 *       sub-text, green counts, etc.</li>
 * </ul>
 *
 * <p>Glyphs are trimmed to their non-transparent bounding box on load, and each glyph's advance is its
 * trimmed ink width plus {@code tracking}. Unknown characters (and {@code ' '}) advance by
 * {@code spaceAdvance} and draw nothing, so a render never crashes on a missing glyph.</p>
 */
public final class BitmapFont {

  /** One character's trimmed bitmap and the cursor advance (native px) to apply after drawing it. */
  public record Glyph(BufferedImage image, int advance) {}

  private final Map<Character, Glyph> glyphs;
  private final int lineHeight;
  private final int spaceAdvance;
  private final int tracking;
  private final boolean tintable;

  BitmapFont(Map<Character, Glyph> glyphs, int lineHeight, int spaceAdvance, int tracking,
      boolean tintable) {
    this.glyphs = Map.copyOf(glyphs);
    this.lineHeight = Math.max(1, lineHeight);
    this.spaceAdvance = Math.max(0, spaceAdvance);
    this.tracking = Math.max(0, tracking);
    this.tintable = tintable;
  }

  /** Native (un-scaled) line height — the design pixel height every glyph is measured against. */
  public int lineHeight() {
    return lineHeight;
  }

  /** Whether glyphs are alpha masks to be coloured (true) or full-colour art drawn as-is (false). */
  public boolean tintable() {
    return tintable;
  }

  /** The glyph for {@code c}, or {@code null} if this font has none (caller advances by the space width). */
  public Glyph glyph(char c) {
    return glyphs.get(c);
  }

  /** Native-px advance for one character (its glyph advance, or the space width when unmapped). */
  public int advance(char c) {
    Glyph glyph = glyphs.get(c);
    return glyph != null ? glyph.advance() : spaceAdvance;
  }

  /** Total native-px width of {@code text} laid out left-to-right (before any draw-time scaling). */
  public int measure(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int width = 0;
    for (int i = 0; i < text.length(); i++) {
      width += advance(text.charAt(i));
    }
    return width;
  }

  /** The scale factor that renders this font's native line height at {@code targetHeight} px. */
  public double scaleFor(int targetHeight) {
    return targetHeight / (double) lineHeight;
  }

  // ----- loading ------------------------------------------------------------------------------

  /**
   * Loads a font from a directory of {@code char_<X>.png} files (the {@code item/font/} convention): a file
   * named {@code char_A.png} maps to {@code 'A'}, {@code char_0.png} to {@code '0'}. Multi-character stems
   * (e.g. {@code char_H_alt}) are stylistic alternates and skipped. The directory must exist; an empty or
   * missing directory yields a font with no glyphs (all text then renders as spacing).
   */
  public static BitmapFont fromCharPngDir(Path dir, boolean tintable, int tracking) {
    Map<Character, Glyph> raw = new TreeMap<>();
    if (dir != null && Files.isDirectory(dir)) {
      try (var stream = Files.list(dir)) {
        stream.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(p -> {
          String stem = p.getFileName().toString();
          stem = stem.substring(0, stem.length() - 4);
          if (!stem.startsWith("char_")) {
            return;
          }
          String key = stem.substring("char_".length());
          if (key.length() != 1) {
            return; // skip "_alt" stylistic variants and any non-single-char stem
          }
          BufferedImage trimmed = trim(read(p));
          if (trimmed != null) {
            raw.put(key.charAt(0), new Glyph(trimmed, trimmed.getWidth() + tracking));
          }
        });
      } catch (IOException exception) {
        throw new UncheckedIOException("Failed to list font dir " + dir, exception);
      }
    }
    return finish(raw, tracking, tintable);
  }

  /** Builds a font from in-memory char→PNG bytes (used by the pack/baker so it needn't touch disk). */
  public static BitmapFont fromCharPngBytes(Map<Character, byte[]> pngByChar, boolean tintable,
      int tracking) {
    Map<Character, Glyph> raw = new LinkedHashMap<>();
    for (Map.Entry<Character, byte[]> entry : pngByChar.entrySet()) {
      BufferedImage trimmed = trim(readBytes(entry.getValue()));
      if (trimmed != null) {
        raw.put(entry.getKey(), new Glyph(trimmed, trimmed.getWidth() + tracking));
      }
    }
    return finish(raw, tracking, tintable);
  }

  private static BitmapFont finish(Map<Character, Glyph> glyphs, int tracking, boolean tintable) {
    int lineHeight = 1;
    int totalWidth = 0;
    for (Glyph glyph : glyphs.values()) {
      lineHeight = Math.max(lineHeight, glyph.image().getHeight());
      totalWidth += glyph.image().getWidth();
    }
    // A sensible space width: the average ink width, or a third of the line height when there are no glyphs.
    int spaceAdvance = glyphs.isEmpty() ? Math.max(1, lineHeight / 3)
        : Math.max(1, totalWidth / glyphs.size() / 3) + tracking;
    return new BitmapFont(glyphs, lineHeight, spaceAdvance, tracking, tintable);
  }

  /**
   * Builds a tintable body font by rasterising a Java2D logical font (always present — no asset to author
   * and no third-party dependency) at a small pixel size with antialiasing off, into baseline-aligned
   * fixed-height cells. This is the body text (player names, counts {@code 8/12}, descriptions, ONLINE) in
   * the <i>offline</i> preview/Discord render; in a real chest the same text is carried by Minecraft's own
   * font, so this never needs to be pixel-identical to vanilla — only crisp and legible.
   *
   * <p>Each glyph keeps the full {@code ascent+descent} cell height (only horizontal whitespace is
   * trimmed) so every character shares one baseline; the renderer's bottom-align then lands descenders and
   * caps correctly. Glyphs are white masks, coloured at draw time.</p>
   */
  public static BitmapFont fromAwtFont(String family, int style, int pixelSize, int tracking) {
    return fromAwtFont(family, style, pixelSize, tracking, false);
  }

  /**
   * As {@link #fromAwtFont(String, int, int, int)} but with selectable text antialiasing. AA OFF gives the
   * hard-edged pixel look (fine for large/blocky text); AA ON keeps thin strokes intact when the glyph is
   * later scaled DOWN to a small label — without it, a small face loses pixels (an {@code M} reads as an
   * {@code H}, a {@code y}'s tail vanishes). Rasterise a few px above the target size and let the renderer's
   * downscale do the smoothing.
   */
  public static BitmapFont fromAwtFont(String family, int style, int pixelSize, int tracking,
      boolean antialias) {
    System.setProperty("java.awt.headless", "true");
    java.awt.Font font = new java.awt.Font(family, style, pixelSize);
    BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D pg = probe.createGraphics();
    pg.setFont(font);
    java.awt.FontMetrics fm = pg.getFontMetrics();
    int ascent = fm.getAscent();
    int descent = fm.getDescent();
    int cellHeight = Math.max(1, ascent + descent);
    int spaceAdvance = Math.max(1, fm.charWidth(' ')) + tracking;
    pg.dispose();

    Object aaHint = antialias ? java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON
        : java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF;
    Map<Character, Glyph> glyphs = new LinkedHashMap<>();
    for (char c = 0x21; c <= 0x7E; c++) { // skip space (0x20) — handled by spaceAdvance
      int width = fm.charWidth(c);
      if (width <= 0) {
        continue;
      }
      BufferedImage cell = new BufferedImage(width + 2, cellHeight, BufferedImage.TYPE_INT_ARGB);
      java.awt.Graphics2D g = cell.createGraphics();
      g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, aaHint);
      g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,
          java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
      g.setFont(font);
      g.setColor(java.awt.Color.WHITE);
      g.drawString(String.valueOf(c), 1, ascent);
      g.dispose();
      BufferedImage trimmed = trimHorizontal(cell);
      if (trimmed != null) {
        glyphs.put(c, new Glyph(trimmed, trimmed.getWidth() + tracking));
      }
    }
    return new BitmapFont(glyphs, cellHeight, spaceAdvance, tracking, true);
  }

  /**
   * A bold, OUTLINED body font: each glyph is rasterised with a {@code outline}-px halo (in
   * {@code outlineArgb}) under a {@code fillArgb} fill, both baked in — so it is a full-colour
   * ({@code tintable == false}) font drawn verbatim, the requested text colour ignored. Use for labels that
   * must read on a busy/coloured background (e.g. the hub card captions on the wooden frame): the outline
   * gives a thick, high-contrast edge the thin tintable face lacked. Rasterised antialiased a few px above
   * the target size so the outline survives the renderer's downscale.
   */
  public static BitmapFont fromAwtFontOutlined(String family, int style, int pixelSize, int tracking,
      int fillArgb, int outlineArgb, float outline) {
    System.setProperty("java.awt.headless", "true");
    java.awt.Font font = new java.awt.Font(family, style, pixelSize);
    BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D pg = probe.createGraphics();
    pg.setFont(font);
    java.awt.FontMetrics fm = pg.getFontMetrics();
    int ascent = fm.getAscent();
    int descent = fm.getDescent();
    int pad = (int) Math.ceil(outline) + 1;
    int cellHeight = Math.max(1, ascent + descent + 2 * pad);
    int spaceAdvance = Math.max(1, fm.charWidth(' ')) + tracking;
    java.awt.font.FontRenderContext frc = pg.getFontRenderContext();
    pg.dispose();

    java.awt.Color fill = new java.awt.Color(fillArgb, true);
    java.awt.Color outlineColor = new java.awt.Color(outlineArgb, true);
    // Stroke straddles the glyph outline path (outline px each side); the fill then covers the inner half,
    // leaving a clean `outline`-px-thick edge around the letter.
    java.awt.Stroke stroke = new java.awt.BasicStroke(outline * 2f, java.awt.BasicStroke.CAP_ROUND,
        java.awt.BasicStroke.JOIN_ROUND);
    Map<Character, Glyph> glyphs = new LinkedHashMap<>();
    for (char c = 0x21; c <= 0x7E; c++) { // skip space (0x20) — handled by spaceAdvance
      int width = fm.charWidth(c);
      if (width <= 0) {
        continue;
      }
      BufferedImage cell = new BufferedImage(width + 2 * pad, cellHeight, BufferedImage.TYPE_INT_ARGB);
      java.awt.Graphics2D g = cell.createGraphics();
      g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
          java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL,
          java.awt.RenderingHints.VALUE_STROKE_PURE);
      java.awt.Shape shape = font.createGlyphVector(frc, String.valueOf(c)).getOutline(pad, pad + ascent);
      g.setColor(outlineColor);
      g.setStroke(stroke);
      g.draw(shape);
      g.setColor(fill);
      g.fill(shape);
      g.dispose();
      BufferedImage trimmed = trimHorizontal(cell);
      if (trimmed != null) {
        glyphs.put(c, new Glyph(trimmed, trimmed.getWidth() + tracking));
      }
    }
    return new BitmapFont(glyphs, cellHeight, spaceAdvance, tracking, false); // colours baked → not tintable
  }

  /** Crops only left/right transparent columns, preserving full cell height (so the baseline is stable). */
  private static BufferedImage trimHorizontal(BufferedImage image) {
    int w = image.getWidth();
    int h = image.getHeight();
    int minX = w;
    int maxX = -1;
    for (int x = 0; x < w; x++) {
      for (int y = 0; y < h; y++) {
        if ((image.getRGB(x, y) >>> 24) != 0) {
          if (x < minX) {
            minX = x;
          }
          if (x > maxX) {
            maxX = x;
          }
          break;
        }
      }
    }
    if (maxX < minX) {
      return null;
    }
    return image.getSubimage(minX, 0, maxX - minX + 1, h);
  }

  /** Crops an image to its non-transparent bounding box; returns {@code null} for a fully blank image. */
  private static BufferedImage trim(BufferedImage image) {
    if (image == null) {
      return null;
    }
    int w = image.getWidth();
    int h = image.getHeight();
    int minX = w;
    int minY = h;
    int maxX = -1;
    int maxY = -1;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if ((image.getRGB(x, y) >>> 24) != 0) {
          if (x < minX) {
            minX = x;
          }
          if (y < minY) {
            minY = y;
          }
          if (x > maxX) {
            maxX = x;
          }
          if (y > maxY) {
            maxY = y;
          }
        }
      }
    }
    if (maxX < minX || maxY < minY) {
      return null;
    }
    return image.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
  }

  private static BufferedImage read(Path path) {
    try (InputStream in = Files.newInputStream(path)) {
      return toArgb(ImageIO.read(in));
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read glyph " + path, exception);
    }
  }

  private static BufferedImage readBytes(byte[] bytes) {
    try {
      return toArgb(ImageIO.read(new java.io.ByteArrayInputStream(bytes)));
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to decode glyph bytes", exception);
    }
  }

  /** Normalises any decoded image to TYPE_INT_ARGB so alpha math and trimming are uniform. */
  private static BufferedImage toArgb(BufferedImage source) {
    if (source == null) {
      return null;
    }
    if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
      return source;
    }
    BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(),
        BufferedImage.TYPE_INT_ARGB);
    java.awt.Graphics2D g = argb.createGraphics();
    g.drawImage(source, 0, 0, null);
    g.dispose();
    return argb;
  }
}
