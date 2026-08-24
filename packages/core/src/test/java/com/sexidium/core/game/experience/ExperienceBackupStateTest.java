package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy carries the run's real counters, or none — never the ones the database happens to remember.
 *
 * <p>{@code experiences.challenge_state} is a CRASH NET, not a record. It is written when a reset is
 * about to delete the folder the real {@code state.yml} lives in, and at no other time, so on a world
 * anybody actually plays it drifts arbitrarily far behind. Measured on the live server: the column
 * said 39 558 seconds of playtime and knew nothing about the day count or the blocks broken, while the
 * file inside the world said 62 983 seconds, 27 days and 11 809 blocks — more than six hours apart,
 * and a day stale.</p>
 *
 * <p>{@code createBackup} copied that column verbatim, which made every backup a promise to roll a run
 * back six and a half hours the moment anything read the column instead of the file. Two fixes, both
 * pinned here: the registry stores what it is handed and invents nothing, and the engine hands it the
 * file.</p>
 */
class ExperienceBackupStateTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private ExperienceManager experiences;
  private ExperienceStateStore store;
  private ExperienceBackupService engine;
  private FakeExperienceWorlds worlds;
  private ExperienceManager.Experience source;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    Database database = new Database(new File(tmp.toFile(), "state.db"));
    experiences = new ExperienceManager(SILENT, database);
    store = new ExperienceStateStore(tmp.resolve("experiences"), SILENT);
    worlds = new FakeExperienceWorlds();
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    // No match anywhere, and no other node holding anything: this fixture is about the state file.
    engine = new ExperienceBackupService(server, experiences, store, key -> false, key -> false);
    source = experiences.create(owner, "Ashu11a", List.of("deathresets"), "Death Resets",
        System.currentTimeMillis());
    assertNotNull(source);
  }

  /** Writes a real {@code sexidium/state.yml} inside the source's folder, the way a live run does. */
  private void writeStateFile(java.util.Map<String, String> counters) throws Exception {
    Path folder = tmp.resolve("experiences").resolve(source.worldKey());
    Files.createDirectories(folder);
    store.saveSharedState(source.worldKey(), ExperienceState.fromValues(counters));
    assertTrue(Files.isRegularFile(folder.resolve("sexidium").resolve("state.yml")));
  }

  @Test
  @DisplayName("the registry never copies the stale column on its own")
  void theRegistryDoesNotCarryTheColumnForward() {
    experiences.updateChallengeState(source.id(), "stats.run.seconds.total=39558", 1_000L);
    ExperienceManager.Experience copy = experiences.createBackup(
        experiences.get(source.id()), "Copy", System.currentTimeMillis());
    assertNotNull(copy);

    String carried = experiences.challengeState(copy.id());
    assertTrue(carried == null || carried.isBlank(),
        "with nobody able to read the folder, storing NOTHING is the only honest answer: the copy's"
            + " own state.yml is then the single source, which is where the counters live anyway."
            + " Storing the column would have handed the copy a value already known to be wrong");
    assertEquals("stats.run.seconds.total=39558", experiences.challengeState(source.id()),
        "and the source's own crash net is left exactly as it was");
  }

  @Test
  @DisplayName("the engine hands the registry the FILE's counters, not the column's")
  void theEngineCarriesTheTruth() throws Exception {
    experiences.updateChallengeState(source.id(), "stats.run.seconds.total=39558", 1_000L);
    writeStateFile(java.util.Map.of(
        "stats.run.seconds.total", "62983",
        "deathresets.days", "27",
        "stats.blocks.broken", "11809"));

    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.backup(owner, source.id(), outcomes::add);
    assertEquals(List.of(ExperienceBackup.Outcome.CREATED), outcomes);

    List<ExperienceManager.Experience> copies = experiences.backupsOf(source.id());
    assertEquals(1, copies.size());
    ExperienceState carried = ExperienceState.decode(experiences.challengeState(copies.get(0).id()));
    assertEquals(62983L, carried.getLong("stats.run.seconds.total", -1L),
        "the file is the truth; the column is a day behind on any world somebody plays");
    assertEquals(27, carried.getInt("deathresets.days", -1),
        "the column had no day count at ALL, which is how a restore would silently lose the run");
    assertEquals(11809, carried.getInt("stats.blocks.broken", -1));
  }

  @Test
  @DisplayName("a copy of a world with no state file carries nothing rather than something wrong")
  void noFileMeansNoColumn() {
    experiences.updateChallengeState(source.id(), "stats.run.seconds.total=39558", 1_000L);
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.backup(owner, source.id(), outcomes::add);
    assertEquals(List.of(ExperienceBackup.Outcome.CREATED), outcomes);

    String carried = experiences.challengeState(experiences.backupsOf(source.id()).get(0).id());
    assertTrue(carried == null || carried.isBlank());
  }

  @Test
  @DisplayName("a copy is named apart from the world it came from, so a list can tell them apart")
  void theCopyIsNamedApart() {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.backup(owner, source.id(), outcomes::add);
    assertEquals(List.of(ExperienceBackup.Outcome.CREATED), outcomes);

    ExperienceManager.Experience copy = experiences.backupsOf(source.id()).get(0);
    assertEquals("Death Resets (backup)", copy.displayName(),
        "the engine used to pass the source's name verbatim, so the suffix never ran and the live"
            + " database ended up with two rows both called 'Death Resets'");
    assertFalse(copy.key().key().contains("backup"),
        "the FOLDER keeps the plain name: a path segment is an identity an operator reads, and"
            + " '(backup)' in it is noise nobody sees in game and everybody reads past in a shell");
  }

  @Test
  @DisplayName("the cap is re-counted inside the insert, so two clicks cannot land a fourth copy")
  void theCapIsAtomicWithTheInsert() {
    for (int index = 0; index < 3; index++) {
      String id = ExperienceManager.newExperienceId();
      assertNotNull(experiences.createBackup(source, "Copy " + index, id,
          WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, 3));
    }
    String id = ExperienceManager.newExperienceId();
    assertEquals(null, experiences.createBackup(source, "One too many", id,
            WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, 3),
        "the check upstream and the insert are a check-then-act: a double click, or two nodes, both"
            + " pass a cap of 3 and land a fourth. Counting inside the transaction is what makes the"
            + " cap true rather than likely");
    assertEquals(3, experiences.countBackupsOf(source.id()));
  }
}
