package com.sexidium.core.menu.scene;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

import javax.imageio.ImageIO;

/**
 * The Java2D backend: rasterises a {@link Scene} to a {@link BufferedImage}, headless and deterministic.
 * This is both the offline output (golden tests, docs, Discord/web, held-map HUD) and the build-time baker
 * that produces the resource-pack background/atlas textures — so what a designer previews is exactly what
 * the in-game compiler reconstructs from the same {@link Scene}.
 *
 * <p>Determinism: pure CPU raster into a {@code TYPE_INT_ARGB} buffer, no GPU, antialiasing off for text
 * (the pixelated Minecraft look), and a scale-direction-aware interpolation choice — nearest-neighbour when
 * upscaling (keeps pixel art crisp), bicubic when downscaling detailed source art (avoids aliasing). Forces
 * {@code java.awt.headless=true} on class load so it runs on a server with no display.</p>
 */
public final class SceneRenderer {

  static {
    System.setProperty("java.awt.headless", "true");
  }

  private final ComponentAtlas atlas;
  private final Map<String, BitmapFont> fonts;

  public SceneRenderer(ComponentAtlas atlas, Map<String, BitmapFont> fonts) {
    this.atlas = atlas;
    this.fonts = Map.copyOf(fonts);
  }

  /** Renders at 1:1 (one device pixel per scene GUI pixel). */
  public BufferedImage render(Scene scene) {
    return render(scene, 1);
  }

  /**
   * Renders the scene with each GUI pixel expanded to {@code scale} device pixels (the in-game chest
   * frames bake at scale 6; previews often use 4). All element coordinates are multiplied by {@code scale}.
   */
  public BufferedImage render(Scene scene, int scale) {
    if (scale < 1) {
      throw new IllegalArgumentException("scale must be >= 1: " + scale);
    }
    BufferedImage image = new BufferedImage(scene.width() * scale, scene.height() * scale,
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
      g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
      g.setComposite(AlphaComposite.SrcOver);
      for (Element element : scene.elements()) {
        draw(g, element, scale);
      }
    } finally {
      g.dispose();
    }
    return image;
  }

  private void draw(Graphics2D g, Element element, int scale) {
    switch (element) {
      case Element.Fill fill -> drawFill(g, fill, scale);
      case Element.Sprite sprite -> drawSprite(g, sprite, scale);
      case Element.Text text -> drawText(g, text, scale);
    }
  }

  private void drawFill(Graphics2D g, Element.Fill fill, int scale) {
    Box b = fill.box();
    g.setColor(new Color(fill.argb(), true));
    if (fill.cornerRadius() > 0) {
      int arc = fill.cornerRadius() * scale;
      g.fillRoundRect(b.x() * scale, b.y() * scale, b.width() * scale, b.height() * scale, arc, arc);
    } else {
      g.fillRect(b.x() * scale, b.y() * scale, b.width() * scale, b.height() * scale);
    }
  }

  private void drawSprite(Graphics2D g, Element.Sprite sprite, int scale) {
    BufferedImage source = atlas.get(sprite.spriteId());
    if (source == null) {
      return; // missing asset: skip, never abort the compose
    }
    Box dest = fitBox(sprite.box(), source.getWidth(), source.getHeight(), sprite.fit());
    blit(g, source, dest, scale);
  }

  private void drawText(Graphics2D g, Element.Text text, int scale) {
    BitmapFont font = fonts.get(text.fontId());
    String value = text.value();
    if (font == null || value == null || value.isEmpty()) {
      return;
    }
    Box box = text.box();
    double glyphScale = font.scaleFor(box.height()); // native font px -> box px
    int runWidth = (int) Math.round(font.measure(value) * glyphScale);
    int blockHeight = (int) Math.round(font.lineHeight() * glyphScale);

    int startX = switch (text.hAlign()) {
      case LEFT -> box.x();
      case CENTER -> box.x() + (box.width() - runWidth) / 2;
      case RIGHT -> box.right() - runWidth;
    };
    int topY = switch (text.vAlign()) {
      case TOP -> box.y();
      case MIDDLE -> box.y() + (box.height() - blockHeight) / 2;
      case BOTTOM -> box.bottom() - blockHeight;
    };

    double cursor = startX;
    Color tint = new Color(text.argb(), true);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      BitmapFont.Glyph glyph = font.glyph(c);
      if (glyph != null) {
        BufferedImage img = font.tintable() ? tint(glyph.image(), tint) : glyph.image();
        int gw = (int) Math.round(img.getWidth() * glyphScale);
        int gh = (int) Math.round(img.getHeight() * glyphScale);
        // Bottom-align each trimmed glyph to a common baseline at the block's bottom.
        int gx = (int) Math.round(cursor);
        int gy = topY + (int) Math.round((font.lineHeight() - img.getHeight()) * glyphScale);
        blit(g, img, new Box(gx, gy, gw, gh), scale);
      }
      cursor += font.advance(c) * glyphScale;
    }
  }

  /** Draws {@code source} into the scaled device rectangle for {@code destGui}, choosing interpolation. */
  private void blit(Graphics2D g, BufferedImage source, Box destGui, int scale) {
    int dx = destGui.x() * scale;
    int dy = destGui.y() * scale;
    int dw = Math.max(1, destGui.width() * scale);
    int dh = Math.max(1, destGui.height() * scale);
    boolean upscaling = dw >= source.getWidth() && dh >= source.getHeight();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, upscaling
        ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
        : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.drawImage(source, dx, dy, dw, dh, null);
  }

  /** The destination box (GUI px) for {@code fit}, given the source's native pixel size. */
  private static Box fitBox(Box box, int srcW, int srcH, Element.Fit fit) {
    return switch (fit) {
      case STRETCH -> box;
      case NONE -> new Box(box.x(), box.y(), srcW, srcH);
      case CONTAIN -> {
        double s = Math.min(box.width() / (double) srcW, box.height() / (double) srcH);
        yield box.centeredChild((int) Math.round(srcW * s), (int) Math.round(srcH * s));
      }
      case COVER -> {
        double s = Math.max(box.width() / (double) srcW, box.height() / (double) srcH);
        yield box.centeredChild((int) Math.round(srcW * s), (int) Math.round(srcH * s));
      }
    };
  }

  /** A copy of a (mask) glyph multiplied by {@code color} — used for tintable body fonts. */
  private static BufferedImage tint(BufferedImage source, Color color) {
    int cr = color.getRed();
    int cg = color.getGreen();
    int cb = color.getBlue();
    int ca = color.getAlpha();
    BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
        BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < source.getHeight(); y++) {
      for (int x = 0; x < source.getWidth(); x++) {
        int argb = source.getRGB(x, y);
        int a = (argb >>> 24) * ca / 255;
        int r = ((argb >> 16) & 0xFF) * cr / 255;
        int gr = ((argb >> 8) & 0xFF) * cg / 255;
        int b = (argb & 0xFF) * cb / 255;
        out.setRGB(x, y, (a << 24) | (r << 16) | (gr << 8) | b);
      }
    }
    return out;
  }

  /** Encodes an image to PNG bytes (used by the baker and golden tests). */
  public static byte[] toPng(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to encode PNG", exception);
    }
    return out.toByteArray();
  }
}
