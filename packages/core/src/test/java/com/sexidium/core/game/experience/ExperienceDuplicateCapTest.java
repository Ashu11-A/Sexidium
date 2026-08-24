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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DUPLICATE is the one copy verb that spends a world slot, and its cap has to be counted at the INSERT.
 *
 * <p>The check at the click and the row that lands are separated by a copy of a multi-hundred-megabyte
 * folder tree. A player at 9 of 10 who clicks Duplicate on two different backups inside that window
 * passes both checks, lands both copies and owns 11 worlds — and nothing afterwards ever notices,
 * because the cap is only ever consulted before a create. The same reasoning already made
 * {@code createBackup} re-count the per-experience cap inside its own transaction; this is the missing
 * half, and the predicate has to be {@code countByOwner}'s exactly, because backups do not spend a
 * slot and counting them would refuse duplicates the owner is entitled to.</p>
 */
class ExperienceDuplicateCapTest {

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
  private ExperienceBackupService engine;
  private FakeExperienceWorlds worlds;
  private ExperienceManager.Experience source;
  private ExperienceManager.Experience backup;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    Database database = new Database(new File(tmp.toFile(), "duplicate.db"));
    experiences = new ExperienceManager(SILENT, database);
    worlds = new FakeExperienceWorlds();
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    engine = new ExperienceBackupService(server, experiences,
        new ExperienceStateStore(tmp.resolve("experiences"), SILENT), key -> false, key -> false);

    source = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Death Resets",
        System.currentTimeMillis());
    assertNotNull(source);
    String id = ExperienceManager.newExperienceId();
    backup = experiences.createBackup(source, "Death Resets (backup)", id,
        WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, Integer.MAX_VALUE);
    assertNotNull(backup);
  }

  /** Fills the owner up to exactly {@code target} experiences (backups never count towards it). */
  private void fillTo(int target) {
    for (int index = experiences.countByOwner(owner); index < target; index++) {
      assertNotNull(experiences.create(owner, "Ashu11a", List.of(), "Filler " + index,
          System.currentTimeMillis()));
    }
    assertEquals(target, experiences.countByOwner(owner));
  }

  private ExperienceManager.Experience duplicateRow(int cap) {
    String id = ExperienceManager.newExperienceId();
    return experiences.createFrom(experiences.get(backup.id()), "Death Resets (copy)", id,
        WorldKey.of("Death Resets", id), System.currentTimeMillis(), "days=27", cap);
  }

  @Test
  @DisplayName("the insert re-counts the per-player cap and writes NOTHING when it is reached")
  void theInsertRefusesAtTheCap() {
    int cap = engine.maxPerPlayer();
    fillTo(cap);
    int rowsBefore = experiences.byOwner(owner).size();

    assertNull(duplicateRow(cap),
        "the pre-check at the click cannot see a slot spent while the folder tree was being copied,"
            + " so the only place the cap is TRUE rather than likely is inside the insert");
    assertEquals(cap, experiences.countByOwner(owner), "and the refusal rolled the whole thing back");
    assertEquals(rowsBefore, experiences.byOwner(owner).size(),
        "no row at all -- not even one that would show up in My Experiences as a world with no folder");
  }

  @Test
  @DisplayName("backups do not spend a slot, so the count here is countByOwner's, character for character")
  void backupsDoNotBlockADuplicate() {
    int cap = engine.maxPerPlayer();
    fillTo(cap - 1);
    // Enough copies to blow any cap that counted them. `backup_of IS NULL` is what keeps them out.
    for (int index = 0; index < cap; index++) {
      String id = ExperienceManager.newExperienceId();
      assertNotNull(experiences.createBackup(source, "Copy " + index, id,
          WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, Integer.MAX_VALUE));
    }

    ExperienceManager.Experience stored = duplicateRow(cap);
    assertNotNull(stored, "an owner one under their cap may duplicate, however many copies they keep");
    assertEquals(cap, experiences.countByOwner(owner));
  }

  @Test
  @DisplayName("the uncapped overload still inserts — every other caller wants no cap here")
  void theUncappedOverloadStillInserts() {
    fillTo(engine.maxPerPlayer());

    ExperienceManager.Experience stored = duplicateRow(Integer.MAX_VALUE);
    assertNotNull(stored, "Integer.MAX_VALUE means 'no cap here', exactly as it does for createBackup");
    assertFalse(stored.isBackup(), "a duplicate is nobody's copy; that is why it costs a slot");
    assertNotNull(experiences.get(stored.id()));
  }

  @Test
  @DisplayName("a slot taken while the copy ran refuses the duplicate, cleans up, and says LIMIT_REACHED")
  void aSlotLostDuringTheCopyIsAnHonestRefusal() {
    int cap = engine.maxPerPlayer();
    fillTo(cap - 1);
    // The second click: another duplicate (or a plain create) commits its row while this one is still
    // copying bytes. Both passed the pre-check; only one of them may land.
    worlds.duringCopy = () -> assertNotNull(experiences.create(owner, "Ashu11a", List.of(),
        "The other click", System.currentTimeMillis()));

    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.duplicate(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.LIMIT_REACHED), outcomes,
        "FAILED sends the owner looking for a fault; the truth is that they are at their limit and"
            + " can act on it by deleting a world");
    assertEquals(cap, experiences.countByOwner(owner), "eleven worlds out of a cap of ten, otherwise");
    assertEquals(1, worlds.copied.size());
    String destination = worlds.copied.get(0).split(" -> ")[1];
    assertEquals(List.of(destination), worlds.deleted,
        "the bytes landed and nothing names them, so they go back off disk -- and what is removed is"
            + " the COPY, never anything the owner still has a row for");
    assertTrue(experiences.byOwner(owner).stream()
            .noneMatch(row -> row.displayName().endsWith("(copy)")),
        "no row was written for the copy that was refused");
  }
}
