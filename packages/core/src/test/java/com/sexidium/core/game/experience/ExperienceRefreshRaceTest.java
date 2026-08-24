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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a REFRESH is allowed to do to rows and folders that moved while it was copying.
 *
 * <p>A refresh is the only verb here whose two halves are minutes apart: it decides everything —
 * which folder to replace, which row to re-point, which folder to delete — and then copies several
 * hundred megabytes before acting on any of it. Every value it acts on is therefore a statement about
 * a moment that has passed, and the whole of this file is about the ones that can have stopped being
 * true:</p>
 *
 * <ul>
 *   <li>a RESTORE of the same backup can commit inside that window, which moves both rows onto each
 *       other's folders. The continuation would then re-point a row that has taken over the SOURCE's
 *       world and go on to delete the folder the source is running <em>now</em>;</li>
 *   <li>a SECOND refresh of the same backup can start inside it, allocate its own destination and win
 *       the re-point, leaving the loser's copy named by no row at all — invisible to the owner and
 *       collected by nothing;</li>
 *   <li>and the member pointers of the backup have to follow the folder, or the refresh deletes the
 *       world every {@code experience_players} row of that backup still names.</li>
 * </ul>
 *
 * <p>{@link FakeExperienceWorlds#duringCopy} is what expresses "and then this happened while the copy
 * ran"; the fake answers the copy synchronously, so anything the hook does is exactly a change that
 * landed after the gates and before the continuation.</p>
 */
class ExperienceRefreshRaceTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private Database database;
  private ExperienceManager experiences;
  private ExperienceBackupService engine;
  private FakeExperienceWorlds worlds;
  private ExperienceManager.Experience source;
  private ExperienceManager.Experience backup;
  private final UUID owner = UUID.randomUUID();
  private final Set<String> elsewhere = new HashSet<>();
  /**
   * Armed from {@link FakeExperienceWorlds#duringCopy} to make everything AFTER the copy throw.
   *
   * <p>{@code matchRunning} is asked once before the copy and once inside the continuation, which is
   * the same shape as the database calls the continuation really makes: a MariaDB that restarts while
   * the bytes are moving answers the first question and throws on the second.</p>
   */
  private RuntimeException failEverythingAfterTheCopy;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "refresh-race.db"));
    experiences = new ExperienceManager(SILENT, database);
    worlds = new FakeExperienceWorlds();
    // Silent, except where a test has armed it: "Copied experience ..." is logged from inside the
    // create path's continuation, one line before the owner is answered, so throwing there is the
    // cheapest way to say "something in this continuation blew up" at exactly that point. In
    // production the thing that blows up is the row write itself -- ExperienceManager.transact asks
    // Database.connection() outside its own try, and that throws when the database has gone away.
    LoggerAdapter logger = new LoggerAdapter() {
      @Override public void info(String message) {
        if (failEverythingAfterTheCopy != null && message.startsWith("Copied experience")) {
          throw failEverythingAfterTheCopy;
        }
      }
      @Override public void warning(String message) { }
      @Override public void severe(String message) { }
      @Override public void warning(String message, Throwable throwable) { }
      @Override public void severe(String message, Throwable throwable) { }
    };
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return logger; }
      @Override public WorldLeaseService worlds() { return worlds; }
    };
    engine = new ExperienceBackupService(server, experiences,
        new ExperienceStateStore(tmp.resolve("experiences"), SILENT),
        key -> {
          if (failEverythingAfterTheCopy != null) {
            throw failEverythingAfterTheCopy;
          }
          return false;
        },
        key -> elsewhere.contains(key.key()));

    source = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Death Resets",
        System.currentTimeMillis());
    assertNotNull(source);
    String id = ExperienceManager.newExperienceId();
    backup = experiences.createBackup(source, "Death Resets (backup)", id,
        WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, Integer.MAX_VALUE);
    assertNotNull(backup);
  }

  private List<ExperienceBackup.Outcome> refresh() {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    engine.refresh(owner, backup.id(), outcomes::add);
    assertEquals(1, outcomes.size(), "the owner is answered exactly once, always");
    return outcomes;
  }

  /** The destination of the one copy that was taken, read off the fake's record of it. */
  private String destinationOfTheCopy() {
    assertEquals(1, worlds.copied.size(), "exactly one copy: " + worlds.copied);
    return worlds.copied.get(0).split(" -> ", 2)[1];
  }

  // ===== the restore that commits mid-copy ======================================================

  @Test
  @DisplayName("THE ONE THAT DESTROYS DATA: a restore commits mid-copy, and the refresh deletes nothing")
  void aRestoreThatCommitsMidCopyIsNotOverwritten() {
    // The sequence, exactly: refresh(B) starts and captures oldKey = B's folder. A restore of the same
    // backup commits while the copy runs, so the SOURCE now runs what was B's folder and B holds what
    // the source was using. The refresh's continuation then wakes up holding oldKey -- which is now the
    // LIVE world -- and, before this fix, re-pointed B onto the new copy (orphaning the folder it had
    // just been given) and deleted the world the source runs now, with nothing loaded here to stop it.
    String liveKey = source.worldKey();
    String copyKey = backup.worldKey();
    worlds.duringCopy = () -> assertTrue(
        experiences.swapWithBackup(source.id(), backup.id(), "Death Resets (before restore)",
            System.currentTimeMillis()),
        "the restore is what this test is about; it must actually commit");

    assertEquals(List.of(ExperienceBackup.Outcome.FAILED), refresh(),
        "the backup this refresh was re-pointing is not on that folder any more, so there is nothing"
            + " it can correctly do; FAILED is the honest end and the owner repeats the click");

    assertEquals(copyKey, experiences.get(source.id()).worldKey(),
        "the restore stands: the source runs the copy's folder, which is what the owner asked for");
    assertEquals(liveKey, experiences.get(backup.id()).worldKey(),
        "and the backup row keeps the safety copy the restore handed it -- untouched, not re-pointed"
            + " onto a folder taken from a world it is no longer a copy of");
    assertEquals(List.of(destinationOfTheCopy()), worlds.deleted,
        "the only thing removed is the copy this refresh made and cannot use");
    assertFalse(worlds.deleted.contains(copyKey),
        "deleting this is deleting the terrain of the experience the owner is about to walk into");
    assertFalse(worlds.deleted.contains(liveKey),
        "and this is the safety copy the restore promised them");
  }

  @Test
  @DisplayName("a folder another row still names is left alone, and the refresh still succeeds")
  void aFolderSomeRowStillNamesIsNeverDeleted() throws Exception {
    // The three questions asked before the delete are all "does anybody have it OPEN". None of them
    // can see a row that NAMES it -- and a world nobody has opened yet answers "closed" on every node
    // while being some experience's terrain. The registry is the only thing that can say so.
    //
    // The unique index on `experiences.world_key` is what should make two rows on one folder
    // impossible, and it is dropped here for the same reason `swapWithBackup` counts rows instead of
    // trusting it: it was added late, and the live database it was added to already carried
    // duplicates. This is the state that database can still be in.
    String oldKey = backup.worldKey();
    synchronized (database.lock()) {
      try (java.sql.Statement drop = database.connection().createStatement()) {
        drop.executeUpdate("DROP INDEX IF EXISTS ux_experiences_world");
      }
    }
    String squatterId = ExperienceManager.newExperienceId();
    assertNotNull(experiences.createFrom(source, "Squatter", squatterId,
        WorldKey.parse(oldKey), System.currentTimeMillis(), null),
        "a second row naming that folder is the fixture; without it there is nothing to protect");

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), refresh(),
        "the row is already correct, which is the whole verb; an orphan an operator can see in the"
            + " log is not a failure to report to the owner");
    assertTrue(worlds.deleted.isEmpty(), "somebody's terrain is not this verb's to remove");
    assertEquals(oldKey, experiences.get(squatterId).worldKey(), "and that row is untouched");
    assertNotEquals(oldKey, experiences.get(backup.id()).worldKey(),
        "the backup names the fresh copy either way");
  }

  // ===== one verb at a time, per backup =========================================================

  @Test
  @DisplayName("a second refresh of the same backup is BUSY, and copies nothing")
  void twoRefreshesOfOneBackupDoNotBothRun() {
    // Both used to pass every gate, allocate a destination each and both complete. The second
    // re-point won, so the first one's folder ended up named by no row at all: hundreds of megabytes
    // that nothing lists, nothing collects and no owner can see.
    List<ExperienceBackup.Outcome> second = new ArrayList<>();
    worlds.duringCopy = () -> engine.refresh(owner, backup.id(), second::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), refresh());
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), second,
        "the same word the owner already understands from a world somebody has open, and the same"
            + " instruction: try again in a moment");
    assertEquals(1, worlds.copied.size(), "one destination allocated, so no folder can be orphaned");
  }

  @Test
  @DisplayName("a restore clicked while a refresh is copying is BUSY, and changes no row")
  void aRestoreDuringARefreshIsRefused() {
    String liveKey = source.worldKey();
    String copyKey = backup.worldKey();
    List<ExperienceBackup.Outcome> restored = new ArrayList<>();
    worlds.duringCopy = () -> engine.restore(owner, backup.id(), restored::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), refresh());
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), restored,
        "the refresh is holding both rows; a swap underneath it is the data-loss sequence itself");
    assertEquals(liveKey, experiences.get(source.id()).worldKey(), "no swap happened");
    assertEquals(List.of(copyKey), worlds.deleted,
        "the folder removed at the end is the one the backup used to name, and nothing else");
  }

  @Test
  @DisplayName("a plain backup of the same source is BUSY too — the claim covers the source id")
  void aBackupOfTheSameSourceDuringARefreshIsRefused() {
    List<ExperienceBackup.Outcome> copied = new ArrayList<>();
    worlds.duringCopy = () -> engine.backup(owner, source.id(), copied::add);

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), refresh());
    assertEquals(List.of(ExperienceBackup.Outcome.BUSY), copied);
    assertEquals(1, worlds.copied.size(), "only the refresh's own copy was taken");
  }

  @Test
  @DisplayName("the claim is released when the verb ends, however it ended")
  void theClaimIsNotLeaked() {
    worlds.copySucceeds = false;
    assertEquals(List.of(ExperienceBackup.Outcome.FAILED), refresh());

    worlds.copySucceeds = true;
    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), refresh(),
        "a failed refresh that kept its claim would make this backup un-refreshable until restart");
  }

  @Test
  @DisplayName("THE CLAIM THAT NEVER CAME BACK: the continuation THROWS, and both ids are still freed")
  void aContinuationThatThrowsStillReleasesTheClaim() {
    // The path, exactly, and it is not hypothetical: the continuation runs on the world thread through
    // PaperWorldControl's getGlobalRegionScheduler().run(...), which catches nothing, and everything it
    // does there talks to the database. ExperienceManager.transact asks Database.connection() OUTSIDE
    // its own try, and that call throws IllegalStateException("Failed to reconnect to the database")
    // when the round trip to a MariaDB that has just restarted fails. So the row write does not answer
    // null -- it throws, past `tell`, and the claim on BOTH ids stays taken for the life of the JVM.
    //
    // Every verb on either id then answers BUSY -- "try again in a moment" -- forever, which is
    // strictly worse than the failure it was guarding: that one cost a single backup.
    worlds.duringCopy = () ->
        failEverythingAfterTheCopy = new IllegalStateException("Failed to reconnect to the database");

    assertEquals(List.of(ExperienceBackup.Outcome.FAILED), refresh(),
        "a continuation that could not finish is told to the owner, exactly once, instead of being"
            + " thrown into a scheduler that catches nothing");

    worlds.duringCopy = null;
    failEverythingAfterTheCopy = null;
    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), refresh(),
        "and the very next click works: the claim covers the backup AND its source, so a leak here"
            + " freezes backup, duplicate, refresh and restore on both rows");

    // The source id half of the claim, said out loud: a plain backup of the source is the verb an
    // owner reaches for after a refresh they were told failed.
    List<ExperienceBackup.Outcome> copied = new ArrayList<>();
    engine.backup(owner, source.id(), copied::add);
    assertEquals(List.of(ExperienceBackup.Outcome.CREATED), copied);
  }

  @Test
  @DisplayName("the same holds for the CREATE path: a throw after the copy frees the source id")
  void aCreateContinuationThatThrowsReleasesTheClaim() {
    // backup() and duplicate() land in a different continuation from refresh()'s, and it holds a claim
    // on the source id for exactly the same window. One wrapper covers both; this is what says so.
    worlds.duringCopy = () ->
        failEverythingAfterTheCopy = new IllegalStateException("Failed to reconnect to the database");
    List<ExperienceBackup.Outcome> first = new ArrayList<>();
    engine.backup(owner, source.id(), first::add);
    assertEquals(List.of(ExperienceBackup.Outcome.FAILED), first,
        "answered once, from the catch, rather than never");

    worlds.duringCopy = null;
    failEverythingAfterTheCopy = null;
    List<ExperienceBackup.Outcome> second = new ArrayList<>();
    engine.backup(owner, source.id(), second::add);
    assertEquals(List.of(ExperienceBackup.Outcome.CREATED), second,
        "a leaked claim here would make the owner's world un-backupable, un-duplicable and"
            + " un-restorable until the node restarts");
  }

  // ===== the member pointers follow the folder ==================================================

  @Test
  @DisplayName("a refresh moves the backup's members onto the new folder before deleting the old one")
  void membersFollowTheFolder() {
    // The invariant swapWithBackup documents and keeps: a member row names the world its own
    // experience_id names. A refresh broke it and then DELETED the folder the member row still named,
    // so a player who walked into the copy and disconnected hard came back to rememberedWorldOf
    // handing them a world that no longer exists.
    UUID visitor = UUID.randomUUID();
    String oldKey = backup.worldKey();
    experiences.rememberPlayerWorld(backup.id(), visitor, WorldKey.parse(oldKey), 1_000L);
    assertEquals(oldKey, experiences.rememberedWorldOf(visitor).key(), "fixture");

    assertEquals(List.of(ExperienceBackup.Outcome.REFRESHED), refresh());

    String fresh = experiences.get(backup.id()).worldKey();
    assertEquals(fresh, experiences.rememberedWorldOf(visitor).key(),
        "the pointer follows the folder in the same transaction as the re-point, so it never names"
            + " the folder the next line deletes");
    assertEquals(List.of(oldKey), worlds.deleted);
  }
}
