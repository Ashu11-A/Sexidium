package com.sexidium.core.menu.scene;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

/**
 * The sprite library a {@link Scene} draws from: maps a sprite id (e.g. {@code frame},
 * {@code item/system/home}, {@code ui/components/pill_public}) to a decoded {@link BufferedImage}. Two
 * sources, blended: explicit in-memory registrations (so the baker can inject generated pieces) and a
 * lazy, cached lookup under a base directory where id {@code item/system/home} resolves to
 * {@code <base>/item/system/home.png}. A miss returns {@code null} — the renderer skips it — so a single
 * absent asset never aborts a compose.
 */
public final class ComponentAtlas {

  private final Path baseDir;
  private final Map<String, BufferedImage> registered = new HashMap<>();
  private final Map<String, BufferedImage> diskCache = new ConcurrentHashMap<>();
  private static final BufferedImage MISSING = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

  private ComponentAtlas(Path baseDir) {
    this.baseDir = baseDir;
  }

  /** An atlas that resolves ids to PNGs under {@code baseDir} (and accepts {@link #register} overrides). */
  public static ComponentAtlas fromDir(Path baseDir) {
    return new ComponentAtlas(baseDir);
  }

  /** An atlas with no disk backing — only what you {@link #register}. */
  public static ComponentAtlas empty() {
    return new ComponentAtlas(null);
  }

  /** Adds (or overrides) a sprite under {@code id}, taking precedence over any disk file. */
  public ComponentAtlas register(String id, BufferedImage image) {
    registered.put(id, toArgb(image));
    return this;
  }

  /** The sprite for {@code id}, or {@code null} if neither registered nor present on disk. */
  public BufferedImage get(String id) {
    BufferedImage explicit = registered.get(id);
    if (explicit != null) {
      return explicit;
    }
    if (baseDir == null) {
      return null;
    }
    BufferedImage cached = diskCache.computeIfAbsent(id, this::loadFromDisk);
    return cached == MISSING ? null : cached;
  }

  /** Whether {@link #get(String)} would resolve a sprite for {@code id}. */
  public boolean has(String id) {
    return get(id) != null;
  }

  private BufferedImage loadFromDisk(String id) {
    Path path = baseDir.resolve(id + ".png");
    if (!Files.isRegularFile(path)) {
      return MISSING;
    }
    try (InputStream in = Files.newInputStream(path)) {
      BufferedImage image = ImageIO.read(in);
      return image == null ? MISSING : toArgb(image);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read sprite " + path, exception);
    }
  }

  private static BufferedImage toArgb(BufferedImage source) {
    if (source == null) {
      return null;
    }
    if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
      return source;
    }
    BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(),
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = argb.createGraphics();
    g.drawImage(source, 0, 0, null);
    g.dispose();
    return argb;
  }
}
