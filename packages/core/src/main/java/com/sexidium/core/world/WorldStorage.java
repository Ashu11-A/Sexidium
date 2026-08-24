package com.sexidium.core.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Owns the on-disk layout and folder-deletion mechanics for managed worlds: resolving an experience
 * key to its nested {@code <root>/<nick>/<map>_<id>} folder and the best-effort recursive deletion the
 * disposal/garbage paths rely on.
 *
 * <p>Extracted from {@code AbstractWorldControl} as a stateless helper bound to one experiences-disk
 * root. It performs no naming policy and no platform calls beyond the filesystem.</p>
 */
final class WorldStorage {
  private final Path experiencesDiskRoot;

  WorldStorage(Path experiencesDiskRoot) {
    this.experiencesDiskRoot = experiencesDiskRoot;
  }

  /** On-disk folder for an experience key, preserving the {@code <nick>/<map>_<id>} nesting. */
  Path experienceFolderFor(String key) {
    Path folder = experiencesDiskRoot;
    if (key != null) {
      for (String segment : key.replace('\\', '/').split("/")) {
        if (!segment.isBlank()) {
          folder = folder.resolve(segment);
        }
      }
    }
    return folder;
  }

  /**
   * Recursively deletes a folder and REPORTS whether it is gone.
   *
   * <p>Still best-effort in the sense that no single failed unlink throws — but the answer is no longer
   * thrown away, because two callers cannot be correct without it. A rollback that could not remove a
   * published folder used to leave ~290 MB on the shared tree with not one line in the log; and the
   * retry loop in {@code copyExperienceFolders} clears staging BETWEEN attempts, so a clearance that
   * silently failed had the next attempt copy over the leftovers with REPLACE_EXISTING — and
   * {@code WorldClone.firstMismatch} only walks the SOURCE's inventory, so an extra file in the
   * destination is invisible to the verification. That publishes a MERGED folder as a verified exact
   * replica, which is precisely the "your backup is subtly wrong" outcome {@code allowLiveCopy()}
   * promises cannot happen.</p>
   *
   * @return true when nothing is left at {@code rootPath} (including: there never was anything)
   */
  static boolean deleteDirectory(Path rootPath) {
    if (rootPath == null || !Files.exists(rootPath)) {
      return true;
    }
    try (var paths = Files.walk(rootPath)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // Per-entry: the walk finishes and the existence check below is what decides.
        }
      });
    } catch (IOException unwalkable) {
      return !Files.exists(rootPath);
    }
    return !Files.exists(rootPath);
  }

  /** Like {@link #deleteDirectory}, but answers false for a folder that was not there to remove. */
  static boolean deleteDirectoryIfExists(Path rootPath) {
    if (rootPath == null || !Files.exists(rootPath)) {
      return false;
    }
    return deleteDirectory(rootPath);
  }

  static Path normalizePath(Path path) {
    return path == null ? null : path.toAbsolutePath().normalize();
  }
}
