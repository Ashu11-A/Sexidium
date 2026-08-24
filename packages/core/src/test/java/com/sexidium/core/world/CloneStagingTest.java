package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4 M9: cloning a map template copies tens of megabytes, and it used to do that INSIDE
 * {@code runOnWorldThread} — so the cost (plus, under contention, the wait for the map lock) froze every
 * player on the node, not merely the match that was starting. The control layer now copies the template
 * on its own staging thread and only hops onto the world thread to create the world.
 *
 * <p>Every test here carries its own negative control: the same scenario is run twice against the same
 * fake backend, once with staging supported ({@code backendCloneStagingFolder} returns the destination,
 * as Paper does) and once without (the pre-M9 arrangement, which is also what a backend that never opts
 * in still gets). The assertions are written so that the unstaged run fails them — reverting the hoist
 * therefore breaks these tests rather than quietly passing them.</p>
 */
class CloneStagingTest {
  private static final String TEMPLATE = "tntwar/tnt-wars";

  /** Big enough that the copy is unmistakably longer than scheduling a task, small enough to stay fast. */
  private static final int REGION_FILES = 16;
  private static final int REGION_FILE_BYTES = 768 * 1024;

  @Test
  void theWorldThreadStaysFreeWhileTheTemplateIsCopied(@TempDir Path root) throws Exception {
    Run staged = clone(root.resolve("staged"), true);
    Run unstaged = clone(root.resolve("unstaged"), false);

    // The discriminator: an unrelated task handed to the world thread the instant the clone was requested.
    // With the copy hoisted off that thread, the task runs straight away — BEFORE the match world is even
    // created. With the copy still on it, the task can only run once the copy is finished.
    assertTrue(staged.probeOrder < staged.leaseOrder,
        "the world thread must serve other work while the template is being copied (probe #"
            + staged.probeOrder + ", lease #" + staged.leaseOrder + ")");
    assertTrue(unstaged.probeOrder > unstaged.leaseOrder,
        "negative control: without staging the copy owns the world thread, so the probe can only run"
            + " after the lease (probe #" + unstaged.probeOrder + ", lease #" + unstaged.leaseOrder + ")");
  }

  @Test
  void theWorldThreadIsBusyForAFractionOfTheTimeItUsedToBe(@TempDir Path root) throws Exception {
    Run staged = clone(root.resolve("staged"), true);
    Run unstaged = clone(root.resolve("unstaged"), false);

    System.out.println("[M9] world-thread busy: staged=" + staged.busyMicros() + " us, unstaged="
        + unstaged.busyMicros() + " us (template " + (REGION_FILES * REGION_FILE_BYTES / (1024 * 1024))
        + " MB); copy on the world thread: staged=" + staged.copiedOnWorldThread + ", unstaged="
        + unstaged.copiedOnWorldThread);

    assertTrue(unstaged.copiedOnWorldThread, "negative control: the pre-M9 path copies on the world thread");
    assertFalse(staged.copiedOnWorldThread, "the staged path must not copy on the world thread");
    // Deliberately a ratio, not an absolute budget: a build machine's disk speed is not ours to assume.
    assertTrue(staged.busyNanos * 4 < unstaged.busyNanos,
        "the world thread must be busy for a small fraction of the pre-M9 time, was " + staged.busyMicros()
            + " us against " + unstaged.busyMicros() + " us");
  }

  @Test
  void aStagedCloneIsStillCreatedLikeACloneAndCarriesTheWholeMap(@TempDir Path root) throws Exception {
    Run staged = clone(root.resolve("staged"), true);
    Run unstaged = clone(root.resolve("unstaged"), false);

    // The reason this whole change needed the backend's cooperation: a staged folder must NOT look like
    // "a world that already existed on disk", or the backend skips the template's spawn read and the land
    // spawn — and the world border is centred on whichever of those wins. Both runs must agree.
    assertEquals(unstaged.onDisk, staged.onDisk, "a staged clone must not be mistaken for a pre-existing world");
    assertFalse(staged.onDisk, "the clone branch (spawn read + land spawn) must still run for a staged clone");
    assertTrue(staged.spawnRead, "the template's own spawn must still be read for a staged clone");
    assertTrue(unstaged.spawnRead);
    assertEquals(REGION_FILES, staged.regionFilesInWorld,
        "the created world must hold the complete map, however the copy got there");
    assertEquals(REGION_FILES, unstaged.regionFilesInWorld);
  }

  @Test
  void aRefusedStagedCopyLeavesNothingBehindAndFallsBackToTheWorldThread(@TempDir Path root) throws Exception {
    // region/ exists but is empty: the half-extracted map. The checked copy refuses it, and the folder it
    // was writing into must not survive — a backend adopts a staged folder without re-reading the
    // template, so a torn one would start the match on a map with holes in it.
    Path home = root.resolve("partial");
    Files.createDirectories(home.resolve("worlds").resolve(TEMPLATE).resolve("region"));
    FakeControl control = new FakeControl(home, true);
    try {
      CountDownLatch done = new CountDownLatch(1);
      control.acquireOrCreateClone(TEMPLATE, List.of(), lease -> done.countDown(), done::countDown);
      assertTrue(done.await(30, TimeUnit.SECONDS), "the clone request must complete");

      Path stagingRoot = home.resolve("temp");
      assertTrue(!Files.isDirectory(stagingRoot) || isEmpty(stagingRoot),
          "a refused staged copy must leave no folder behind");
      assertTrue(control.copiedOnWorldThread.get(),
          "with nothing staged, the backend must clone (and diagnose) on the world thread exactly as before");
    } finally {
      control.close();
    }
  }

  // ===== harness ==============================================================================

  private record Run(int probeOrder, int leaseOrder, long busyNanos, boolean copiedOnWorldThread,
      boolean onDisk, boolean spawnRead, int regionFilesInWorld) {
    long busyMicros() {
      return busyNanos / 1_000L;
    }
  }

  /** Requests one clone and reports what the world thread had to do for it. */
  private Run clone(Path home, boolean staging) throws Exception {
    writeTemplate(home.resolve("worlds").resolve(TEMPLATE));
    FakeControl control = new FakeControl(home, staging);
    try {
      CountDownLatch served = new CountDownLatch(1);
      control.acquireOrCreateClone(TEMPLATE, List.of(), lease -> {
        control.leaseOrder.set(control.order.incrementAndGet());
        served.countDown();
      }, served::countDown);
      // Queued the moment the clone is requested — before any copy could plausibly have finished.
      control.runOnWorldThread(() -> control.probeOrder.set(control.order.incrementAndGet()));
      assertTrue(served.await(60, TimeUnit.SECONDS), "the clone request must complete");
      control.drainWorldThread();

      return new Run(control.probeOrder.get(), control.leaseOrder.get(), control.busyNanos.get(),
          control.copiedOnWorldThread.get(), control.onDisk.get(), control.spawnRead.get(),
          countRegionFiles(control.createdWorldFolder));
    } finally {
      control.close();
    }
  }

  private static void writeTemplate(Path template) throws IOException {
    Path region = template.resolve("region");
    Files.createDirectories(region);
    byte[] chunk = new byte[REGION_FILE_BYTES];
    for (int index = 0; index < REGION_FILES; index++) {
      chunk[index] = (byte) index;
      Files.write(region.resolve("r." + index + ".0.mca"), chunk);
    }
    // What the backend re-reads to re-pin the cloned world's spawn; never copied into the clone.
    Files.writeString(template.resolve("level.dat"), "spawn", StandardCharsets.UTF_8);
  }

  private static int countRegionFiles(Path worldFolder) throws IOException {
    Path region = worldFolder == null ? null : worldFolder.resolve("region");
    if (region == null || !Files.isDirectory(region)) {
      return 0;
    }
    try (var entries = Files.list(region)) {
      return (int) entries.count();
    }
  }

  private static boolean isEmpty(Path folder) throws IOException {
    try (var entries = Files.list(folder)) {
      return entries.findAny().isEmpty();
    }
  }

  /**
   * A backend shaped like Paper's: it clones inside {@code backendAcquire}, on the world thread, unless
   * the control layer already staged the folder for it.
   */
  private static final class FakeControl extends AbstractWorldControl {
    private final Path home;
    private final boolean staging;
    private final ExecutorService worldThread =
        Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "fake-world-thread"));

    private final AtomicInteger order = new AtomicInteger();
    private final AtomicInteger probeOrder = new AtomicInteger();
    private final AtomicInteger leaseOrder = new AtomicInteger();
    private final AtomicLong busyNanos = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean copiedOnWorldThread =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean onDisk =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean spawnRead =
        new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Path createdWorldFolder;

    private FakeControl(Path home, boolean staging) {
      super(new PropertiesConfigurationAdapter(), new SilentLogger());
      this.home = home;
      this.staging = staging;
    }

    @Override
    protected void runOnWorldThread(Runnable task) {
      worldThread.execute(() -> {
        long startedAt = System.nanoTime();
        try {
          task.run();
        } finally {
          busyNanos.addAndGet(System.nanoTime() - startedAt);
        }
      });
    }

    private void drainWorldThread() throws InterruptedException {
      CountDownLatch drained = new CountDownLatch(1);
      worldThread.execute(drained::countDown);
      assertTrue(drained.await(30, TimeUnit.SECONDS));
    }

    private void close() {
      shutdown();
      worldThread.shutdownNow();
    }

    @Override protected Path serverHome() { return home; }

    @Override protected Path experiencesDiskRoot() { return home.resolve("experiences"); }

    @Override protected Path lobbyDiskFolder() { return home.resolve("lobby"); }

    @Override protected Path backendTempDiskRoot() { return home.resolve("temp"); }

    @Override
    protected Path backendCloneStagingFolder(WorldRequest request) {
      return staging && request.kind() == WorldKind.CLONE ? folderFor(request) : null;
    }

    private Path folderFor(WorldRequest request) {
      return backendTempDiskRoot().resolve(request.keyPath());
    }

    /** Mirrors PaperWorldControl.backendAcquire: the clone branch, the staged short-circuit, the spawn read. */
    @Override
    protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean createIfMissing) {
      Path folder = folderFor(request);
      boolean staged = request.kind() == WorldKind.CLONE && Files.isDirectory(folder.resolve("region"));
      boolean alreadyOnDisk = !staged && Files.isDirectory(folder.resolve("region"));
      if (request.kind() == WorldKind.CLONE) {
        onDisk.set(alreadyOnDisk);
        if (!alreadyOnDisk && !cloneTemplate(request, folder, staged)) {
          return Optional.empty();
        }
      }
      createdWorldFolder = folder;
      return Optional.of(new FakeHandle(request.runtimeName(), folder));
    }

    private boolean cloneTemplate(WorldRequest request, Path destination, boolean staged) {
      Path source = home.resolve("worlds").resolve(request.templateRuntimeName());
      if (!staged) {
        copiedOnWorldThread.set(true);
        WorldClone.ChunkCopyResult result = MapBundle.underSharedWorldRootLock(worldRoot(), logger, 500L,
            lockHeld -> WorldClone.copyChunkDataChecked(source, destination));
        if (!result.ok()) {
          return false;
        }
      }
      // Runs either way — it is what the world border ends up centred on.
      spawnRead.set(Files.exists(source.resolve("level.dat")));
      return true;
    }

    @Override
    protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
      return Optional.empty();
    }

    @Override protected boolean backendUnload(WorldHandle handle, boolean save) { return true; }

    @Override protected Optional<WorldHandle> backendLobby() { return Optional.empty(); }

    @Override protected List<WorldHandle> backendLoadedTempWorlds() { return List.of(); }
  }

  private static final class SilentLogger implements LoggerAdapter {
    @Override public void info(String message) {}
    @Override public void warning(String message) {}
    @Override public void severe(String message) {}
    @Override public void warning(String message, Throwable throwable) {}
    @Override public void severe(String message, Throwable throwable) {}
  }

  private record FakeHandle(String runtimeName, Path folder) implements WorldHandle {
    @Override public WorldKind kind() { return WorldKind.TEMP; }

    @Override public WorldAdapter adapter() { return new FakeWorld(runtimeName); }

    @Override public Path canonicalFolder() { return folder; }
  }

  private record FakeWorld(String name) implements WorldAdapter {
    @Override public WorldPosition spawnPosition() { return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f); }

    @Override public List<PlayerAdapter> players() { return List.of(); }

    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}

    @Override public void playSound(WorldPosition target, SoundKey soundKey, float volume, float pitch) {}

    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}

    @Override public void resetBorder() {}

    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }

  /** Unused, but keeps the fake honest about the interface the degraded path talks to. */
  @SuppressWarnings("unused")
  private static final class SilentPlayer implements PlayerAdapter {
    @Override public UUID uniqueId() { return UUID.nameUUIDFromBytes("p".getBytes(StandardCharsets.UTF_8)); }
    @Override public String name() { return "P"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return false; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
  }
}
