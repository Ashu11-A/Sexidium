package com.sexidium.core.world;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Copies a world's save folder so a pre-built template (e.g. a TNT-War arena) can be cloned into a
 * fresh, disposable match world. Both platform world-lease services call this before loading the new
 * folder, so the clone keeps every block at the same coordinates as the template (which is what lets a
 * map's base regions stay valid in the match world). Lock and identity files that must NOT be shared
 * between two simultaneously-loaded worlds are skipped.
 */
public final class WorldClone {
  /** Files a freshly loaded world regenerates and must never inherit from the template. */
  private static final Set<String> SKIP_FILES = Set.of("session.lock", "uid.dat");

  private WorldClone() {
  }

  /**
   * Recursively copies {@code source} into {@code target}. Returns true on success. A missing source or
   * any IO error returns false (callers then fall back to generating a fresh world) rather than throwing.
   */
  public static boolean copy(Path source, Path target) {
    if (source == null || target == null || !Files.isDirectory(source)) {
      return false;
    }
    try {
      Files.createDirectories(target);
      Files.walkFileTree(source, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
          Files.createDirectories(target.resolve(source.relativize(dir).toString()));
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          if (!isSkipped(file)) {
            Path destination = target.resolve(source.relativize(file).toString());
            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
          }
          return FileVisitResult.CONTINUE;
        }
      });
      return true;
    } catch (IOException exception) {
      return false;
    }
  }

  private static boolean isSkipped(Path file) {
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    return SKIP_FILES.contains(name) || name.endsWith(".lock");
  }

  /** The per-world chunk/data subfolders copied when persisting an edit world back onto a template. */
  private static final Set<String> CHUNK_DIRS = Set.of("region", "entities", "poi", "data");

  /**
   * Why a chunk-data copy was refused. These names travel verbatim into operator logs, so an on-call
   * reader can tell "the map was never installed on this node" apart from "somebody rewrote the map
   * folder while a match was cloning it" — the two used to produce the exact same (or no) message.
   */
  public enum CloneFailure {
    /** Copy completed and the destination matches the source file-for-file. */
    NONE,
    /** The folder is not there at all (never provisioned, wrong name, or moved aside by a refresh). */
    TEMPLATE_MISSING,
    /** The folder exists but has no top-level {@code region/} — not a world save, or a partial extract. */
    NO_REGION_DATA,
    /** {@code region/} is there but empty: an extract that was interrupted before writing any chunk. */
    EMPTY_REGION_DATA,
    /** A read or write blew up mid-copy; the destination may hold a partial tree. */
    IO_ERROR,
    /**
     * The SOURCE tree changed while we were reading it (file appeared, vanished, or changed size/mtime).
     * That is the concurrent-refresh race: a map re-extract or an editor save rewriting the same folder.
     * Without this check the copy returns "success" holding a truncated {@code .mca} or a Frankenstein
     * mix of the old and new map — which the player experiences as 512x512 holes in the arena.
     */
    TEMPLATE_CHANGED,
    /** The destination does not match the source inventory (missing file or short write). */
    INCOMPLETE_COPY
  }

  /**
   * Outcome of a validated chunk-data copy. {@code detail} is a ready-to-log human sentence naming the
   * folder and, where relevant, the offending entry.
   */
  public record ChunkCopyResult(CloneFailure failure, String detail) {
    public boolean ok() {
      return failure == CloneFailure.NONE;
    }
  }

  /**
   * Copies only the block/chunk data ({@code region/entities/poi/data}) from {@code source} into
   * {@code target}, leaving {@code target}'s own identity files ({@code level.dat}, spawn, version)
   * intact. Used by the map editor's Confirm to write structural edits made in a cloned edit world back
   * onto the base template map, so the next match clone of that template carries the new build, and by
   * the match-clone path in the other direction (template -&gt; fresh temp world). Returns true only when
   * the destination is a complete, self-consistent copy — see {@link #copyChunkDataChecked}.
   */
  public static boolean copyChunkData(Path source, Path target) {
    return copyChunkDataChecked(source, target).ok();
  }

  /**
   * {@link #copyChunkData} with the reason attached.
   *
   * <p>The copy is bracketed by two inventories of the SOURCE (relative path -&gt; size + mtime) and
   * followed by an inventory check of the DESTINATION. That is the cheapest guard that catches the two
   * silent-corruption races: reading a {@code .mca} that another JVM is truncating-and-rewriting (the
   * file's size/mtime moves under us), and stitching old regions to new ones after the writer renamed
   * the folder mid-walk ({@code Files.walkFileTree} iterates a directory descriptor, but
   * {@code Files.copy} re-resolves each entry BY PATH, so half the clone can come from the replacement
   * tree). Both of those used to return {@code true} with no exception and no log line.</p>
   *
   * <p>The source inventory is taken BEFORE anything is written, so a template that is missing or has no
   * region data is refused without touching the destination — that matters in the editor-save direction,
   * where the destination is the master copy of the map.</p>
   */
  public static ChunkCopyResult copyChunkDataChecked(Path source, Path target) {
    return copyChunkDataChecked(source, target, null);
  }

  /**
   * Test seam: {@code midFlight} runs after the bytes are copied and before the source is re-read, which
   * is the window a real concurrent writer occupies. Production code passes {@code null}; only the race
   * tests use it, because a genuine interleaving cannot be scheduled deterministically from a test.
   */
  static ChunkCopyResult copyChunkDataChecked(Path source, Path target, Runnable midFlight) {
    if (source == null || target == null) {
      return new ChunkCopyResult(CloneFailure.TEMPLATE_MISSING, "no world folder was given");
    }
    ChunkCopyResult inspection = inspect(source);
    if (!inspection.ok()) {
      return inspection;
    }
    Map<String, String> before;
    try {
      before = inventory(source);
    } catch (IOException exception) {
      return new ChunkCopyResult(CloneFailure.IO_ERROR,
          "could not read '" + source + "': " + exception);
    }
    for (String dir : CHUNK_DIRS) {
      Path sourceDir = source.resolve(dir);
      if (Files.isDirectory(sourceDir) && !copy(sourceDir, target.resolve(dir))) {
        return new ChunkCopyResult(CloneFailure.IO_ERROR,
            "copying '" + dir + "' from '" + source + "' to '" + target + "' failed part-way");
      }
    }
    if (midFlight != null) {
      midFlight.run();
    }
    Map<String, String> after;
    try {
      after = inventory(source);
    } catch (IOException exception) {
      return new ChunkCopyResult(CloneFailure.IO_ERROR,
          "could not re-read '" + source + "' to verify the copy: " + exception);
    }
    if (!before.equals(after)) {
      return new ChunkCopyResult(CloneFailure.TEMPLATE_CHANGED,
          "'" + source + "' changed while it was being copied (" + describeDrift(before, after)
              + ") — another process is rewriting this map right now");
    }
    String missing = firstMismatch(before, target);
    if (missing != null) {
      return new ChunkCopyResult(CloneFailure.INCOMPLETE_COPY,
          "'" + target + "' is missing or truncated at '" + missing + "'");
    }
    return new ChunkCopyResult(CloneFailure.NONE, "copied " + before.size() + " chunk file(s)");
  }

  /**
   * Copies a world folder WHOLE — every file, not only {@link #CHUNK_DIRS} — with the same
   * before/after verification bracket {@link #copyChunkDataChecked} applies.
   *
   * <p>Exists for the experience BACKUP, which is the one caller that wants an exact replica rather
   * than a chunk transplant. {@code copyChunkDataChecked} deliberately leaves out {@code
   * paper-world.yml} and the {@code sexidium/} sidecar because its two directions both write into a
   * folder that already has an identity of its own. A backup has no such folder: it is creating one,
   * and everything that makes the copy the same world — the stored seed and generator registry in
   * {@code data/minecraft/world_gen_settings.dat}, the hardcore/difficulty/spawn/clock overrides in
   * {@code data/paper/level_overrides.dat}, the per-player saves and the run's counters under
   * {@code sexidium/} — has to travel with it. Anything left behind here is a value the clone would
   * silently regenerate from defaults, which is exactly what "an exact copy" must not mean.</p>
   *
   * <p>{@link #isSkipped} still applies, so {@code session.lock}, {@code uid.dat} and any other
   * {@code *.lock} stay behind: those are per-open-world identity, never content. A keyed dimension
   * folder has none of them anyway — which is precisely why two servers can open one, and why the
   * caller must prove the source is closed before calling this.</p>
   */
  public static ChunkCopyResult copyWorldFolderChecked(Path source, Path target) {
    return copyWorldFolderChecked(source, target, null);
  }

  /** Test seam, exactly as {@link #copyChunkDataChecked(Path, Path, Runnable)}. */
  static ChunkCopyResult copyWorldFolderChecked(Path source, Path target, Runnable midFlight) {
    if (source == null || target == null) {
      return new ChunkCopyResult(CloneFailure.TEMPLATE_MISSING, "no world folder was given");
    }
    ChunkCopyResult inspection = inspect(source);
    if (!inspection.ok()) {
      return inspection;
    }
    Map<String, String> before;
    try {
      before = inventoryTree(source);
    } catch (IOException exception) {
      return new ChunkCopyResult(CloneFailure.IO_ERROR,
          "could not read '" + source + "': " + exception);
    }
    if (!copy(source, target)) {
      return new ChunkCopyResult(CloneFailure.IO_ERROR,
          "copying '" + source + "' to '" + target + "' failed part-way");
    }
    if (midFlight != null) {
      midFlight.run();
    }
    Map<String, String> after;
    try {
      after = inventoryTree(source);
    } catch (IOException exception) {
      return new ChunkCopyResult(CloneFailure.IO_ERROR,
          "could not re-read '" + source + "' to verify the copy: " + exception);
    }
    if (!before.equals(after)) {
      return new ChunkCopyResult(CloneFailure.TEMPLATE_CHANGED,
          "'" + source + "' changed while it was being copied (" + describeDrift(before, after)
              + ") — another process is writing this world right now");
    }
    String missing = firstMismatch(before, target);
    if (missing != null) {
      return new ChunkCopyResult(CloneFailure.INCOMPLETE_COPY,
          "'" + target + "' is missing or truncated at '" + missing + "'");
    }
    return new ChunkCopyResult(CloneFailure.NONE, "copied " + before.size() + " file(s)");
  }

  /**
   * Validates a world save folder WITHOUT copying anything: is it there, does it hold its own
   * {@code region/}, and does that folder actually contain chunk files? Callers use it to explain a
   * failed clone after the fact ("the map is not installed" vs. "the map looks fine now, so it was
   * rewritten under us").
   *
   * <p>Requiring the source to hold its own {@code region/} directly is deliberate: there is NO
   * "look under dimensions/minecraft/overworld" fallback, which on 26.1 storage could silently grab the
   * SERVER's vanilla overworld and overwrite a real template map with vanilla terrain.</p>
   */
  public static ChunkCopyResult inspect(Path source) {
    if (source == null || !Files.isDirectory(source)) {
      return new ChunkCopyResult(CloneFailure.TEMPLATE_MISSING, "'" + source + "' does not exist");
    }
    Path region = source.resolve("region");
    if (!Files.isDirectory(region)) {
      return new ChunkCopyResult(CloneFailure.NO_REGION_DATA,
          "'" + source + "' has no top-level region/ folder");
    }
    try (var entries = Files.list(region)) {
      if (entries.noneMatch(Files::isRegularFile)) {
        return new ChunkCopyResult(CloneFailure.EMPTY_REGION_DATA,
            "'" + region + "' contains no chunk files");
      }
    } catch (IOException exception) {
      return new ChunkCopyResult(CloneFailure.IO_ERROR, "could not list '" + region + "': " + exception);
    }
    return new ChunkCopyResult(CloneFailure.NONE, "'" + source + "' looks complete");
  }

  /** Relative path -> "size@mtime" for every non-skipped file under the chunk folders that exist. */
  private static Map<String, String> inventory(Path source) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    for (String dir : CHUNK_DIRS) {
      Path sourceDir = source.resolve(dir);
      if (!Files.isDirectory(sourceDir)) {
        continue;
      }
      Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (!isSkipped(file)) {
            entries.put(dir + "/" + sourceDir.relativize(file), attrs.size() + "@" + attrs.lastModifiedTime().toMillis());
          }
          return FileVisitResult.CONTINUE;
        }
      });
    }
    return entries;
  }

  /**
   * Relative path -&gt; "size@mtime" for every non-skipped file anywhere under {@code source}.
   *
   * <p>The whole-folder twin of {@link #inventory}, kept separate rather than parameterised so the
   * chunk-data bracket that {@code CloneStagingTest}/{@code WorldCloneTest} pin cannot change shape by
   * accident. Keys use {@code /} so {@link #firstMismatch} can resolve them against the destination.</p>
   *
   * <p>The timestamp is taken at FULL precision ({@code toInstant()}, nanoseconds on ext4/xfs/tmpfs),
   * not rounded to milliseconds as {@link #inventory} does. That difference matters here and only here:
   * the whole-folder copy is the one that brackets the small flat files under {@code sexidium/}, and the
   * writes those take are same-LENGTH rewrites — a counter going {@code "7"} to {@code "8"} does not
   * change the file's size. Size plus a millisecond is therefore not enough to notice one: a sidecar
   * rewritten inside the same millisecond the reader sampled it produces an identical inventory entry,
   * the bracket reports no drift, and a copy taken across a live write is returned as verified. Region
   * files have the same shape, being written in fixed sectors. Nanoseconds do not make the check
   * airtight — a filesystem with coarse timestamps still cannot tell — but they close the window that
   * actually occurs, at no cost, and they are why this reads the instant rather than the millis.</p>
   */
  private static Map<String, String> inventoryTree(Path source) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (!isSkipped(file)) {
          entries.put(source.relativize(file).toString().replace('\\', '/'),
              attrs.size() + "@" + attrs.lastModifiedTime().toInstant());
        }
        return FileVisitResult.CONTINUE;
      }
    });
    return entries;
  }

  /** Names one entry that differs between the two source inventories, for the log line. */
  private static String describeDrift(Map<String, String> before, Map<String, String> after) {
    for (Map.Entry<String, String> entry : before.entrySet()) {
      String now = after.get(entry.getKey());
      if (now == null) {
        return entry.getKey() + " disappeared";
      }
      if (!now.equals(entry.getValue())) {
        return entry.getKey() + " was rewritten";
      }
    }
    for (String key : after.keySet()) {
      if (!before.containsKey(key)) {
        return key + " appeared";
      }
    }
    return "the file set changed";
  }

  /**
   * Returns the first inventory entry the destination does not reproduce byte-count-for-byte, or null.
   * Only the size is compared: {@link #copy} preserves timestamps, but attribute copying is best-effort
   * on some filesystems, whereas a short file is always a real problem.
   */
  private static String firstMismatch(Map<String, String> expected, Path target) {
    for (Map.Entry<String, String> entry : expected.entrySet()) {
      Path copied = target.resolve(entry.getKey());
      long expectedSize = Long.parseLong(entry.getValue().substring(0, entry.getValue().indexOf('@')));
      try {
        if (!Files.isRegularFile(copied) || Files.size(copied) != expectedSize) {
          return entry.getKey();
        }
      } catch (IOException exception) {
        return entry.getKey();
      }
    }
    return null;
  }
}
