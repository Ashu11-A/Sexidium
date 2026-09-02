package com.sexidium.core.world;

import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.ResourceAdapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts the minigame map worlds embedded in the plugin jar at build time into the server's world
 * root, so a fresh server ships ready-to-clone TNT War maps without an operator hand-placing folders,
 * and <b>keeps them up to date</b> as the bundled maps change.
 *
 * <p>The build's {@code prepareMapBundle} task stages each map as {@code bundled/maps/<world-path>.zip}
 * (entries normalised so {@code level.dat} sits at the zip root) and writes a {@code bundled/maps/manifest.txt}
 * listing one {@code <world-path> <sha256>} per line (e.g. {@code tntwar/tnt-wars a1b2…}). The digest is
 * taken from the <em>source</em> zip under {@code assets/worlds/**}, so it changes when — and only when —
 * someone re-exports that map. Each world path matches a {@code minigames.<mode>.maps} entry's
 * {@code world}, so {@code GameManager} can clone {@code <worldRoot>/<world-path>} into a fresh match world.</p>
 *
 * <h2>What happens to a map already on disk</h2>
 *
 * <p>Extraction writes the map's digest into a {@value #STAMP_FILE} marker inside the extracted folder, and
 * that marker is what the next boot compares against:</p>
 *
 * <ul>
 *   <li><b>No folder</b> → extract it (first boot).</li>
 *   <li><b>Marker matches the manifest</b> → leave it alone. This is the common case, and it is what keeps
 *       operator edits (a {@code sexidium-tntwar.yml} written by {@code /sx admin map edit}, blocks changed
 *       in-game) from being reverted on every restart.</li>
 *   <li><b>Marker differs</b> → the bundled map was re-exported: move the old folder aside as
 *       {@code <name>.replaced-<timestamp>} and extract the new one. Moved, never deleted — an operator who
 *       edited in-game but forgot to re-export still has their work, one {@code mv} away. Only the newest
 *       such copy is kept; older ones are pruned so iterating on a map does not leave a full copy of it
 *       behind on every round.</li>
 *   <li><b>No marker</b> (folder seeded by an older jar, or hand-placed) → <b>adopt</b> it: record the current
 *       digest and change nothing. Anything else would silently replace maps that predate this mechanism on
 *       the first boot after an upgrade.</li>
 * </ul>
 *
 * <p>A manifest line without a digest (written by older builds) falls back to the original never-clobber
 * behaviour: seed when missing, never touch an existing folder.</p>
 *
 * <h2>More than one server on the same world root</h2>
 *
 * <p>In a network every backend boots its own JVM against the same plugin jar, and once {@code worlds/} is
 * a shared directory they all run this at the same second. The decision above is a read-then-write
 * ({@link #hasWorldData} → extract) and the write is {@code REPLACE_EXISTING} straight onto the final
 * region files, so unsynchronised that is two JVMs interleaving bytes inside one {@code r.0.0.mca} — and
 * because both then stamp the <em>same</em> digest, the next boot sees a folder that looks perfectly
 * current. Silent, permanent corruption. So a whole seeding pass runs under a {@link FileLock} on
 * {@value #LOCK_FILE} in the world root: an OS-level, cross-process lock, which is the only kind that
 * means anything between separate JVMs sharing a filesystem. The second server blocks, then finds the
 * stamp the first one wrote and does nothing — the never-clobber rule finally holds across processes,
 * not just within one.</p>
 *
 * <h2>The other side of that lock: whoever is READING the folder</h2>
 *
 * <p>The paragraph above only ever thought about writers, because of the sentence "nothing loads them as
 * worlds at startup". That is still true and still misses the point: <em>nobody loads them</em> is not
 * <em>nobody reads them</em>. Every match start clones a template folder file by file
 * ({@code WorldClone.copyChunkData}), in runtime, on a node that has no idea another node is halfway
 * through re-extracting that same map. A reader that walks the tree while {@link #extractZip} rewrites it
 * copies a truncated {@code .mca}, or stitches half the old map to half the new one, and returns
 * <em>success</em> — the player then falls through a 512x512 hole in the arena.</p>
 *
 * <p>So the lock file has a second user: {@link #underSharedWorldRootLock} takes the SAME
 * {@value #LOCK_FILE} in shared mode ({@code fcntl} {@code F_RDLCK}). POSIX read locks are mutually
 * compatible, so any number of concurrent clones — on this node or another — pass straight through each
 * other; only a writer waits, and only for as long as a clone takes. The reader waits only while a
 * refresh is genuinely in flight, which is the rare event this exists for.</p>
 *
 * <p>Two defences sit behind it, because a lock that cannot be taken (see {@link #underSeedingLock}) must
 * not mean silent corruption: extraction is staged and published by {@code rename} (see
 * {@link #extractMap}), and the reader verifies what it copied.</p>
 *
 * <p>Unlike {@link LobbyBundle} the extracted folders are clone <em>templates</em>, not live dimensions, so
 * their {@code level.dat} is retained (the clone strips it in the copy) — and, because nothing loads them as
 * worlds at startup, replacing one on disk is safe. The lobby has no equivalent refresh for exactly that
 * reason: it is a loaded world by the time anything could compare it.</p>
 */
public final class MapBundle {
  public static final String MANIFEST_RESOURCE = "bundled/maps/manifest.txt";

  /** Marker written inside an extracted map folder holding the digest of the bundle it came from. */
  public static final String STAMP_FILE = ".sexidium-map-bundle";

  /** Lock file in the world root held for the duration of a seeding pass; see the class javadoc. */
  public static final String LOCK_FILE = ".map-bundle.lock";

  /**
   * One in-JVM read/write lock per world root, because a {@link FileLock} is owned by the <em>JVM</em>,
   * not by the thread: a second thread asking the OS for the same region would not queue behind the
   * first, it would hit {@link OverlappingFileLockException} and fall through to the unlocked path —
   * which is exactly the bug the lock exists to prevent. The in-JVM lock gives threads the serialisation
   * the file lock gives processes, in the same reader/writer shape, so the two halves compose: within
   * the process the {@link ReentrantReadWriteLock} decides who may proceed, and between processes the
   * single {@link FileLock} does.
   *
   * <p>Keyed by the normalised absolute path so two callers spelling the same root differently still
   * share it; bounded by the number of world roots a server has, which is one.</p>
   */
  private static final Map<String, RootLock> ROOT_LOCKS = new ConcurrentHashMap<>();

  /** How long a reader waits for a lock before giving up on it; {@code < 0} waits forever. */
  public static final long WAIT_FOREVER = -1L;

  /**
   * Body of a read-only pass over the world root. {@code lockHeld} is false when the lock could not be
   * taken within the caller's budget (or at all, on a filesystem that refuses advisory locks) — the body
   * still runs, because refusing to start a match is worse than reading a folder nobody is writing, but
   * it is told so it can verify harder / retry rather than trust the bytes.
   */
  @FunctionalInterface
  public interface WorldRootReader<T> {
    T read(boolean lockHeld);
  }

  /**
   * The reader/writer pair for one world root: a fair in-JVM {@link ReentrantReadWriteLock} plus, for
   * readers, ONE OS-level shared {@link FileLock} shared by every reader thread in this JVM and released
   * by the last one out (reference counted).
   *
   * <p>Why reference counting rather than a lock per reader: a second {@code lock()} on an overlapping
   * region of the same file, from the same JVM, throws {@link OverlappingFileLockException} no matter
   * how compatible the modes are — the JDK tracks locks per file, per process. Two concurrent clones on
   * one node would therefore break the mechanism at the exact moment it matters. One lock, N holders,
   * is both correct and precisely what the OS is being told: this process is reading.</p>
   *
   * <p>The lock is FAIR on purpose. Readers are match starts and arrive continuously; the writer is a
   * boot-time seeding pass. Unfair mode would let a steady trickle of clones starve the server that is
   * trying to install a re-exported map, which is the one thing that must not be starved.</p>
   */
  private static final class RootLock {
    private final ReentrantReadWriteLock threads = new ReentrantReadWriteLock(true);
    /** Guards the refcount + channel pair only; never held across the body of a read. */
    private final Object osLock = new Object();
    private FileChannel channel;
    private FileLock shared;
    private int readers;

    /**
     * Takes (or joins) this JVM's shared OS lock. Returns false when it could not be taken inside
     * {@code budgetMillis} — a writer in another process is mid-extract, or the filesystem has no
     * advisory locks.
     */
    boolean acquireShared(Path lockFile, long budgetMillis) {
      synchronized (osLock) {
        if (readers > 0) {
          // Already held by this JVM: joining costs nothing and asking the OS again would throw.
          readers++;
          return true;
        }
        FileChannel opened = null;
        try {
          // READ as well as WRITE: a shared lock on a channel that cannot read is a
          // NonReadableChannelException, since "shared" means "I am reading this".
          opened = FileChannel.open(lockFile,
              StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
          FileLock lock = awaitShared(opened, budgetMillis);
          if (lock == null) {
            opened.close();
            return false;
          }
          channel = opened;
          this.shared = lock;
          readers = 1;
          return true;
        } catch (IOException | RuntimeException failure) {
          // RuntimeException covers OverlappingFileLockException (this JVM already holds an overlapping
          // region) — impossible by construction here, but a fall-through to the unlocked path is a far
          // better answer than an exception escaping into a match start.
          closeQuietly(opened);
          return false;
        }
      }
    }

    private static FileLock awaitShared(FileChannel open, long budgetMillis) throws IOException {
      if (budgetMillis < 0) {
        return open.lock(0L, Long.MAX_VALUE, true);
      }
      long deadline = System.nanoTime() + budgetMillis * 1_000_000L;
      while (true) {
        FileLock attempt = open.tryLock(0L, Long.MAX_VALUE, true);
        if (attempt != null) {
          return attempt;
        }
        // tryLock returning null means another PROCESS holds it exclusively (a seeding pass). Poll
        // rather than block, because the caller may be on a thread that must not stall (the world
        // thread); the budget is theirs to spend.
        if (System.nanoTime() >= deadline) {
          return null;
        }
        try {
          Thread.sleep(5L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return null;
        }
      }
    }

    void releaseShared() {
      synchronized (osLock) {
        if (readers == 0) {
          return;
        }
        if (--readers > 0) {
          return;
        }
        try {
          if (shared != null) {
            shared.release();
          }
        } catch (IOException ignored) {
          // The channel close below drops it anyway; nothing useful to do or say.
        }
        closeQuietly(channel);
        shared = null;
        channel = null;
      }
    }

    private static void closeQuietly(FileChannel open) {
      if (open == null) {
        return;
      }
      try {
        open.close();
      } catch (IOException ignored) {
        // Nothing to recover: the lock dies with the descriptor either way.
      }
    }
  }

  private static RootLock rootLock(Path worldsRoot) {
    return ROOT_LOCKS.computeIfAbsent(
        worldsRoot.toAbsolutePath().normalize().toString(), key -> new RootLock());
  }

  /**
   * The directory whose {@value #LOCK_FILE} actually guards {@code template}'s bytes.
   *
   * <p>On the network layout a node's world root is not where the maps live: {@code run/<node>/worlds/tntwar}
   * is a SYMLINK into {@code run/shared/maps/tntwar}. A reader locking its own nominal root would take
   * {@code run/<node>/worlds/.map-bundle.lock} while the seeding writer (the {@code init} container running
   * {@link MapBundleCli}, whose world root IS {@code run/shared/maps}) takes
   * {@code run/shared/maps/.map-bundle.lock} — two different files, so reader and writer would not exclude
   * each other at all, which is precisely the case the lock exists for.
   *
   * <p>So the lock follows the BYTES, not the nominal root: the template path is resolved through its
   * symlinks ({@link Path#toRealPath}) and then walked back up by as many segments as the template's name
   * has below the world root, yielding the real root that contains it. A leaf symlink
   * ({@code worlds/tntwar/tnt-wars}) and an intermediate one ({@code worlds/tntwar}) both land on
   * {@code run/shared/maps}. Different bundles may therefore resolve to different roots — one symlinked into
   * the shared tree, one still a local copy — and each is locked where it lives.
   *
   * <p>Fallbacks, all of which return the (resolved) nominal root rather than throwing, because no lock
   * bookkeeping may ever be the reason a match fails to start:
   * <ul>
   *   <li>{@code template} is null / not under the world root;</li>
   *   <li>the template does not exist — {@code toRealPath} throws, and this path must keep flowing into the
   *       caller's own missing-template diagnostic instead of becoming a new exception;</li>
   *   <li>the resolved path does not end with the template's own name segments, i.e. the link renames as it
   *       redirects. Walking up blind there would invent an unrelated directory and drop a lock file in it.</li>
   * </ul>
   *
   * <p>Keying is unaffected in the no-symlink case (standalone, tests): the real path of the
   * template under a real root is that same root, so the lock file, the {@link #ROOT_LOCKS} key and the
   * behaviour are byte-for-byte what they were. And because resolution is deterministic, two threads
   * reading the SAME bundle always compute the same root and therefore share one {@link RootLock} entry —
   * one refcounted OS lock, no serialisation between them.
   */
  public static Path lockRootFor(Path worldsRoot, Path template) {
    if (worldsRoot == null) {
      return null;
    }
    Path root = worldsRoot.toAbsolutePath().normalize();
    if (template == null) {
      return realOrSelf(root);
    }
    Path absolute = template.toAbsolutePath().normalize();
    Path relative;
    try {
      relative = root.relativize(absolute);
    } catch (IllegalArgumentException differentFileSystems) {
      return realOrSelf(root);
    }
    if (relative.getNameCount() == 0 || relative.startsWith("..")) {
      return realOrSelf(root);
    }
    Path real;
    try {
      real = absolute.toRealPath();
    } catch (IOException | RuntimeException missingOrUnreadable) {
      return realOrSelf(root);
    }
    if (!real.endsWith(relative)) {
      return realOrSelf(root);
    }
    Path resolved = real;
    for (int segment = 0; segment < relative.getNameCount(); segment++) {
      resolved = resolved.getParent();
      if (resolved == null) {
        return realOrSelf(root);
      }
    }
    return resolved;
  }

  /** {@code root}'s path with symlinks resolved, or {@code root} itself when it cannot be resolved. */
  private static Path realOrSelf(Path root) {
    try {
      return root.toRealPath();
    } catch (IOException | RuntimeException notThereYet) {
      return root;
    }
  }

  private static final String REPLACED_SUFFIX = ".replaced-";
  /** Suffix of the folder a map is inflated into before being renamed onto its final path. */
  private static final String STAGING_SUFFIX = ".incoming-";
  private static final DateTimeFormatter REPLACED_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  /** How many superseded copies of a map to keep beside it (the newest one). */
  private static final int KEEP_REPLACED_COPIES = 1;

  private final ResourceAdapter resources;
  private final LoggerAdapter logger;

  public MapBundle(ResourceAdapter resources, LoggerAdapter logger) {
    this.resources = resources;
    this.logger = logger;
  }

  /** One manifest entry: where the map is extracted, and the fingerprint of the bundled source. */
  private record BundledMap(String worldPath, String digest) {
    boolean hasDigest() {
      return digest != null && !digest.isEmpty();
    }
  }

  /** What was done to a map on disk this boot. */
  private enum Outcome { EXTRACTED, REFRESHED, ADOPTED, UNCHANGED, FAILED }

  /**
   * Seeds every bundled map listed in the manifest into {@code worldsRoot}, refreshing the ones whose
   * bundled copy changed since they were seeded.
   *
   * @param worldsRoot the server's world root (config {@code worlds.root}, resolved by the caller)
   * @return the number of map folders written (freshly extracted + refreshed)
   */
  public int seedBundledMaps(Path worldsRoot) {
    return seedBundledMaps(worldsRoot, true);
  }

  /**
   * @param refreshWhenChanged when false, an existing map folder is never replaced even if the bundled map
   *     changed — the pre-refresh never-clobber behaviour, for an operator who manages the folders by hand
   *     (config {@code worlds.map-bundle.refresh-when-changed})
   * @return the number of map folders written (freshly extracted + refreshed)
   */
  public int seedBundledMaps(Path worldsRoot, boolean refreshWhenChanged) {
    List<BundledMap> maps = readManifest();
    if (maps.isEmpty()) {
      return 0;
    }
    // The whole pass, not each map: hasWorldData → moveAside → extract → stamp → prune is one decision
    // per map, and every step of it is destructive if a second server is halfway through the same one.
    return underSeedingLock(worldsRoot, () -> seedAll(worldsRoot, maps, refreshWhenChanged));
  }

  private int seedAll(Path worldsRoot, List<BundledMap> maps, boolean refreshWhenChanged) {
    int extracted = 0;
    int refreshed = 0;
    for (BundledMap map : maps) {
      switch (seedMap(worldsRoot, map, refreshWhenChanged)) {
        case EXTRACTED -> extracted++;
        case REFRESHED -> refreshed++;
        default -> { /* adopted / unchanged / failed: nothing written */ }
      }
    }
    if (extracted > 0) {
      logger.info("Extracted " + extracted + " bundled map(s) into '" + worldsRoot + "'.");
    }
    if (refreshed > 0) {
      logger.info("Refreshed " + refreshed + " bundled map(s) in '" + worldsRoot + "' from the jar.");
    }
    return extracted + refreshed;
  }

  /**
   * Runs {@code pass} while holding the world root's seeding lock against both other threads and other
   * JVMs. {@link FileChannel#lock()} blocks — deliberately: the loser is a server booting, and waiting a
   * few seconds for the winner to finish extracting is the correct outcome, whereas skipping would leave
   * it without map templates.
   *
   * <p>When the lock cannot be taken at all the pass still runs. Filesystems exist that refuse advisory
   * locks (older NFS mounts being the classic), and on a standalone server — one JVM, one world root,
   * where nothing was ever racing — refusing to seed maps because a lock file could not be created would
   * turn a non-problem into a broken server.</p>
   */
  private int underSeedingLock(Path worldsRoot, IntSupplier pass) {
    Path lockRoot;
    Path lockFile;
    try {
      Files.createDirectories(worldsRoot);
      // Resolved, for the same reason readers resolve: the writer and the reader must name ONE file. A
      // writer whose own root is reached through a link (or a reader that resolved into this root) would
      // otherwise take a different lock than the other half of the pair.
      lockRoot = lockRootFor(worldsRoot, null);
      lockFile = lockRoot.resolve(LOCK_FILE);
    } catch (IOException exception) {
      logger.warning("Could not prepare the bundled-map lock in '" + worldsRoot + "' ("
          + exception.getMessage() + "); seeding without it.");
      return pass.getAsInt();
    }
    // The in-JVM WRITE lock, taken first: it excludes this JVM's clone readers, which is what makes the
    // single OS lock below safe to ask for (no reader of ours can be holding an overlapping one).
    ReentrantReadWriteLock.WriteLock exclusive = rootLock(lockRoot).threads.writeLock();
    exclusive.lock();
    try {
      try (FileChannel channel = FileChannel.open(lockFile,
              StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          // Never read; declared so try-with-resources releases it. Holding it IS the work.
          FileLock heldForTheWholePass = channel.lock()) {
        return pass.getAsInt();
      } catch (IOException | OverlappingFileLockException exception) {
        logger.warning("Could not lock '" + lockFile + "' (" + exception.getMessage()
            + "); seeding bundled maps unserialised. If another server shares this world root, "
            + "extract the maps with only one of them running.");
        return pass.getAsInt();
      }
    } finally {
      exclusive.unlock();
    }
  }

  /**
   * Runs a READ-ONLY pass over {@code worldsRoot} — in practice cloning a map template into a fresh match
   * world — while holding the world root's lock in shared mode, so no seeding pass can rewrite the folder
   * underneath it.
   *
   * <p>Shared is the whole point: {@code FileChannel.lock(0, MAX, true)} is a {@code F_RDLCK}, and POSIX
   * read locks are mutually compatible, so N concurrent clones (this node's and every other node's) do not
   * see each other at all. The cost in the normal, uncontended case is one {@code open} + {@code fcntl} +
   * {@code close}: tens of microseconds against the 100-300 ms the copy itself already spends. Only a
   * writer waits, and only for one clone.</p>
   *
   * <p><b>Budget.</b> {@code maxWaitMillis} exists because the caller may be on a thread where an
   * unbounded wait is not a wait but an outage — the Paper world thread being the case in point: blocking
   * it for the 1-2 s an extraction takes freezes every player on the node, not merely the match that is
   * starting. On timeout the body still runs, with {@code lockHeld=false}, and is expected to verify what
   * it read ({@code WorldClone.copyChunkDataChecked}) rather than trust it. Pass {@link #WAIT_FOREVER}
   * from a background thread, where waiting costs nothing.</p>
   *
   * @param maxWaitMillis how long to wait for the lock, or {@link #WAIT_FOREVER}
   */
  public static <T> T underSharedWorldRootLock(Path worldsRoot, LoggerAdapter logger,
      long maxWaitMillis, WorldRootReader<T> reader) {
    return underSharedWorldRootLock(worldsRoot, null, logger, maxWaitMillis, reader);
  }

  /**
   * As above, but locking the root that really holds {@code template} — see {@link #lockRootFor}. Callers
   * that are about to read one specific map template must use this overload: on the network layout the
   * template is a symlink out of the node's world root into the shared map tree, and only the resolved root
   * has the lock file the seeding writer takes.
   *
   * @param template the template folder about to be read, or null to lock the world root itself
   */
  public static <T> T underSharedWorldRootLock(Path worldsRoot, Path template, LoggerAdapter logger,
      long maxWaitMillis, WorldRootReader<T> reader) {
    if (worldsRoot == null) {
      return reader.read(false);
    }
    Path lockRoot = lockRootFor(worldsRoot, template);
    Path lockFile;
    try {
      Files.createDirectories(lockRoot);
      lockFile = lockRoot.resolve(LOCK_FILE);
    } catch (IOException exception) {
      // Same policy as the writer: a lock we cannot even create must never stop a match from starting.
      warn(logger, "Could not prepare the bundled-map lock in '" + lockRoot + "' ("
          + exception.getMessage() + "); reading the world root without it.");
      return reader.read(false);
    }
    RootLock root = rootLock(lockRoot);
    long startedAt = System.nanoTime();
    if (!acquireThreadRead(root, maxWaitMillis)) {
      // A seeding pass in THIS JVM is running (boot-time, so this is close to impossible in practice).
      warn(logger, "A bundled-map seeding pass is holding '" + lockRoot + "'; reading it unlocked.");
      return reader.read(false);
    }
    boolean osLockHeld = false;
    try {
      osLockHeld = root.acquireShared(lockFile, remaining(maxWaitMillis, startedAt));
      if (!osLockHeld) {
        warn(logger, "Could not take a shared lock on '" + lockFile
            + "' in time; another server may be rewriting a map right now. Reading it unlocked and "
            + "verifying the copy instead.");
      }
      return reader.read(osLockHeld);
    } finally {
      if (osLockHeld) {
        root.releaseShared();
      }
      root.threads.readLock().unlock();
    }
  }

  private static boolean acquireThreadRead(RootLock root, long maxWaitMillis) {
    if (maxWaitMillis < 0) {
      root.threads.readLock().lock();
      return true;
    }
    try {
      return root.threads.readLock().tryLock(maxWaitMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** What is left of the caller's budget after the in-JVM half of the acquisition. */
  private static long remaining(long maxWaitMillis, long startedAt) {
    if (maxWaitMillis < 0) {
      return WAIT_FOREVER;
    }
    long spent = (System.nanoTime() - startedAt) / 1_000_000L;
    return Math.max(0L, maxWaitMillis - spent);
  }

  private static void warn(LoggerAdapter logger, String message) {
    if (logger != null) {
      logger.warning(message);
    }
  }

  /** The world paths (e.g. {@code tntwar/tnt-wars}) of every map bundled in the jar, for listing/tab-complete. */
  public static List<String> bundledWorldPaths(ResourceAdapter resources) {
    return new MapBundle(resources, null).readManifest().stream().map(BundledMap::worldPath).toList();
  }

  private List<BundledMap> readManifest() {
    Optional<InputStream> resource = resources.openResource(MANIFEST_RESOURCE);
    if (resource.isEmpty()) {
      return List.of();
    }
    List<BundledMap> maps = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.get(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        // "<world-path>" (older jars) or "<world-path> <sha256>".
        int separator = trimmed.indexOf(' ');
        if (separator < 0) {
          maps.add(new BundledMap(trimmed, null));
        } else {
          maps.add(new BundledMap(trimmed.substring(0, separator).trim(), trimmed.substring(separator + 1).trim()));
        }
      }
    } catch (IOException exception) {
      if (logger != null) {
        logger.warning("Could not read bundled-map manifest: " + exception.getMessage());
      }
    }
    return maps;
  }

  private Outcome seedMap(Path worldsRoot, BundledMap map, boolean refreshWhenChanged) {
    Path target = worldsRoot.resolve(map.worldPath()).normalize();
    if (!hasWorldData(target)) {
      return extractMap(target, map) ? Outcome.EXTRACTED : Outcome.FAILED;
    }
    // Without a digest there is nothing to compare against, so the original rule stands: never clobber.
    if (!map.hasDigest() || !refreshWhenChanged) {
      return Outcome.UNCHANGED;
    }
    String stamped = readStamp(target);
    if (stamped == null) {
      // Seeded before this mechanism existed (or hand-placed): take it as current rather than replace it.
      writeStamp(target, map.digest());
      return Outcome.ADOPTED;
    }
    if (stamped.equals(map.digest())) {
      return Outcome.UNCHANGED;
    }
    // Order matters, and it is the reverse of what it used to be: inflate the new map into a staging
    // folder FIRST, while the live one is still whole and readable, and only then swap. See extractMap.
    Path staged = extractToStaging(target, map);
    if (staged == null) {
      return Outcome.FAILED;
    }
    Path replaced = moveAside(target);
    if (replaced == null) {
      discard(staged);
      return Outcome.FAILED;
    }
    if (!publish(staged, target)) {
      // Publication failed after the move — put the operator's copy back rather than leave no map at all.
      restore(replaced, target);
      discard(staged);
      return Outcome.FAILED;
    }
    logger.info("Bundled map '" + map.worldPath() + "' changed in the jar; previous copy kept at '"
        + replaced.getFileName() + "'.");
    pruneReplacedCopies(target);
    return Outcome.REFRESHED;
  }

  /**
   * Inflates the bundled zip into a staging sibling and {@code rename}s it into place.
   *
   * <p><b>What this buys, precisely.</b> The old code inflated straight onto the final path, so for the
   * 1-2 s an inflate takes the folder held a half-written mixture of the two maps — and a reader is a
   * {@code Files.copy} per file that re-resolves each path from the root, so it happily copies a
   * {@code .mca} that is being truncated and rewritten, with no error anywhere. Staging shrinks that
   * window from seconds to the microseconds of a {@code rename(2)}.</p>
   *
   * <p><b>What it does NOT buy, so nobody trusts it for more than it is.</b> This is not atomic
   * publication of a directory. POSIX {@code rename} refuses to replace a non-empty directory, so a
   * refresh is unavoidably two renames — old aside, then new in — and between them the path does not
   * exist at all. A reader landing in that gap fails cleanly (missing source) instead of corrupting, and
   * a reader that started before it produces a detectably mixed copy, but neither is prevented here.
   * True atomic publication would need a symlink swapped by {@code rename} over itself. The thing that
   * actually prevents the race is the lock ({@link #underSharedWorldRootLock}); this is the belt to its
   * braces, for the filesystems and failure modes where the lock does not hold.</p>
   */
  private Path extractToStaging(Path target, BundledMap map) {
    String resourcePath = "bundled/maps/" + map.worldPath() + ".zip";
    Optional<InputStream> resource = resources.openResource(resourcePath);
    if (resource.isEmpty()) {
      logger.warning("Bundled-map manifest lists '" + map.worldPath() + "' but '" + resourcePath + "' is missing.");
      return null;
    }
    Path parent = target.getParent();
    if (parent == null) {
      logger.warning("Refusing to seed bundled map at '" + target + "': it has no parent directory.");
      return null;
    }
    // Beside the target, never in the system temp dir: staging must live on the same filesystem or the
    // publish stops being a rename and becomes a second full copy, reopening the window it exists to close.
    Path staging = parent.resolve(target.getFileName() + STAGING_SUFFIX
        + LocalDateTime.now().format(REPLACED_STAMP) + "-" + Long.toHexString(System.nanoTime()));
    // A JVM killed mid-inflate leaves its staging folder behind; it is worthless (nothing ever reads a
    // half-map by that name) and would otherwise accumulate a full copy of the map on every crash. Safe
    // to sweep here because this runs under the exclusive seeding lock: no other process is staging.
    sweepStaleStaging(parent, target.getFileName() + STAGING_SUFFIX);
    try (InputStream stream = resource.get()) {
      discard(staging);
      extractZip(stream, staging);
      // Stamped inside staging, so the digest and the data it describes appear together in one rename:
      // no boot can ever see stamped-but-unwritten data.
      writeStamp(staging, map.digest());
      return staging;
    } catch (IOException exception) {
      logger.warning("Could not extract bundled map '" + map.worldPath() + "': " + exception.getMessage());
      discard(staging);
      return null;
    }
  }

  private boolean extractMap(Path target, BundledMap map) {
    Path staged = extractToStaging(target, map);
    if (staged == null) {
      return false;
    }
    if (!publish(staged, target)) {
      discard(staged);
      return false;
    }
    logger.info("Seeded bundled map '" + map.worldPath() + "' into '" + target + "'.");
    return true;
  }

  /** Moves a fully-written staging folder onto the (absent) target path. */
  private boolean publish(Path staged, Path target) {
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try {
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException | UnsupportedOperationException atomicFailure) {
        // Same-directory renames are atomic everywhere we run; the fallback is for exotic filesystems.
        Files.move(staged, target);
      }
      return true;
    } catch (IOException exception) {
      logger.warning("Could not publish the bundled map staged at '" + staged + "' as '" + target
          + "': " + exception.getMessage());
      return false;
    }
  }

  private void sweepStaleStaging(Path parent, String prefix) {
    if (!Files.isDirectory(parent)) {
      return;
    }
    try (Stream<Path> siblings = Files.list(parent)) {
      siblings.filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith(prefix))
          .toList()
          .forEach(this::discard);
    } catch (IOException exception) {
      // Nothing depends on this succeeding; the extraction below uses a fresh, unique name regardless.
      logger.warning("Could not sweep stale map staging folders in '" + parent + "': " + exception.getMessage());
    }
  }

  private void discard(Path staging) {
    try {
      deleteRecursively(staging);
    } catch (IOException exception) {
      logger.warning("Could not remove the staging folder '" + staging + "': " + exception.getMessage());
    }
  }

  /** True when the folder already holds world data, so it is a seeded/edited map rather than an empty path. */
  private static boolean hasWorldData(Path folder) {
    return Files.isDirectory(folder)
        && (Files.isDirectory(folder.resolve("region")) || Files.exists(folder.resolve("level.dat")));
  }

  private String readStamp(Path worldFolder) {
    Path stamp = worldFolder.resolve(STAMP_FILE);
    if (!Files.isRegularFile(stamp)) {
      return null;
    }
    try {
      String content = Files.readString(stamp, StandardCharsets.UTF_8).trim();
      return content.isEmpty() ? null : content;
    } catch (IOException exception) {
      logger.warning("Could not read '" + stamp + "': " + exception.getMessage());
      return null;
    }
  }

  private void writeStamp(Path worldFolder, String digest) {
    if (digest == null || digest.isEmpty()) {
      return;
    }
    try {
      Files.createDirectories(worldFolder);
      Files.writeString(worldFolder.resolve(STAMP_FILE), digest + System.lineSeparator(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      // Not fatal: the map is on disk and playable, the next boot simply re-adopts it.
      logger.warning("Could not stamp bundled map at '" + worldFolder + "': " + exception.getMessage());
    }
  }

  /** Renames the existing folder to {@code <name>.replaced-<timestamp>}; returns the new path, or null. */
  private Path moveAside(Path target) {
    Path parent = target.getParent();
    if (parent == null) {
      logger.warning("Refusing to replace bundled map at '" + target + "': it has no parent directory.");
      return null;
    }
    String stamp = LocalDateTime.now().format(REPLACED_STAMP);
    Path destination = parent.resolve(target.getFileName() + REPLACED_SUFFIX + stamp);
    // A second refresh inside the same second would collide with the copy just made.
    for (int attempt = 1; Files.exists(destination) && attempt < 100; attempt++) {
      destination = parent.resolve(target.getFileName() + REPLACED_SUFFIX + stamp + "-" + attempt);
    }
    try {
      Files.move(target, destination, StandardCopyOption.ATOMIC_MOVE);
      return destination;
    } catch (IOException atomicFailure) {
      try {
        Files.move(target, destination);
        return destination;
      } catch (IOException exception) {
        logger.warning("Could not move the previous copy of '" + target + "' aside: " + exception.getMessage());
        return null;
      }
    }
  }

  private void restore(Path replaced, Path target) {
    try {
      deleteRecursively(target);
      Files.move(replaced, target);
      logger.warning("Restored the previous copy of '" + target + "' after a failed refresh.");
    } catch (IOException exception) {
      logger.warning("Could not restore '" + target + "' from '" + replaced + "': " + exception.getMessage());
    }
  }

  /**
   * Keeps only the newest {@link #KEEP_REPLACED_COPIES} superseded copies of this map. Iterating on a map
   * (edit → re-export → boot) would otherwise leave a full copy of its region data behind on every single
   * round, and only the most recent one is any use as a safety net.
   */
  private void pruneReplacedCopies(Path target) {
    Path parent = target.getParent();
    if (parent == null) {
      return;
    }
    String prefix = target.getFileName() + REPLACED_SUFFIX;
    try (Stream<Path> siblings = Files.list(parent)) {
      List<Path> replaced = siblings
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith(prefix))
          .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
          .toList();
      for (int index = KEEP_REPLACED_COPIES; index < replaced.size(); index++) {
        Path stale = replaced.get(index);
        deleteRecursively(stale);
        logger.info("Removed superseded map copy '" + stale.getFileName() + "'.");
      }
    } catch (IOException exception) {
      logger.warning("Could not prune superseded copies of '" + target + "': " + exception.getMessage());
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException exception) {
          throw new UncheckedIOException(exception);
        }
      });
    } catch (UncheckedIOException exception) {
      throw exception.getCause();
    }
  }

  private void extractZip(InputStream zipStream, Path targetDir) throws IOException {
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
}
