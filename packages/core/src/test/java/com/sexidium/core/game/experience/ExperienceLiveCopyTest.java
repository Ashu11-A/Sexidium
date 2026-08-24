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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reversal, and the line it is NOT allowed to cross.
 *
 * <p>Copies may now read a world people are inside. That is a deliberate trade: a copy is verified
 * after the fact (the inventory bracket refuses a source that moved), so the worst a live copy can
 * produce is a refusal. The two operations that cannot be checked afterwards did not move an inch —
 * a restore re-points a {@code world_key} a live match is bound to, and a refresh DELETES the folder
 * it replaces. Both of those still refuse a busy world, here and across the fleet.</p>
 *
 * <p>Every test below fails on the previous behaviour or on any of the obvious ways to over-apply
 * the new one, which is the only reason each of them is here.</p>
 */
class ExperienceLiveCopyTest {

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
  private FakeExperienceWorlds worlds;
  private TestServerAdapter server;
  private ExperienceBackupService engine;
  private ExperienceManager.Experience source;
  private ExperienceManager.Experience backup;
  private final UUID owner = UUID.randomUUID();
  /** World keys another node holds — see {@code ExperienceRestoreGateTest} for why this is separate. */
  private final Set<String> elsewhere = new HashSet<>();
  /** World keys a live match is running on. */
  private final Set<String> matches = new HashSet<>();

  @BeforeEach
  void setUp() throws Exception {
    experiences = new ExperienceManager(SILENT, new Database(new File(tmp.toFile(), "live.db")));
    worlds = new FakeExperienceWorlds();
    server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    engine = new ExperienceBackupService(server, experiences,
        new ExperienceStateStore(tmp.resolve("experiences"), SILENT),
        key -> matches.contains(key.key()), key -> elsewhere.contains(key.key()));

    source = experiences.create(owner, "Ashu11a", List.of("deathresets"), "Death Resets",
        System.currentTimeMillis());
    assertNotNull(source);
    String id = ExperienceManager.newExperienceId();
    backup = experiences.createBackup(source, "Death Resets (backup)", id,
        WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, Integer.MAX_VALUE);
    assertNotNull(backup);
  }

  // ===== the reversal ==========================================================================

  @Test
  @DisplayName("a backup of a world people are inside now happens, and is flushed first")
  void backupCopiesALiveWorld() {
    worlds.loaded.add(source.worldKey());

    assertEquals(ExperienceBackup.Outcome.CREATED, backup(owner, source.id()),
        "this answered BUSY before: an owner could not back up the world they were playing");
    assertEquals(1, worlds.copied.size());
    assertEquals(2, worlds.calls.size(), worlds.calls.toString());
    assertEquals("save " + source.worldKey(), worlds.calls.get(0),
        "the flush has to come BEFORE the copy — a save issued afterwards is a write racing the"
            + " reader it was meant to precede, and would look like diligence in the log");
    assertTrue(worlds.calls.get(1).startsWith("copy " + source.worldKey() + " -> "),
        worlds.calls.toString());
    assertEquals(2, experiences.backupsOf(source.id()).size(),
        "the fixture copy plus the one just taken");
  }

  @Test
  @DisplayName("a live MATCH on the source does not stop a backup either")
  void backupCopiesAWorldWithAMatchOnIt() {
    matches.add(source.worldKey());
    // The two questions were always one gate ("is it busy") and they are dropped together. A match is
    // just the loaded question asked of a world the lease layer has already handed back.
    assertEquals(ExperienceBackup.Outcome.CREATED, backup(owner, source.id()));
    assertEquals(1, worlds.copied.size());
  }

  @Test
  @DisplayName("a duplicate of a copy somebody is standing in now happens")
  void duplicateCopiesALiveWorld() {
    worlds.loaded.add(backup.worldKey());
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.duplicate(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.DUPLICATED), outcomes);
    assertEquals(List.of(backup.worldKey()), worlds.saved);
    assertEquals(1, worlds.copied.size());
  }

  @Test
  @DisplayName("a refresh reads a live SOURCE, and still removes the old copy nobody is in")
  void refreshCopiesALiveSource() {
    worlds.loaded.add(source.worldKey());
    String oldKey = backup.worldKey();
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), outcomes);
    assertEquals(List.of(source.worldKey()), worlds.saved,
        "the SOURCE is the folder being read, so it is the one flushed");
    assertEquals(List.of(oldKey), worlds.deleted);
  }

  @Test
  @DisplayName("a source that never settles ends FAILED, and nothing is recorded")
  void aSourceThatKeepsMovingIsRefused() {
    // The world layer copies-and-verifies a bounded number of times and then answers false; the shape
    // of that bound is pinned in ExperienceCopyRetryTest. What matters here is the verb's half of the
    // contract: a copy that could not be verified is REFUSED, never published and never given a row.
    worlds.loaded.add(source.worldKey());
    worlds.copySucceeds = false;

    assertEquals(ExperienceBackup.Outcome.FAILED, backup(owner, source.id()));
    assertEquals(List.of(backup.id()),
        experiences.backupsOf(source.id()).stream().map(ExperienceManager.Experience::id).toList(),
        "a row naming a torn copy is the one outcome this whole feature must never produce");
    assertTrue(worlds.deleted.isEmpty());
  }

  // ===== the line that did not move ============================================================

  @Test
  @DisplayName("restore still refuses BUSY on either world, and is not covered by allow-live-copy")
  void restoreStillRefusesBusy() {
    for (String open : List.of(source.worldKey(), backup.worldKey())) {
      worlds.loaded.clear();
      worlds.loaded.add(open);
      assertEquals(ExperienceBackup.Outcome.BUSY, restore(owner, backup.id()),
          "the swap re-points the world_key a live match's persistence holds, and nothing about that"
              + " can be verified after the fact the way a copy can: " + open);
      assertNothingMoved();
    }
    // And it is not the config that is holding the line: turning live copies ON explicitly changes
    // nothing here, because a restore reads no folder at all.
    server.configuration().set("worlds.experiences.allow-live-copy", true);
    worlds.loaded.clear();
    worlds.loaded.add(source.worldKey());
    assertEquals(ExperienceBackup.Outcome.BUSY, restore(owner, backup.id()));
    assertNothingMoved();
  }

  @Test
  @DisplayName("restore still refuses a copy another NODE holds")
  void restoreStillRefusesHeldElsewhere() {
    elsewhere.add(backup.worldKey());
    assertEquals(ExperienceBackup.Outcome.BUSY, restore(owner, backup.id()));
    assertNothingMoved();
  }

  @Test
  @DisplayName("THE REGRESSION THAT COSTS A WORLD: refresh never deletes a folder somebody is in")
  void refreshStillRefusesToDeleteAnOpenCopy() {
    // Local. The copy is open on THIS node, and the refresh would end by removing its folder.
    worlds.loaded.add(backup.worldKey());
    List<ExperienceBackup.Outcome> local = new ArrayList<>();
    engine.refresh(owner, backup.id(), local::add);
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), local,
        "reading a live world is now fine; DELETING one is not, and the two must not loosen together");
    assertNothingMoved();

    // Cross-node. Nothing local can see this: on the live deployment the folder is a symlink into
    // shared storage, so the disk says "present" from every node and the loaded set says "closed" on
    // every node but the one that has it.
    worlds.loaded.clear();
    elsewhere.add(backup.worldKey());
    List<ExperienceBackup.Outcome> fleet = new ArrayList<>();
    engine.refresh(owner, backup.id(), fleet::add);
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), fleet);
    assertNothingMoved();
  }

  @Test
  @DisplayName("and not even when the copy is re-entered DURING the copy window")
  void refreshStillSkipsTheDeleteWhenTheOldFolderIsReopenedMidCopy() {
    // The source is live, which is the whole point of this change — and the OLD copy is walked into
    // while the multi-second copy runs, which is the moment the pre-delete re-check exists for.
    worlds.loaded.add(source.worldKey());
    String oldKey = backup.worldKey();
    worlds.duringCopy = () -> elsewhere.add(oldKey);

    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), outcomes,
        "the row is already correct, so the verb succeeded; an orphan folder named in a SEVERE line"
            + " is not a failure to report to the owner");
    assertTrue(worlds.deleted.isEmpty(),
        "losing a folder somebody is standing in is strictly worse than leaving one behind");
  }

  // ===== the operator's way back ===============================================================

  @Test
  @DisplayName("allow-live-copy: false restores the old refusal for exactly the three copy verbs")
  void theStrictModeIsStillThere() {
    server.configuration().set("worlds.experiences.allow-live-copy", false);
    worlds.loaded.add(source.worldKey());

    assertEquals(ExperienceBackup.Outcome.BUSY, backup(owner, source.id()));
    List<ExperienceBackup.Outcome> refreshed = new ArrayList<>();
    engine.refresh(owner, backup.id(), refreshed::add);
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), refreshed);

    worlds.loaded.clear();
    worlds.loaded.add(backup.worldKey());
    List<ExperienceBackup.Outcome> duplicated = new ArrayList<>();
    engine.duplicate(owner, backup.id(), duplicated::add);
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), duplicated);

    assertTrue(worlds.copied.isEmpty(), "a refusal copies nothing");
    assertTrue(worlds.saved.isEmpty(), "and does not flush a world it is not going to read");
    assertNothingMoved();
  }

  @Test
  @DisplayName("allow-live-copy: false changes nothing about a restore, in either direction")
  void theStrictModeDoesNotTouchRestore() {
    server.configuration().set("worlds.experiences.allow-live-copy", false);
    // Both worlds quiet: the restore that was always allowed is still allowed. The setting governs
    // reads, and a restore performs none — reading it as a global "be strict" switch would break the
    // one verb the owner uses to get their world back.
    assertEquals(ExperienceBackup.Outcome.RESTORED, restore(owner, backup.id()));
    assertEquals(backup.worldKey(), experiences.get(source.id()).worldKey());
    assertTrue(worlds.copied.isEmpty(), "a restore copies nothing; that is the whole design");
  }

  @Test
  @DisplayName("with nothing open, the flush is still asked for and still answers 'nothing to do'")
  void aClosedWorldIsFlushedHarmlessly() {
    assertEquals(ExperienceBackup.Outcome.CREATED, backup(owner, source.id()));
    assertEquals(List.of(source.worldKey()), worlds.saved,
        "asking unconditionally is cheaper than deciding when to ask, and a closed world's save is a"
            + " no-op the world layer answers false to");
    assertFalse(worlds.copied.isEmpty());
  }

  // ===== harness ===============================================================================

  private ExperienceBackup.Outcome backup(UUID requester, String experienceId) {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.backup(requester, experienceId, outcomes::add);
    assertEquals(1, outcomes.size(), "the owner is answered exactly once, always");
    return outcomes.get(0);
  }

  private ExperienceBackup.Outcome restore(UUID requester, String backupId) {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.restore(requester, backupId, outcomes::add);
    assertEquals(1, outcomes.size(), "the owner is answered exactly once, always");
    return outcomes.get(0);
  }

  private void assertNothingMoved() {
    assertEquals(source.worldKey(), experiences.get(source.id()).worldKey());
    assertEquals(backup.worldKey(), experiences.get(backup.id()).worldKey());
    assertTrue(worlds.deleted.isEmpty(), "a refusal deletes nothing");
  }
}
