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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the engine refuses, and the promise that a refusal wrote nothing.
 *
 * <p>Every one of these is a case where doing the work anyway is silently destructive rather than
 * loudly broken: a restore over a running match rewrites the world a live {@code ExperiencePersistence}
 * is bound to; a restore from a node that cannot see both folders leaves a live experience naming a
 * folder that is not there; a copy of a copy is indistinguishable from its sibling in any list.</p>
 */
class ExperienceRestoreGateTest {

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
  /**
   * World keys ANOTHER node holds — open, or still flushing an unload. Deliberately separate from
   * {@link FakeExperienceWorlds#loaded}, because that is what this node can see and these are exactly
   * the worlds it cannot: on the live deployment the folder is a symlink into shared storage, so the
   * disk says "present" from everywhere and the loaded set says "closed" on every node but one.
   */
  private final Set<String> elsewhere = new HashSet<>();

  @BeforeEach
  void setUp() throws Exception {
    Database database = new Database(new File(tmp.toFile(), "gate.db"));
    experiences = new ExperienceManager(SILENT, database);
    worlds = new FakeExperienceWorlds();
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    engine = new ExperienceBackupService(server, experiences,
        new ExperienceStateStore(tmp.resolve("experiences"), SILENT), key -> false,
        key -> elsewhere.contains(key.key()));

    source = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Death Resets",
        System.currentTimeMillis());
    assertNotNull(source);
    String id = ExperienceManager.newExperienceId();
    backup = experiences.createBackup(source, "Death Resets (backup)", id,
        WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, Integer.MAX_VALUE);
    assertNotNull(backup);
  }

  private ExperienceBackup.Outcome restore(UUID requester, String backupId) {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.restore(requester, backupId, outcomes::add);
    assertEquals(1, outcomes.size(), "the owner is answered exactly once, always");
    return outcomes.get(0);
  }

  private ExperienceBackup.Outcome backup(UUID requester, String experienceId) {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.backup(requester, experienceId, outcomes::add);
    assertEquals(1, outcomes.size());
    return outcomes.get(0);
  }

  private void assertNothingMoved() {
    assertEquals(source.worldKey(), experiences.get(source.id()).worldKey());
    assertEquals(backup.worldKey(), experiences.get(backup.id()).worldKey());
    assertTrue(worlds.deleted.isEmpty(), "a refusal deletes nothing");
    assertTrue(worlds.copied.isEmpty(), "a refusal copies nothing");
  }

  @Test
  @DisplayName("the world being replaced is open: BUSY, and nothing moved")
  void busyFromTheLiveSide() {
    worlds.loaded.add(source.key().key());
    assertEquals(ExperienceBackup.Outcome.BUSY, restore(owner, backup.id()));
    assertNothingMoved();
  }

  @Test
  @DisplayName("the COPY is open: BUSY too — the swap is symmetric and so is the refusal")
  void busyFromTheCopySide() {
    worlds.loaded.add(backup.key().key());
    assertEquals(ExperienceBackup.Outcome.BUSY, restore(owner, backup.id()));
    assertNothingMoved();
  }

  @Test
  @DisplayName("a live match on either world is BUSY even when no world is loaded here")
  void busyFromALiveMatch() {
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    ExperienceBackupService withMatch = new ExperienceBackupService(server, experiences,
        new ExperienceStateStore(tmp.resolve("experiences"), SILENT),
        key -> key.key().equals(source.worldKey()), key -> elsewhere.contains(key.key()));
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    withMatch.restore(owner, backup.id(), outcomes::add);
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), outcomes,
        "a match can hold an experience whose world the lease layer has already handed back, so"
            + " neither question alone is enough");
    assertNothingMoved();
  }

  @Test
  @DisplayName("an orphan copy is GONE, not NOT_OWNER: there is nothing to restore it over")
  void anOrphanCopyIsGone() {
    experiences.remove(source.id());
    assertEquals(ExperienceBackup.Outcome.GONE, restore(owner, backup.id()));
  }

  @Test
  @DisplayName("a world that is not a copy of anything is GONE too")
  void restoringANonBackupIsGone() {
    assertEquals(ExperienceBackup.Outcome.GONE, restore(owner, source.id()));
    assertNothingMoved();
  }

  @Test
  @DisplayName("a stranger is NOT_OWNER, and the row is never even read out")
  void aStrangerIsRefused() {
    assertEquals(ExperienceBackup.Outcome.NOT_OWNER, restore(UUID.randomUUID(), backup.id()));
    assertEquals(ExperienceBackup.Outcome.NOT_OWNER, restore(null, backup.id()));
    assertEquals(ExperienceBackup.Outcome.NOT_OWNER, restore(owner, "no-such-experience"));
    assertNothingMoved();
  }

  @Test
  @DisplayName("a node that cannot see BOTH folders refuses rather than guessing")
  void aNodeMissingAFolderRefuses() {
    worlds.missingFolders.add(backup.worldKey());
    assertEquals(ExperienceBackup.Outcome.FAILED, restore(owner, backup.id()));
    assertNothingMoved();

    worlds.missingFolders.clear();
    worlds.missingFolders.add(source.worldKey());
    assertEquals(ExperienceBackup.Outcome.FAILED, restore(owner, backup.id()));
    assertNothingMoved();
  }

  @Test
  @DisplayName("with both folders here and both worlds closed, the restore happens")
  void theHappyPathSwaps() {
    assertEquals(ExperienceBackup.Outcome.RESTORED, restore(owner, backup.id()));
    assertEquals(backup.worldKey(), experiences.get(source.id()).worldKey());
    assertEquals(source.worldKey(), experiences.get(backup.id()).worldKey());
    assertTrue(worlds.copied.isEmpty(), "a restore copies NOTHING; that is the whole design");
    assertTrue(worlds.deleted.isEmpty(), "and it deletes nothing, which is why it has no rollback");
  }

  @Test
  @DisplayName("the ENGINE refuses a copy of a copy on its own, not just the menu that draws the tile")
  void theEngineRefusesABackupOfABackup() {
    assertEquals(ExperienceBackup.Outcome.LIMIT_REACHED, backup(owner, backup.id()));
    assertTrue(worlds.copied.isEmpty(),
        "this guard used to live only in ExperienceMenu, so every new entry point -- a console"
            + " command, another screen, a peer's routed request -- got none of it");
  }

  @Test
  @DisplayName("the ENGINE refuses a copy of a LOST hardcore world on its own")
  void theEngineRefusesABackupOfADeadWorld() {
    experiences.updateHardcore(source.id(), true, 1_000L);
    experiences.markDead(source.id(), 2_000L);
    assertEquals(ExperienceBackup.Outcome.LIMIT_REACHED, backup(owner, source.id()));
    assertTrue(worlds.copied.isEmpty());
  }

  @Test
  @DisplayName("a full disk is NO_SPACE and not FAILED — it is the one failure the owner can fix")
  void aFullDiskSaysSo() {
    worlds.roomOnDisk = false;
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.backup(owner, source.id(), outcomes::add);
    assertEquals(List.of(ExperienceBackup.Outcome.NO_SPACE), outcomes);
    assertTrue(worlds.copied.isEmpty(), "nothing was staged, so nothing has to be cleaned up");
  }

  @Test
  @DisplayName("a refresh re-points the row and only THEN removes the folder it used to name")
  void refreshRepointsBeforeDeleting() {
    String oldKey = backup.worldKey();
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), outcomes);
    assertEquals(1, worlds.copied.size());
    assertTrue(worlds.copied.get(0).startsWith(source.worldKey() + " -> "),
        "a refresh is taken from the SOURCE, not from the copy it replaces");
    ExperienceManager.Experience refreshed = experiences.get(backup.id());
    assertEquals(backup.id(), refreshed.id(), "the id is what anything holding this backup remembers");
    assertTrue(refreshed.isBackup());
    assertEquals(List.of(oldKey), worlds.deleted,
        "the old folder goes last: until the row names the new one, deleting it would leave the"
            + " owner's backup naming nothing at all");
  }

  @Test
  @DisplayName("a refresh does NOT delete the old folder if it was re-opened while the copy ran")
  void refreshDoesNotDeleteAWorldSomebodyWalkedBackInto() {
    // The gate at the top proved the old folder was closed. Then the copy runs for seconds to minutes,
    // and for all of it the backup row still names the old folder -- so entering it is a legitimate
    // thing the owner can do. Reusing the stale proof deletes a world with a player standing in it.
    String oldKey = backup.worldKey();
    worlds.duringCopy = () -> worlds.loaded.add(oldKey);
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), outcomes,
        "the ROW is already correct, which is the whole verb; an orphan folder an operator can see"
            + " in the log is not a failure to report to the owner");
    assertTrue(worlds.deleted.isEmpty(),
        "losing a folder somebody is standing in is strictly worse than leaving one behind");
    ExperienceManager.Experience refreshed = experiences.get(backup.id());
    assertEquals(backup.id(), refreshed.id());
    assertTrue(refreshed.isBackup());
    assertNotEquals(oldKey, refreshed.worldKey(), "it names the fresh copy either way");
  }

  @Test
  @DisplayName("a live match on the old folder stops the delete for the same reason")
  void refreshDoesNotDeleteAWorldAMatchTookOver() {
    String oldKey = backup.worldKey();
    Set<String> running = new HashSet<>();
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    ExperienceBackupService withMatch = new ExperienceBackupService(server, experiences,
        new ExperienceStateStore(tmp.resolve("experiences"), SILENT),
        key -> running.contains(key.key()), key -> elsewhere.contains(key.key()));
    worlds.duringCopy = () -> running.add(oldKey);

    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    withMatch.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), outcomes);
    assertTrue(worlds.deleted.isEmpty(),
        "a match can hold a world the lease layer has handed back, so both questions are asked again");
  }

  @Test
  @DisplayName("a refresh whose copy fails leaves the backup naming its original folder")
  void aFailedRefreshChangesNothing() {
    worlds.copySucceeds = false;
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.FAILED), outcomes);
    assertEquals(backup.worldKey(), experiences.get(backup.id()).worldKey());
    assertTrue(worlds.deleted.isEmpty(), "the copy the owner had is the copy the owner still has");
  }

  // ===== the copy is on ANOTHER node ===========================================================
  //
  // Both verbs route on the SOURCE's key, so they run on the node holding the source and every local
  // question they ask -- loaded, closing, match -- is about that node. The COPY is addressed by nobody.
  // On the live deployment the folder check cannot cover for it either: the experiences tree is a
  // symlink into shared storage, so `experienceFolderPresent` is true on all three nodes for every
  // experience there has ever been. Without the placement gate the copy side is guarded by nothing.

  @Test
  @DisplayName("the copy is open on ANOTHER node: BUSY, exactly as if it were open here")
  void restoreRefusesACopyAnotherNodeHolds() {
    // worker-1 holds the source and runs this. The owner opened the backup, looked at it and walked
    // out; worker-2 is still flushing the unload, and will then write state.yml, player snapshots and
    // a rememberPlayerWorld row -- on behalf of the backup -- into a folder this swap has already
    // handed to the live experience. The owner would have been told "exactly as it was", untruly.
    elsewhere.add(backup.worldKey());
    assertEquals(ExperienceBackup.Outcome.BUSY, restore(owner, backup.id()));
    assertNothingMoved();
  }

  @Test
  @DisplayName("a refresh refuses when another node holds the copy, and deletes NOTHING")
  void refreshRefusesACopyAnotherNodeHolds() {
    elsewhere.add(backup.worldKey());
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), outcomes);
    assertNothingMoved();
  }

  @Test
  @DisplayName("THE ONE THAT DESTROYS DATA: the copy is opened elsewhere DURING the copy window")
  void refreshDoesNotDeleteAWorldAnotherNodeOpenedMidCopy() {
    // The gate at the top proved the old folder was quiet on both nodes. Then a full folder re-copy
    // runs -- sub-second on a warm tree, seconds on a cold one -- and for all of it the backup row
    // still names the old key, so the owner can legitimately walk into it and the placement gate hands
    // it to whichever worker is free. That is never this one. Both local questions then answer
    // "closed", and deletePersistent on a world nothing has loaded HERE skips evacuate and unload
    // entirely and removes the region files under the player standing in them.
    String oldKey = backup.worldKey();
    worlds.duringCopy = () -> elsewhere.add(oldKey);
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), outcomes,
        "the row is already correct, which is the whole verb; an orphan folder in the log is not a"
            + " failure to report to the owner");
    assertTrue(worlds.deleted.isEmpty(),
        "worker-2 has this world open and its placement row still says LOADED against it");
    ExperienceManager.Experience refreshed = experiences.get(backup.id());
    assertEquals(backup.id(), refreshed.id());
    assertNotEquals(oldKey, refreshed.worldKey(), "it names the fresh copy either way");
  }

  @Test
  @DisplayName("a placement nothing can determine refuses both verbs rather than proceeding")
  void anUndeterminablePlacementRefuses() {
    // A lock-wait timeout on the shared MariaDB, a locator that threw, or an engine wired without a
    // gate at all. Every one of them arrives here as "true", because the only alternative is acting on
    // a world this node cannot locate. A refusal is a click the owner repeats; a folder deleted under
    // a player is not repeatable.
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    ExperienceStateStore states = new ExperienceStateStore(tmp.resolve("experiences"), SILENT);
    for (ExperienceBackupService blind : List.of(
        new ExperienceBackupService(server, experiences, states, key -> false, key -> true),
        new ExperienceBackupService(server, experiences, states, key -> false, null))) {
      List<ExperienceBackup.Outcome> restored = new ArrayList<>();
      blind.restore(owner, backup.id(), restored::add);
      assertEquals(List.of(ExperienceBackup.Outcome.BUSY), restored);

      List<ExperienceBackup.Outcome> refreshed = new ArrayList<>();
      blind.refresh(owner, backup.id(), refreshed::add);
      assertEquals(List.of(ExperienceBackup.Outcome.BUSY), refreshed,
          "an engine wired without a gate is loudly unable to restore, which an operator fixes --"
              + " not quietly able to delete a world another node has open, which nobody can");

      assertNothingMoved();
    }
  }

  @Test
  @DisplayName("a duplicate is nobody's backup and spends a world slot")
  void aDuplicateIsAWorld() {
    int worldsBefore = experiences.countByOwner(owner);
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.duplicate(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.DUPLICATED), outcomes);
    assertEquals(worldsBefore + 1, experiences.countByOwner(owner),
        "unlike a backup, this one is a world the player chose to make");
    List<ExperienceManager.Experience> owned = experiences.byOwner(owner);
    ExperienceManager.Experience copy = owned.get(owned.size() - 1);
    assertTrue(copy.displayName().endsWith("(copy)"));
    assertTrue(copy.backupOf() == null || copy.backupOf().isBlank(),
        "deleting the original must change nothing about it");
  }

  @Test
  @DisplayName("a player at their world cap cannot duplicate, and nothing is copied")
  void aDuplicateRespectsThePerPlayerCap() {
    for (int index = experiences.countByOwner(owner); index < engine.maxPerPlayer(); index++) {
      assertNotNull(experiences.create(owner, "Ashu11a", List.of(), "Filler " + index,
          System.currentTimeMillis()));
    }
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.duplicate(owner, backup.id(), outcomes::add);
    assertEquals(List.of(ExperienceBackup.Outcome.LIMIT_REACHED), outcomes);
    assertTrue(worlds.copied.isEmpty());
  }
}
