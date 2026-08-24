package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.world.AbstractWorldControl;
import com.sexidium.core.world.WorldHandle;
import com.sexidium.core.world.WorldKey;
import com.sexidium.core.world.WorldKind;
import com.sexidium.core.world.WorldRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The experience backup, end to end: real folders on a real disk, a real {@link AbstractWorldControl}
 * doing the copy on its staging thread, a real {@link ExperienceStateStore} re-pointing the sidecar and
 * a real SQLite {@link Database} holding the row.
 *
 * <p>Nothing here is stubbed at the seam under test, deliberately. Every failure this feature can
 * actually have is a persistence failure — a folder that is not there, a counter that did not travel, a
 * saved position pointing at the world it was copied FROM, a row that names bytes nobody wrote. An
 * in-memory stand-in would have agreed with every broken version of this.</p>
 */
class ExperienceBackupTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private static final UUID OWNER = UUID.randomUUID();
  private static final UUID PLAYER = UUID.fromString("0000cafe-0000-4000-8000-00000000beef");

  @TempDir
  Path tmp;

  private Path home;
  private BackupWorldControl worlds;
  private ExperienceManager experiences;
  private ExperienceStateStore store;
  private ExperienceBackupService backups;
  private ExperienceManager.Experience source;

  @BeforeEach
  void setUp() throws Exception {
    home = tmp.resolve("server");
    worlds = new BackupWorldControl(home);
    experiences = new ExperienceManager(SILENT, new Database(new File(tmp.toFile(), "backup.db")));
    store = new ExperienceStateStore(worlds.experiencesSubdir(), SILENT);
    // Single node: nothing is running a match, and nothing is held anywhere else.
    backups = new ExperienceBackupService(new Adapter(worlds), experiences, store, key -> false,
        key -> false);
    source = experiences.create(OWNER, "Ashu11a", List.of("deathresets"), "Death Resets", 1_000L);
    assertNotNull(source, "the fixture needs a stored experience");
  }

  @AfterEach
  void tearDown() {
    worlds.shutdown();
  }

  // ===== the happy path ========================================================================

  @Test
  @DisplayName("a backup is an experience of its own, with every dimension, counter and player save")
  void aBackupCarriesTheWholeWorld() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());
    writeDimension(source.key().key() + "_nether", null, null);
    writeDimension(source.key().key() + "_end", null, null);

    assertEquals(ExperienceBackup.Outcome.CREATED, run(OWNER, source.id()));

    ExperienceManager.Experience backup = onlyBackup();
    assertEquals(source.id(), backup.backupOf(), "a backup remembers where it came from");
    assertTrue(backup.isBackup());

    // ----- a different RUN, not a later generation of the same one ----------------------------
    WorldKey key = backup.key();
    assertEquals(0, key.generation(), "a backup is generation 0 of a run of its own");
    assertFalse(key.sameRun(source.key()),
        "sharing the base would make the lineage checks treat the copy as a generation of the"
            + " original — and refuse legitimate opens of the original");
    assertNotNull(experiences.byWorld(key), "byWorld must resolve the copy before anything opens it");

    // ----- all three dimensions ----------------------------------------------------------------
    assertTrue(Files.isDirectory(folder(key.key()).resolve("region")));
    assertTrue(Files.isDirectory(folder(key.key() + "_nether").resolve("region")));
    assertTrue(Files.isDirectory(folder(key.key() + "_end").resolve("region")));

    // ----- what copyChunkData would have dropped -----------------------------------------------
    assertEquals("keep-spawn: false",
        Files.readString(folder(key.key()).resolve("paper-world.yml"), StandardCharsets.UTF_8));

    // ----- every counter Death Resets wrote down ------------------------------------------------
    Map<String, String> copied = state(key.key());
    assertEquals("7", copied.get("deathresets.resets"));
    assertEquals("120", copied.get("deathresets.daybaseline"));
    assertEquals("31", copied.get("deathresets.days"));
    assertEquals("4512", copied.get("stats.run.seconds.total"));
    assertEquals("2100", copied.get("stats.run.seconds.player." + PLAYER));
    assertEquals("19", copied.get("stats.run.deaths.total"));
    assertEquals("6", copied.get("stats.run.deaths.player." + PLAYER),
        "the per-player keys ARE the roster: losing them loses who was in the run");

    // ----- and the ones a RESET would deliberately drop ------------------------------------------
    // The anti-regression against reusing StateCarry here. A reset builds new terrain, so a marker
    // saying "this world's island is already built" must not travel; a backup IS the same terrain, so
    // dropping it would hand the owner a copy that rebuilds its island on top of itself.
    assertEquals("1", copied.get("classicskyblock.built"));
    assertEquals("88214", copied.get("stats.blocks.broken"));
    Map<String, String> carriedByAReset =
        StateCarry.select(state(source.key().key()), Set.of("deathresets.*", "stats.run.*"));
    assertFalse(carriedByAReset.containsKey("classicskyblock.built"),
        "negative control: the reset allowlist really does drop world-scoped markers");
    assertFalse(carriedByAReset.containsKey("stats.blocks.broken"));

    // ----- the player save: coordinates KEPT, world re-pointed ----------------------------------
    Map<String, String> saved = playerFile(key.key());
    assertEquals("128.5", saved.get("x"), "the terrain is identical, so the position must survive");
    assertEquals("71.0", saved.get("y"));
    assertEquals("-64.25", saved.get("z"));
    assertEquals("42", saved.get("xp"));
    assertEquals("experiences_" + key.key(), saved.get("world"),
        "left naming the SOURCE world, the entry guard rejects the position and dumps everyone at"
            + " the safe spawn — the exact opposite of what carryPlayerSnapshots does for a reset");

    // ----- and the source is untouched -----------------------------------------------------------
    assertEquals("experiences_" + source.key().key(), playerFile(source.key().key()).get("world"));
    assertEquals("7", state(source.key().key()).get("deathresets.resets"));
  }

  @Test
  @DisplayName("a backup does not spend one of the owner's experience slots")
  void backupsAreExemptFromThePerPlayerCap() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());
    assertEquals(1, experiences.countByOwner(OWNER));

    assertEquals(ExperienceBackup.Outcome.CREATED, run(OWNER, source.id()));

    assertEquals(1, experiences.countByOwner(OWNER),
        "an owner at their cap must still be able to back a world up — that is when it matters most");
    assertEquals(1, experiences.countBackupsOf(source.id()));
    assertEquals(2, experiences.byOwner(OWNER).size(), "the copy is still one of their worlds to see");
    assertEquals(List.of(onlyBackup().id()),
        experiences.backupsOf(source.id()).stream().map(ExperienceManager.Experience::id).toList());
  }

  @Test
  @DisplayName("an experience with no Nether or End copies what it has and calls that complete")
  void aMissingSiblingIsNotAFailure() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());

    assertEquals(ExperienceBackup.Outcome.CREATED, run(OWNER, source.id()));

    WorldKey key = onlyBackup().key();
    assertTrue(Files.isDirectory(folder(key.key())));
    assertFalse(Files.exists(folder(key.key() + "_nether")),
        "inventing a Nether folder would be inventing terrain the original never had");
    assertFalse(Files.exists(folder(key.key() + "_end")));
  }

  @Test
  @DisplayName("a sibling folder that exists but holds no chunk data is skipped, not a refusal")
  void aChunklessSiblingDoesNotDoomTheBackup() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());
    // The two shapes a crash leaves behind. `_nether` got its folder and its data/ sidecar but the
    // server died before a single region file was written; `_end` got as far as an empty region/.
    // Neither holds terrain, and `backendExistsOnDisk` already calls both of them "not on disk" —
    // so refusing them would make this experience permanently un-backup-able, for ever, with the
    // owner seeing a bare FAILED and having nothing they could possibly do about it.
    Files.createDirectories(folder(source.key().key() + "_nether").resolve("data/minecraft"));
    Files.writeString(folder(source.key().key() + "_nether").resolve("data/minecraft/world_gen_settings.dat"),
        "seed", StandardCharsets.UTF_8);
    Files.createDirectories(folder(source.key().key() + "_end").resolve("region"));

    assertEquals(ExperienceBackup.Outcome.CREATED, run(OWNER, source.id()));

    WorldKey key = onlyBackup().key();
    // The overworld — the only dimension that actually had blocks — travelled whole.
    assertEquals("blocks-of-" + source.key().key(),
        Files.readString(folder(key.key()).resolve("region/r.0.0.mca"), StandardCharsets.UTF_8));
    assertEquals("7", state(key.key()).get("deathresets.resets"),
        "the sidecar has to travel with it: skipping a sibling must not turn into a partial copy");
    // And the empty dimensions are simply absent, exactly as they are for an experience that never
    // had a Nether at all. An empty folder in the copy would be a dimension with no terrain in it.
    assertFalse(Files.exists(folder(key.key() + "_nether")));
    assertFalse(Files.exists(folder(key.key() + "_end")));
  }

  // ===== the refusals ==========================================================================

  @Test
  @DisplayName("a LOADED world is copied, and every open dimension is flushed before the read")
  void aLoadedWorldIsFlushedAndCopied() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());
    writeDimension(source.key().key() + "_nether", null, null);
    writeDimension(source.key().key() + "_end", null, null);
    // All three open: the owner is standing in one of them, which is the case this used to refuse.
    worlds.loaded.add("experiences/" + source.key().key());
    worlds.loaded.add("experiences/" + source.key().key() + "_nether");
    worlds.loaded.add("experiences/" + source.key().key() + "_end");

    assertEquals(ExperienceBackup.Outcome.CREATED, run(OWNER, source.id()));

    // The OVERWORLD AND BOTH LINKED DIMENSIONS. Flushing only the world the player is standing in
    // would leave a Nether the copy takes from whatever the last autosave happened to write.
    List<String> saves = worlds.log.stream().filter(line -> line.startsWith("save ")).toList();
    assertEquals(List.of("save experiences/" + source.key().key(),
            "save experiences/" + source.key().key() + "_nether",
            "save experiences/" + source.key().key() + "_end"),
        saves);
    // And every one of them before the first byte was read: a save issued after the copy is a write
    // racing the reader, which is worse than not saving at all.
    int firstCopy = 0;
    while (firstCopy < worlds.log.size() && !worlds.log.get(firstCopy).startsWith("copy ")) {
      firstCopy++;
    }
    assertEquals(3, firstCopy, "every flush must land before the first copy: " + worlds.log);
    assertTrue(firstCopy < worlds.log.size(), "the copy must have run: " + worlds.log);
    assertEquals("7", state(onlyBackup().key().key()).get("deathresets.resets"));
  }

  @Test
  @DisplayName("a live match on the world does not stop the copy either")
  void aLiveMatchIsCopiedToo() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());
    ExperienceBackupService withMatch = new ExperienceBackupService(new Adapter(worlds), experiences,
        store, key -> key.equals(source.key()), key -> false);

    assertEquals(ExperienceBackup.Outcome.CREATED,
        await(tell -> withMatch.backup(OWNER, source.id(), tell)),
        "a match is the loaded question asked of a world the lease layer has handed back; the two"
            + " were always one gate and they are dropped together");
  }

  @Test
  @DisplayName("allow-live-copy: false puts the old refusal back, and copies nothing")
  void theOperatorCanHaveTheOldStrictnessBack() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());
    worlds.loaded.add("experiences/" + source.key().key());
    Adapter strict = new Adapter(worlds);
    strict.configuration().set("worlds.experiences.allow-live-copy", false);
    ExperienceBackupService engine = new ExperienceBackupService(strict, experiences, store,
        key -> false, key -> false);

    assertEquals(ExperienceBackup.Outcome.BUSY,
        await(tell -> engine.backup(OWNER, source.id(), tell)));

    assertEquals(Set.of(source.key().key()), worldFolders(), "a refusal writes nothing at all");
    assertTrue(experiences.backupsOf(source.id()).isEmpty());
    assertTrue(worlds.log.isEmpty(), "and does not even flush a world it is not going to read");
  }

  @Test
  @DisplayName("somebody else's experience is not theirs to copy")
  void aStrangerIsRefused() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());

    assertEquals(ExperienceBackup.Outcome.NOT_OWNER, run(UUID.randomUUID(), source.id()));
    // "No such experience" and "not yours" are one answer on purpose.
    assertEquals(ExperienceBackup.Outcome.NOT_OWNER, run(OWNER, "deadbeef"));
    assertTrue(worldFolders().equals(Set.of(source.key().key())));
  }

  @Test
  @DisplayName("a copy that cannot complete leaves no folder, no staging leftover and no row")
  void aFailedCopyLeavesNothing() throws Exception {
    // region/ exists but holds no chunk file: the half-written folder. The checked copy refuses it,
    // and what must NOT survive is a destination folder a registry row could later be written for.
    Files.createDirectories(folder(source.key().key()).resolve("region"));

    assertEquals(ExperienceBackup.Outcome.FAILED, run(OWNER, source.id()));

    assertEquals(Set.of(source.key().key()), worldFolders(),
        "a half-written backup folder must never survive — staging is deleted, nothing is published");
    assertTrue(experiences.backupsOf(source.id()).isEmpty(),
        "a row naming bytes nobody wrote is worse than no backup at all: the entry path would then"
            + " generate a world into it");
    assertEquals(1, experiences.byOwner(OWNER).size());
  }

  @Test
  @DisplayName("an experience with no folder on this node is refused rather than recorded empty")
  void nothingToCopyIsAFailure() throws Exception {
    assertEquals(ExperienceBackup.Outcome.FAILED, run(OWNER, source.id()));
    assertTrue(experiences.backupsOf(source.id()).isEmpty());
  }

  @Test
  @DisplayName("the per-experience cap refuses the fourth copy instead of deleting the first")
  void theCapRefusesRatherThanRotates() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());

    for (int copy = 1; copy <= ExperienceBackupService.DEFAULT_MAX_BACKUPS; copy++) {
      assertEquals(ExperienceBackup.Outcome.CREATED, run(OWNER, source.id()), "copy #" + copy);
    }
    List<ExperienceManager.Experience> kept = experiences.backupsOf(source.id());

    assertEquals(ExperienceBackup.Outcome.LIMIT_REACHED, run(OWNER, source.id()));
    assertEquals(kept.stream().map(ExperienceManager.Experience::id).toList(),
        experiences.backupsOf(source.id()).stream().map(ExperienceManager.Experience::id).toList(),
        "which copy to let go of is the owner's decision, never this code's");
    for (ExperienceManager.Experience backup : kept) {
      assertTrue(Files.isDirectory(folder(backup.key().key())), "and no folder was reclaimed either");
    }
  }

  // ===== the world-layer seam on its own =======================================================

  @Test
  @DisplayName("a key is only free when nothing claims it — including its linked dimensions")
  void aKeyIsFreeOnlyWhenEveryDimensionIs() throws Exception {
    WorldKey key = WorldKey.of("island", "ab12cd34");
    assertTrue(worlds.experienceKeyFree(key));

    // Only the END is left over — a teardown that crashed between two of the three dimensions. The
    // overworld name is free, so a check that looked at it alone would say yes and copy a fresh world
    // on top of somebody else's End.
    writeDimension(key.key() + "_end", null, null);
    assertFalse(worlds.experienceKeyFree(key),
        "a crashed teardown leaves a sibling behind; copying on top of it would stitch two worlds");
  }

  @Test
  @DisplayName("boot reclaims a staging folder a crash abandoned, and leaves a live one alone")
  void bootSweepsAbandonedBackupStaging() throws Exception {
    writeDimension(source.key().key(), sidecar(), players());
    // What a kill -9 halfway through a copy leaves: hundreds of megabytes nothing will ever delete,
    // and — worse — a folder with a region/ in it, which the placement reconciler adopts as a
    // permanent row on non-shared storage and then never drops, because the folder is still there.
    Path abandoned = folder("island_ab12cd34.incoming-deadbeef");
    Files.createDirectories(abandoned.resolve("region"));
    Files.writeString(abandoned.resolve("region/r.0.0.mca"), "half-copied", StandardCharsets.UTF_8);
    ageOut(abandoned);
    // And what a PEER NODE writing into the shared experiences tree right now looks like from here.
    // Deleting this one would corrupt somebody else's backup mid-flight, which is why the sweep is
    // age-guarded rather than "anything that matches the suffix".
    Path inFlight = folder("island_99887766.incoming-c0ffee");
    Files.createDirectories(inFlight.resolve("region"));
    Files.writeString(inFlight.resolve("region/r.0.0.mca"), "being-written", StandardCharsets.UTF_8);

    worlds.start();

    assertFalse(Files.exists(abandoned), "nothing else collects staging folders — the GC only walks temp/");
    assertTrue(Files.isDirectory(inFlight), "a fresh staging folder is a copy in progress, not litter");
    assertTrue(Files.isDirectory(folder(source.key().key())), "and a real world is never a candidate");
    assertTrue(worldFolders().contains(inFlight.getFileName().toString()));
    assertFalse(worldFolders().contains(abandoned.getFileName().toString()));
  }

  /** Backdates a folder and its contents well past the sweep's staleness threshold. */
  private static void ageOut(Path folder) throws IOException {
    java.nio.file.attribute.FileTime old = java.nio.file.attribute.FileTime.fromMillis(
        System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(3));
    // Children first: writing into a directory bumps the directory's own mtime back to now.
    try (var walk = Files.walk(folder)) {
      List<Path> paths = new ArrayList<>(walk.toList());
      java.util.Collections.reverse(paths);
      for (Path path : paths) {
        Files.setLastModifiedTime(path, old);
      }
    }
  }

  // ===== harness ===============================================================================

  private ExperienceBackup.Outcome run(UUID requester, String experienceId) throws Exception {
    return await(tell -> backups.backup(requester, experienceId, tell));
  }

  private ExperienceBackup.Outcome await(java.util.function.Consumer<java.util.function.Consumer<
      ExperienceBackup.Outcome>> request) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<ExperienceBackup.Outcome> outcome = new AtomicReference<>();
    request.accept(answer -> {
      outcome.set(answer);
      done.countDown();
    });
    assertTrue(done.await(60, TimeUnit.SECONDS), "the backup must answer exactly once");
    return outcome.get();
  }

  /** The one backup row, failing loudly when the test made more than it meant to. */
  private ExperienceManager.Experience onlyBackup() {
    List<ExperienceManager.Experience> all = experiences.backupsOf(source.id());
    assertEquals(1, all.size(), "expected exactly one backup");
    return all.get(0);
  }

  private Path folder(String key) {
    return home.resolve("experiences").resolve(key);
  }

  /** Every experience world folder on disk right now (the shared lock file is not one). */
  private Set<String> worldFolders() throws IOException {
    Path root = home.resolve("experiences");
    if (!Files.isDirectory(root)) {
      return Set.of();
    }
    try (var children = Files.list(root)) {
      return new HashSet<>(children.filter(Files::isDirectory)
          .map(path -> path.getFileName().toString()).toList());
    }
  }

  private Map<String, String> state(String key) throws IOException {
    return flat(folder(key).resolve("sexidium/state.yml"));
  }

  private Map<String, String> playerFile(String key) throws IOException {
    return flat(folder(key).resolve("sexidium/players/" + PLAYER + ".yml"));
  }

  private static Map<String, String> flat(Path file) throws IOException {
    Map<String, String> values = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String stripped = line.strip();
      int separator = stripped.indexOf(':');
      if (stripped.isEmpty() || stripped.startsWith("#") || separator < 0) {
        continue;
      }
      String value = stripped.substring(separator + 1).strip();
      if (value.length() >= 2 && value.charAt(0) == '"' && value.endsWith("\"")) {
        value = value.substring(1, value.length() - 1);
      }
      values.put(stripped.substring(0, separator).strip(), value);
    }
    return values;
  }

  /** What Death Resets and the map challenges write into {@code state.yml}. */
  private static Map<String, String> sidecar() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("deathresets.resets", "7");
    values.put("deathresets.daybaseline", "120");
    values.put("deathresets.days", "31");
    values.put("stats.run.seconds.total", "4512");
    values.put("stats.run.seconds.player." + PLAYER, "2100");
    values.put("stats.run.deaths.total", "19");
    values.put("stats.run.deaths.player." + PLAYER, "6");
    // World-SCOPED, which is why a reset drops them and a backup must not.
    values.put("classicskyblock.built", "1");
    values.put("stats.blocks.broken", "88214");
    return values;
  }

  private Map<String, String> players() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("name", "Ashu11a");
    // The spelling Bukkit reports, which is what really lands in these files.
    values.put("world", "experiences_" + source.key().key());
    values.put("x", "128.5");
    values.put("y", "71.0");
    values.put("z", "-64.25");
    values.put("yaw", "90.0");
    values.put("pitch", "-5.0");
    values.put("gamemode", "SURVIVAL");
    values.put("health", "18.0");
    values.put("food", "17");
    values.put("xp", "42");
    values.put("inv", "payload");
    values.put("effects", "speed:1:200");
    return values;
  }

  /** A keyed dimension folder as it really is on disk: no level.dat, no uid.dat, no session.lock. */
  private void writeDimension(String key, Map<String, String> state, Map<String, String> player)
      throws IOException {
    Path folder = folder(key);
    Files.createDirectories(folder.resolve("region"));
    Files.createDirectories(folder.resolve("data/minecraft"));
    Files.writeString(folder.resolve("region/r.0.0.mca"), "blocks-of-" + key, StandardCharsets.UTF_8);
    Files.writeString(folder.resolve("data/minecraft/world_gen_settings.dat"), "seed", StandardCharsets.UTF_8);
    Files.writeString(folder.resolve("paper-world.yml"), "keep-spawn: false", StandardCharsets.UTF_8);
    if (state != null) {
      writeFlat(folder.resolve("sexidium/state.yml"), state);
    }
    if (player != null) {
      writeFlat(folder.resolve("sexidium/players/" + PLAYER + ".yml"), player);
    }
  }

  private static void writeFlat(Path file, Map<String, String> values) throws IOException {
    Files.createDirectories(file.getParent());
    StringBuilder builder = new StringBuilder("# test fixture\n");
    values.forEach((key, value) -> builder.append(key).append(": \"").append(value).append("\"\n"));
    Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
  }

  /** A {@link TestServerAdapter} whose world layer is the real thing. */
  private static final class Adapter extends TestServerAdapter {
    private final WorldLeaseService worlds;

    private Adapter(WorldLeaseService worlds) {
      this.worlds = worlds;
    }

    @Override public WorldLeaseService worlds() {
      return worlds;
    }

    @Override public LoggerAdapter logger() {
      return SILENT;
    }
  }

  /**
   * A world control with no Minecraft under it but a REAL disk beneath: folders are folders, the
   * staging executor is the real one, and the linked-dimension suffixes are Paper's — which is what
   * makes "all three dimensions" and "a free key claims all three" mean anything here.
   */
  private static final class BackupWorldControl extends AbstractWorldControl {
    private final Path home;
    private final Set<String> loaded = new HashSet<>();
    /**
     * Saves and copies in ONE list, in the order they happened. Two lists could each be right while
     * the order between them was wrong, and the order is the entire point of a flush.
     */
    private final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());

    private BackupWorldControl(Path home) {
      super(new PropertiesConfigurationAdapter(), SILENT);
      this.home = home;
    }

    @Override protected boolean backendSaveWorld(WorldHandle handle) {
      log.add("save " + handle.runtimeName());
      return true;
    }

    @Override protected com.sexidium.core.world.WorldClone.ChunkCopyResult copyOneWorldFolder(
        Path from, Path staging) {
      log.add("copy " + from.getFileName());
      return super.copyOneWorldFolder(from, staging);
    }

    @Override protected List<String> siblingKeySuffixes() {
      return List.of("_nether", "_end");
    }

    @Override protected void runOnWorldThread(Runnable task) {
      task.run();
    }

    @Override protected Path serverHome() {
      return home;
    }

    @Override protected Path experiencesDiskRoot() {
      return home.resolve("experiences");
    }

    @Override protected Path lobbyDiskFolder() {
      return home.resolve("lobby");
    }

    @Override protected boolean backendExistsOnDisk(WorldRequest request) {
      return Files.isDirectory(experienceFolderFor(request.keyPath()).resolve("region"));
    }

    @Override protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean create) {
      return Optional.empty();
    }

    @Override protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
      return loaded.contains(runtimeName)
          ? Optional.of(new FakeHandle(runtimeName, home.resolve(runtimeName.replace('/', '_'))))
          : Optional.empty();
    }

    @Override protected boolean backendUnload(WorldHandle handle, boolean save) {
      return true;
    }

    @Override protected Optional<WorldHandle> backendLobby() {
      return Optional.empty();
    }

    @Override public Optional<WorldPosition> lobbySpawn() {
      return Optional.empty();
    }
  }

  private record FakeHandle(String runtimeName, Path folder) implements WorldHandle {
    @Override public WorldKind kind() {
      return WorldKind.PERSISTENT;
    }

    @Override public com.sexidium.core.platform.WorldAdapter adapter() {
      return new FakeWorld(runtimeName);
    }

    @Override public Path canonicalFolder() {
      return folder;
    }
  }

  private record FakeWorld(String name) implements com.sexidium.core.platform.WorldAdapter {
    @Override public WorldPosition spawnPosition() {
      return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f);
    }

    @Override public List<PlayerAdapter> players() {
      return new ArrayList<>();
    }

    @Override public void dropItem(WorldPosition target, ItemStackData item) { }

    @Override public void playSound(WorldPosition target, SoundKey sound, float volume, float pitch) { }

    @Override public void setBorder(WorldBorderSpec spec) { }

    @Override public void resetBorder() { }

    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }
}
