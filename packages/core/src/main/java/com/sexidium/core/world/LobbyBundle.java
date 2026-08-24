package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.ResourceAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Applies a lobby world + settings bundle embedded in the plugin jar at build time.
 *
 * <p>The build can embed two optional resources (see the root {@code build.gradle.kts}
 * {@code -PlobbyWorldZip}/{@code -PlobbyWorldDir}/{@code -PlobbySettingsDir} properties):</p>
 * <ul>
 *   <li>{@code bundled/world.zip} — a Minecraft world, extracted over the lobby world folder;</li>
 *   <li>{@code bundled/settings.zip} — a settings folder (e.g. {@code fakeplayers/*.yml}),
 *       extracted into the plugin data folder.</li>
 * </ul>
 *
 * <p>Seeding is driven by {@code worlds.lobby.create-if-missing}: the bootstrap only calls this when
 * the lobby world does not yet exist on disk, so an existing (operator-customised) lobby is never
 * clobbered. When no bundle is embedded the bootstrap generates a fresh world instead.</p>
 */
public final class LobbyBundle {
  public static final String WORLD_RESOURCE = "bundled/world.zip";
  public static final String SETTINGS_RESOURCE = "bundled/settings.zip";

  private final ConfigurationAdapter configuration;
  private final ResourceAdapter resources;
  private final LoggerAdapter logger;

  public LobbyBundle(ConfigurationAdapter configuration, ResourceAdapter resources, LoggerAdapter logger) {
    this.configuration = configuration;
    this.resources = resources;
    this.logger = logger;
  }

  /**
   * Seeds a brand-new lobby from the embedded bundle: extracts {@code bundled/world.zip} into
   * {@code lobbyFolder} (cleared first) and {@code bundled/settings.zip} into {@code dataDirectory}
   * (merged). The caller MUST only invoke this when the lobby world does not yet exist, so an existing
   * lobby is never overwritten.
   *
   * @param lobbyFolder   target folder for the embedded world (cleared before extraction)
   * @param dataDirectory target folder for the embedded settings (merged, not cleared)
   * @return true when an embedded world was extracted (so the lobby came from the bundle); false when
   *     no world is embedded and the caller should generate a fresh world instead
   */
  public boolean seedFromEmbeddedBundle(Path lobbyFolder, Path dataDirectory) {
    boolean world = extractResource(WORLD_RESOURCE, lobbyFolder, true, "lobby world");
    extractResource(SETTINGS_RESOURCE, dataDirectory, false, "settings");
    return world;
  }

  private boolean extractResource(String resourcePath, Path targetDir, boolean clearTargetFirst, String label) {
    Optional<InputStream> resource = resources.openResource(resourcePath);
    if (resource.isEmpty()) {
      return false;
    }
    try (InputStream stream = resource.get()) {
      extractZip(stream, targetDir, clearTargetFirst);
      logger.info("Applied bundled " + label + " into '" + targetDir + "'.");
      return true;
    } catch (IOException exception) {
      logger.warning("Could not extract bundled " + label + ": " + exception.getMessage());
      return false;
    }
  }

  private void extractZip(InputStream zipStream, Path targetDir, boolean clearTargetFirst) throws IOException {
    if (clearTargetFirst && Files.isDirectory(targetDir)) {
      deleteRecursively(targetDir);
    }
    Files.createDirectories(targetDir);
    Path targetRoot = targetDir.toAbsolutePath().normalize();
    try (ZipInputStream zip = new ZipInputStream(zipStream)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        Path resolved = targetRoot.resolve(entry.getName()).normalize();
        // zip-slip guard: never write outside the target directory.
        if (!resolved.startsWith(targetRoot)) {
          throw new IOException("Refusing zip entry that escapes the target directory: " + entry.getName());
        }
        if (entry.isDirectory()) {
          Files.createDirectories(resolved);
        } else {
          Path parent = resolved.getParent();
          if (parent != null) {
            Files.createDirectories(parent);
          }
          Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
        }
        zip.closeEntry();
      }
    }
  }

  private void deleteRecursively(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // Best-effort cleanup; a locked file will simply be overwritten on extraction.
        }
      });
    }
  }
}
