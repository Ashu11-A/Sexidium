package com.sexidium.core.world;

import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two servers, one world root.
 *
 * <p>These are the tests for the thing that is invisible when it goes wrong: four backends boot against
 * the same jar and the same shared {@code worlds/}, all four read "no map here", all four extract on top
 * of each other, and all four then write the <em>same</em> digest stamp — so the corrupted region files
 * look up to date forever after. What is asserted below is that a seeding pass is now serialised, and
 * that the serialisation is an OS file lock (the only kind that reaches across JVMs) rather than a
 * synchronized block that would do nothing for four separate processes.</p>
 *
 * <p>Threads stand in for processes here: the in-JVM monitor and the file lock are two halves of one
 * mechanism (see {@code MapBundle.SEEDING_MONITORS}), and {@link #takesAnOsLockAnotherJvmWouldBlockOn()}
 * pins the half a second JVM would meet.</p>
 */
class MapBundleLockTest {

  private static final String DIGEST = "a1b2c3";

  @Test
  @DisplayName("two servers seeding the same world root extract the map exactly once")
  void concurrentSeedingExtractsEachMapOnce(@TempDir Path worldsRoot) throws Exception {
    // 150ms inside the extraction is the window an unserialised second server slips into; without the
    // lock both threads pass hasWorldData() and both extract, and the assertions below both fail.
    SlowResources resources = new SlowResources(150);
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars " + DIGEST + "\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("from-bundle"));

    CyclicBarrier bothReady = new CyclicBarrier(2);
    ExecutorService servers = Executors.newFixedThreadPool(2);
    try {
      // Two MapBundle instances, as two JVMs would have: only the world root is shared.
      Future<Integer> first = servers.submit(() -> seedTogether(bothReady, resources, worldsRoot));
      Future<Integer> second = servers.submit(() -> seedTogether(bothReady, resources, worldsRoot));

      int written = first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS);

      assertEquals(1, written, "the map must be extracted by exactly one of the two servers");
      assertEquals(1, resources.peakConcurrentExtractions(),
          "two extractions overlapped: the seeding pass is not serialised");
      assertEquals("from-bundle",
          Files.readString(worldsRoot.resolve("tntwar/tnt-wars/region/r.0.0.mca")));
      assertEquals(DIGEST, Files.readString(worldsRoot.resolve("tntwar/tnt-wars/" + MapBundle.STAMP_FILE)).trim());
    } finally {
      servers.shutdownNow();
    }
  }

  @Test
  @DisplayName("the lock is an OS file lock, so a second JVM waits instead of interleaving")
  void takesAnOsLockAnotherJvmWouldBlockOn(@TempDir Path worldsRoot) throws Exception {
    CountDownLatch seeding = new CountDownLatch(1);
    CountDownLatch mayFinish = new CountDownLatch(1);
    SlowResources resources = new SlowResources(0, seeding, mayFinish);
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars " + DIGEST + "\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("from-bundle"));

    Thread server = new Thread(() -> bundle(resources).seedBundledMaps(worldsRoot), "seeding-server");
    server.start();
    assertTrue(seeding.await(30, TimeUnit.SECONDS), "the seeding pass never started");

    Path lockFile = worldsRoot.resolve(MapBundle.LOCK_FILE);
    assertTrue(Files.isRegularFile(lockFile), "the lock file should exist while a pass is running");
    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
      // A second JVM asking for this region would BLOCK in lock(). Asked from inside the JVM that
      // already owns it, the JDK refuses instead — which is the same fact stated locally: the OS-level
      // lock is held for the whole pass, not merely a Java monitor that a separate process cannot see.
      assertThrows(OverlappingFileLockException.class, channel::tryLock);
    } finally {
      mayFinish.countDown();
      server.join(TimeUnit.SECONDS.toMillis(30));
    }
  }

  @Test
  @DisplayName("a clone waits for a seeding pass and then reads the finished map, never a half-written one")
  void readerWaitsForASeedingPass(@TempDir Path worldsRoot) throws Exception {
    // The writer×reader case, which is the actual production accident: a node deploys a re-exported map
    // while three other nodes keep starting matches. Before this change the reader took no lock at all,
    // walked into the middle of the extraction, and copied a truncated region file WITHOUT failing.
    CountDownLatch extracting = new CountDownLatch(1);
    CountDownLatch mayFinish = new CountDownLatch(1);
    SlowResources resources = new SlowResources(0, extracting, mayFinish);
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars " + DIGEST + "\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("from-bundle"));

    Thread writer = new Thread(() -> bundle(resources).seedBundledMaps(worldsRoot), "seeding-server");
    writer.start();
    assertTrue(extracting.await(30, TimeUnit.SECONDS), "the seeding pass never started");

    AtomicReference<String> seen = new AtomicReference<>();
    CountDownLatch readerDone = new CountDownLatch(1);
    Thread reader = new Thread(() -> {
      MapBundle.underSharedWorldRootLock(worldsRoot, new StdoutLoggerAdapter("Test"), MapBundle.WAIT_FOREVER,
          lockHeld -> {
            seen.set(lockHeld + ":" + readRegion(worldsRoot));
            return null;
          });
      readerDone.countDown();
    }, "match-clone");
    reader.start();

    assertFalse(readerDone.await(300, TimeUnit.MILLISECONDS),
        "the clone read the map while it was being extracted: the reader is not taking the lock");
    mayFinish.countDown();
    assertTrue(readerDone.await(30, TimeUnit.SECONDS), "the clone never got the lock");
    writer.join(TimeUnit.SECONDS.toMillis(30));

    // It waited AND it got the real thing: no partial tree, no missing file.
    assertEquals("true:from-bundle", seen.get());
  }

  @Test
  @DisplayName("two clones on one node run at the same time, and still hold a real OS lock")
  void concurrentReadersDoNotSerialise(@TempDir Path worldsRoot) throws Exception {
    // The latency half of the change. Match starts are frequent; if two of them queued behind each other
    // the fix for a rare corruption would have cost every player a slower start. They must overlap — and
    // overlap while the OS lock is genuinely held, which is the part a naive implementation gets wrong:
    // a second FileLock on the same region from the same JVM throws OverlappingFileLockException, so the
    // obvious "lock per reader" silently degrades to no lock at all.
    Files.createDirectories(worldsRoot);
    CyclicBarrier bothInside = new CyclicBarrier(2);
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch mayFinish = new CountDownLatch(1);
    ExecutorService clones = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> clone = () -> MapBundle.underSharedWorldRootLock(
          worldsRoot, new StdoutLoggerAdapter("Test"), MapBundle.WAIT_FOREVER, lockHeld -> {
            entered.countDown();
            try {
              // Serialised readers can never both reach this line: the first would wait here forever
              // while the second waits for a lock it will not get until the first returns.
              bothInside.await(10, TimeUnit.SECONDS);
              mayFinish.await(10, TimeUnit.SECONDS);
            } catch (Exception broken) {
              throw new IllegalStateException("the two clones did not overlap", broken);
            }
            return lockHeld;
          });
      Future<Boolean> first = clones.submit(clone);
      Future<Boolean> second = clones.submit(clone);

      assertTrue(entered.await(30, TimeUnit.SECONDS), "both clones should be inside the copy at once");
      try (FileChannel channel = FileChannel.open(worldsRoot.resolve(MapBundle.LOCK_FILE),
          StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        // Two readers are inside, and the JVM holds exactly ONE shared lock covering them both: asking
        // for an overlapping region from this same JVM is refused (a seeding pass in another JVM would
        // block instead, which is the same fact seen from outside).
        assertThrows(OverlappingFileLockException.class, channel::tryLock);
      } finally {
        mayFinish.countDown();
      }
      assertTrue(first.get(30, TimeUnit.SECONDS), "the first clone should have held the shared lock");
      assertTrue(second.get(30, TimeUnit.SECONDS), "the second clone should have held the shared lock");
    } finally {
      clones.shutdownNow();
    }

    // ...and once both are out, the lock is gone: the refcount dropped to zero rather than leaking a
    // descriptor that would block the next seeding pass forever.
    try (FileChannel channel = FileChannel.open(worldsRoot.resolve(MapBundle.LOCK_FILE),
        StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      FileLock exclusive = channel.tryLock();
      assertNotNull(exclusive, "the shared lock outlived its last reader");
      exclusive.release();
    }
  }

  @Test
  @DisplayName("a clone gives up on the lock inside its budget instead of stalling the world thread")
  void readerGivesUpWithinItsBudget(@TempDir Path worldsRoot) throws Exception {
    // cloneTemplate runs on the Paper world thread, where an unbounded wait is not a wait but an outage
    // for every player on the node. Past the budget the copy runs unlocked-but-verified, which is the
    // lesser of the two evils and is what lockHeld=false tells the body.
    CountDownLatch extracting = new CountDownLatch(1);
    CountDownLatch mayFinish = new CountDownLatch(1);
    SlowResources resources = new SlowResources(0, extracting, mayFinish);
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars " + DIGEST + "\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("from-bundle"));

    Thread writer = new Thread(() -> bundle(resources).seedBundledMaps(worldsRoot), "seeding-server");
    writer.start();
    assertTrue(extracting.await(30, TimeUnit.SECONDS), "the seeding pass never started");
    try {
      long startedAt = System.nanoTime();
      Boolean held = MapBundle.underSharedWorldRootLock(worldsRoot, new StdoutLoggerAdapter("Test"), 150L,
          lockHeld -> lockHeld);
      long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

      assertFalse(held, "the lock was held by the seeding pass, so the clone must be told it went unlocked");
      assertTrue(waitedMillis < 5_000L, "the clone stalled for " + waitedMillis + "ms waiting for the lock");
    } finally {
      mayFinish.countDown();
      writer.join(TimeUnit.SECONDS.toMillis(30));
    }
  }

  @Test
  @DisplayName("a refreshed map is inflated aside and renamed in, so it is never half-written in place")
  void refreshPublishesByRename(@TempDir Path worldsRoot) throws Exception {
    // Defence in depth for the moment the lock is unavailable (a filesystem without advisory locks, or a
    // reader past its budget): the live folder is whole until a rename replaces it, so the window in
    // which a reader can see a mixture is a rename rather than a 1-2s inflate. NOT atomic publication —
    // POSIX rename cannot replace a non-empty directory, so old-aside + new-in remain two steps.
    SlowResources first = new SlowResources(0);
    first.put("bundled/maps/manifest.txt", "tntwar/tnt-wars " + DIGEST + "\n");
    first.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    assertEquals(1, bundle(first).seedBundledMaps(worldsRoot));

    CountDownLatch inflating = new CountDownLatch(1);
    CountDownLatch mayFinish = new CountDownLatch(1);
    SlowResources second = new SlowResources(0, inflating, mayFinish);
    second.put("bundled/maps/manifest.txt", "tntwar/tnt-wars deadbeef\n");
    second.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v2"));

    Thread writer = new Thread(() -> bundle(second).seedBundledMaps(worldsRoot), "refreshing-server");
    writer.start();
    assertTrue(inflating.await(30, TimeUnit.SECONDS), "the refresh never started");
    // Mid-inflate the live map is still entirely v1 — the old code would already have truncated it.
    assertEquals("v1", readRegion(worldsRoot));
    mayFinish.countDown();
    writer.join(TimeUnit.SECONDS.toMillis(30));

    assertEquals("v2", readRegion(worldsRoot));
    try (Stream<Path> siblings = Files.list(worldsRoot.resolve("tntwar"))) {
      assertTrue(siblings.noneMatch(path -> path.getFileName().toString().contains(".incoming-")),
          "the staging folder should be renamed away, not left behind");
    }
  }

  private static String readRegion(Path worldsRoot) {
    try {
      return Files.readString(worldsRoot.resolve("tntwar/tnt-wars/region/r.0.0.mca"));
    } catch (IOException exception) {
      return "<unreadable: " + exception + ">";
    }
  }

  @Test
  @DisplayName("the lock file is left behind and is never mistaken for a map")
  void lockFileSurvivesWithoutDisturbingSeeding(@TempDir Path worldsRoot) throws Exception {
    SlowResources resources = new SlowResources(0);
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars " + DIGEST + "\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("from-bundle"));

    assertEquals(1, bundle(resources).seedBundledMaps(worldsRoot));
    // Second boot over a leftover lock file: still a no-op, and the lock is reusable.
    assertEquals(0, bundle(resources).seedBundledMaps(worldsRoot));
    assertTrue(Files.isRegularFile(worldsRoot.resolve(MapBundle.LOCK_FILE)));
    assertEquals(0L, Files.size(worldsRoot.resolve(MapBundle.LOCK_FILE)), "the lock file holds no content");
  }

  // ---------------------------------------------------------------------------------------------------
  // The network layout: run/<node>/worlds/<bundle> is a SYMLINK into run/shared/maps/<bundle>.
  // ---------------------------------------------------------------------------------------------------

  /**
   * Builds the deployed layout under {@code root}: {@code shared/maps} holding a seeded v1 map, and
   * {@code node/worlds/tntwar} symlinked onto {@code shared/maps/tntwar}. Returns {shared/maps, node/worlds}.
   */
  private static Path[] networkLayout(Path root) throws Exception {
    Path sharedMaps = Files.createDirectories(root.resolve("shared/maps"));
    Path nodeWorlds = Files.createDirectories(root.resolve("node/worlds"));

    SlowResources first = new SlowResources(0);
    first.put("bundled/maps/manifest.txt", "tntwar/tnt-wars " + DIGEST + "\n");
    first.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    assertEquals(1, bundle(first).seedBundledMaps(sharedMaps));

    try {
      // The provisioner's link: the node sees `worlds/tntwar`, the bytes live in the shared tree.
      Files.createSymbolicLink(nodeWorlds.resolve("tntwar"), sharedMaps.resolve("tntwar"));
    } catch (UnsupportedOperationException | IOException noSymlinks) {
      org.junit.jupiter.api.Assumptions.abort("this filesystem does not support symlinks: " + noSymlinks);
    }
    return new Path[] {sharedMaps, nodeWorlds};
  }

  /**
   * Starts a refresh to v2 that parks mid-inflate. The caller releases it with {@code mayFinish} and must
   * JOIN the returned thread before the test ends: an inflate still running during {@code @TempDir}
   * teardown races the cleanup and fails the test on a directory it cannot delete.
   */
  private static Thread startBlockedRefresh(Path sharedMaps, CountDownLatch inflating,
      CountDownLatch mayFinish) throws Exception {
    SlowResources second = new SlowResources(0, inflating, mayFinish);
    second.put("bundled/maps/manifest.txt", "tntwar/tnt-wars deadbeef\n");
    second.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v2"));
    Thread writer = new Thread(() -> bundle(second).seedBundledMaps(sharedMaps), "seeding-init-container");
    writer.setDaemon(true);
    writer.start();
    assertTrue(inflating.await(30, TimeUnit.SECONDS), "the seeding pass never started");
    return writer;
  }

  @Test
  @DisplayName("a clone through a symlinked bundle waits for the writer seeding the SHARED map tree")
  void readerThroughASymlinkExcludesTheSharedTreeWriter(@TempDir Path root) throws Exception {
    // The furo this test exists for: the writer (the init container, world root = run/shared/maps) locks
    // run/shared/maps/.map-bundle.lock, while a node cloning run/<node>/worlds/tntwar used to lock
    // run/<node>/worlds/.map-bundle.lock. Two different files — so on the ONE layout the whole network
    // deploy is built on, reader and writer did not exclude each other at all.
    Path[] layout = networkLayout(root);
    Path sharedMaps = layout[0];
    Path nodeWorlds = layout[1];
    Path template = nodeWorlds.resolve("tntwar/tnt-wars");

    assertEquals(sharedMaps.toRealPath(), MapBundle.lockRootFor(nodeWorlds, template),
        "the lock must be taken where the bytes are, not where the node's path says they are");

    CountDownLatch inflating = new CountDownLatch(1);
    CountDownLatch mayFinish = new CountDownLatch(1);
    Thread writer = startBlockedRefresh(sharedMaps, inflating, mayFinish);

    AtomicReference<String> seen = new AtomicReference<>();
    CountDownLatch readerDone = new CountDownLatch(1);
    Thread reader = new Thread(() -> {
      MapBundle.underSharedWorldRootLock(nodeWorlds, template, new StdoutLoggerAdapter("Test"),
          MapBundle.WAIT_FOREVER, lockHeld -> {
            seen.set(lockHeld + ":" + readRegionAt(template));
            return null;
          });
      readerDone.countDown();
    }, "match-clone");
    reader.setDaemon(true);
    reader.start();

    assertFalse(readerDone.await(500, TimeUnit.MILLISECONDS),
        "the clone read a map that the shared-tree writer was mid-way through replacing");
    mayFinish.countDown();
    assertTrue(readerDone.await(30, TimeUnit.SECONDS), "the clone never got the lock");
    writer.join(TimeUnit.SECONDS.toMillis(30));

    // It waited for the writer AND it saw the finished v2 through the symlink.
    assertEquals("true:v2", seen.get());
    assertFalse(Files.exists(nodeWorlds.resolve(MapBundle.LOCK_FILE)),
        "the reader must not create a second, private lock file in the node's nominal world root");
    assertTrue(Files.isRegularFile(sharedMaps.resolve(MapBundle.LOCK_FILE)));
  }

  @Test
  @DisplayName("NEGATIVE CONTROL: locking the nominal root lets the same clone walk straight into the refresh")
  void nominalRootLockDoesNotExcludeTheSharedTreeWriter(@TempDir Path root) throws Exception {
    // Exactly the scenario above, run against the pre-fix behaviour — which is still reachable through the
    // template-less overload, since that is what it always did: lock the world root it was handed. If this
    // ever starts blocking, the test above has stopped proving anything.
    Path[] layout = networkLayout(root);
    Path sharedMaps = layout[0];
    Path nodeWorlds = layout[1];
    Path template = nodeWorlds.resolve("tntwar/tnt-wars");

    CountDownLatch inflating = new CountDownLatch(1);
    CountDownLatch mayFinish = new CountDownLatch(1);
    Thread writer = startBlockedRefresh(sharedMaps, inflating, mayFinish);
    try {
      AtomicReference<String> seen = new AtomicReference<>();
      CountDownLatch readerDone = new CountDownLatch(1);
      Thread reader = new Thread(() -> {
        MapBundle.underSharedWorldRootLock(nodeWorlds, new StdoutLoggerAdapter("Test"),
            MapBundle.WAIT_FOREVER, lockHeld -> {
              seen.set(lockHeld + ":" + readRegionAt(template));
              return null;
            });
        readerDone.countDown();
      }, "match-clone-unfixed");
      reader.setDaemon(true);
      reader.start();

      assertTrue(readerDone.await(10, TimeUnit.SECONDS),
          "the old behaviour was expected to sail through the writer's lock, not wait for it");
      // ...and it believed it was safe (lockHeld=true) while the writer held a different file entirely.
      assertTrue(seen.get().startsWith("true:"),
          "the nominal-root lock was taken and reported held: " + seen.get());
      assertTrue(Files.isRegularFile(nodeWorlds.resolve(MapBundle.LOCK_FILE)),
          "the pre-fix path locks the node's own file, which is the whole bug");
    } finally {
      mayFinish.countDown();
      writer.join(TimeUnit.SECONDS.toMillis(30));
    }
  }

  @Test
  @DisplayName("two clones of the same symlinked bundle still overlap")
  void concurrentReadersOfASymlinkedBundleDoNotSerialise(@TempDir Path root) throws Exception {
    // Resolving the lock root must not accidentally hand the two readers different RootLock entries (which
    // would break the refcounted OS lock) nor serialise them (which would tax every match start).
    Path[] layout = networkLayout(root);
    Path nodeWorlds = layout[1];
    Path template = nodeWorlds.resolve("tntwar/tnt-wars");

    CyclicBarrier bothInside = new CyclicBarrier(2);
    CountDownLatch entered = new CountDownLatch(2);
    CountDownLatch mayFinish = new CountDownLatch(1);
    ExecutorService clones = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> clone = () -> MapBundle.underSharedWorldRootLock(
          nodeWorlds, template, new StdoutLoggerAdapter("Test"), MapBundle.WAIT_FOREVER, lockHeld -> {
            entered.countDown();
            try {
              bothInside.await(10, TimeUnit.SECONDS);
              mayFinish.await(10, TimeUnit.SECONDS);
            } catch (Exception broken) {
              throw new IllegalStateException("the two clones did not overlap", broken);
            }
            return lockHeld;
          });
      Future<Boolean> first = clones.submit(clone);
      Future<Boolean> second = clones.submit(clone);

      assertTrue(entered.await(30, TimeUnit.SECONDS), "both clones should be inside the copy at once");
      mayFinish.countDown();
      assertTrue(first.get(30, TimeUnit.SECONDS), "the first clone should have held the shared lock");
      assertTrue(second.get(30, TimeUnit.SECONDS), "the second clone should have held the shared lock");
    } finally {
      clones.shutdownNow();
    }
  }

  @Test
  @DisplayName("a template that is missing, outside the root, or renamed by its link falls back to the root")
  void lockRootFallsBackInsteadOfThrowing(@TempDir Path root) throws Exception {
    Path[] layout = networkLayout(root);
    Path sharedMaps = layout[0];
    Path nodeWorlds = layout[1];
    Path nominal = nodeWorlds.toRealPath();

    // Missing template: toRealPath throws, and this path must keep flowing into the caller's own SEVERE
    // missing-map diagnostic rather than becoming a new exception out of the lock helper.
    assertEquals(nominal, MapBundle.lockRootFor(nodeWorlds, nodeWorlds.resolve("nope/gone")));
    // Outside the root entirely.
    assertEquals(nominal, MapBundle.lockRootFor(nodeWorlds, root.resolve("elsewhere")));
    // No template named at all (the template-less overload, and the writer's own root).
    assertEquals(nominal, MapBundle.lockRootFor(nodeWorlds, null));
    assertEquals(sharedMaps.toRealPath(), MapBundle.lockRootFor(sharedMaps, null));
    // A link that RENAMES as it redirects: walking up blind would invent an unrelated directory and drop a
    // lock file into it, so the fallback keeps it inside the root we were given.
    Files.createSymbolicLink(nodeWorlds.resolve("aliased"), sharedMaps.resolve("tntwar/tnt-wars"));
    assertEquals(nominal, MapBundle.lockRootFor(nodeWorlds, nodeWorlds.resolve("aliased")));
    // And the plain case (standalone / NeoForge / every existing test): unchanged, the root itself.
    assertEquals(sharedMaps.toRealPath(), MapBundle.lockRootFor(sharedMaps, sharedMaps.resolve("tntwar/tnt-wars")));
  }

  @Test
  @DisplayName("an intermediate symlink resolves to the same shared root as a leaf one")
  void lockRootFollowsTheBundleNotTheRoot(@TempDir Path root) throws Exception {
    // Two bundles, two homes: one linked into the shared tree, one still a local copy. The lock follows
    // each bundle to where its bytes actually are.
    Path[] layout = networkLayout(root);
    Path sharedMaps = layout[0];
    Path nodeWorlds = layout[1];
    Files.createDirectories(nodeWorlds.resolve("combat/arena/region"));

    assertEquals(sharedMaps.toRealPath(),
        MapBundle.lockRootFor(nodeWorlds, nodeWorlds.resolve("tntwar/tnt-wars")),
        "the symlinked bundle locks in the shared tree");
    assertEquals(nodeWorlds.toRealPath(),
        MapBundle.lockRootFor(nodeWorlds, nodeWorlds.resolve("combat/arena")),
        "the local copy still locks in the node's own root");
  }

  private static String readRegionAt(Path template) {
    try {
      return Files.readString(template.resolve("region/r.0.0.mca"));
    } catch (IOException exception) {
      return "<unreadable: " + exception + ">";
    }
  }

  private static int seedTogether(CyclicBarrier bothReady, ResourceAdapter resources, Path worldsRoot)
      throws Exception {
    bothReady.await(30, TimeUnit.SECONDS);
    return bundle(resources).seedBundledMaps(worldsRoot);
  }

  private static MapBundle bundle(ResourceAdapter resources) {
    return new MapBundle(resources, new StdoutLoggerAdapter("Test"));
  }

  /** A minimal world zip: level.dat at the root plus one region file carrying a marker payload. */
  private static byte[] worldZip(String regionMarker) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("level.dat"));
      zip.write("level".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("region/r.0.0.mca"));
      zip.write(regionMarker.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  /**
   * Resources whose map zips take their time and count how many callers are inside an extraction at
   * once — the overlap this whole change exists to make impossible.
   */
  private static final class SlowResources implements ResourceAdapter {
    private final Map<String, byte[]> entries = new HashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final long delayMillis;
    private final CountDownLatch entered;
    private final CountDownLatch mayFinish;

    SlowResources(long delayMillis) {
      this(delayMillis, null, null);
    }

    SlowResources(long delayMillis, CountDownLatch entered, CountDownLatch mayFinish) {
      this.delayMillis = delayMillis;
      this.entered = entered;
      this.mayFinish = mayFinish;
    }

    void put(String path, String content) {
      entries.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    void put(String path, byte[] content) {
      entries.put(path, content);
    }

    int peakConcurrentExtractions() {
      return peak.get();
    }

    @Override public Optional<InputStream> openResource(String resourcePath) {
      byte[] data = entries.get(resourcePath);
      if (data == null) {
        return Optional.empty();
      }
      if (!resourcePath.endsWith(".zip")) {
        return Optional.of(new ByteArrayInputStream(data));
      }
      peak.accumulateAndGet(active.incrementAndGet(), Math::max);
      if (entered != null) {
        entered.countDown();
      }
      await();
      // MapBundle closes the stream when the extraction ends, which is where the count goes back down.
      return Optional.of(new FilterInputStream(new ByteArrayInputStream(data)) {
        @Override public void close() throws IOException {
          active.decrementAndGet();
          super.close();
        }
      });
    }

    private void await() {
      try {
        if (mayFinish != null) {
          mayFinish.await(30, TimeUnit.SECONDS);
        }
        if (delayMillis > 0) {
          Thread.sleep(delayMillis);
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
