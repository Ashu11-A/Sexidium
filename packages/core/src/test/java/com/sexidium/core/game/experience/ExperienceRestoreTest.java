package com.sexidium.core.game.experience;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The restore itself, against a real database: it copies nothing and rewrites who names what.
 *
 * <p>Every claim here is a claim about ROWS, which is why none of it is a source-text guard. A restore
 * that half-happens leaves a live world naming a folder that is not there, or two rows naming one
 * folder — and both of those are invisible until somebody walks into them.</p>
 */
class ExperienceRestoreTest {

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
  private ExperienceManager.Experience source;
  private ExperienceManager.Experience backup;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "restore.db"));
    experiences = new ExperienceManager(SILENT, database);
    source = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Death Resets",
        System.currentTimeMillis());
    assertNotNull(source);
    backup = takeBackup("Death Resets (backup)");
  }

  private ExperienceManager.Experience takeBackup(String name) {
    String id = ExperienceManager.newExperienceId();
    ExperienceManager.Experience stored = experiences.createBackup(
        experiences.get(source.id()), name, id, WorldKey.of("Death Resets", id),
        System.currentTimeMillis(), "days=27", Integer.MAX_VALUE);
    assertNotNull(stored, "the fixture needs a stored backup");
    return stored;
  }

  private int rowsNaming(String worldKey) throws Exception {
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT COUNT(*) FROM experiences WHERE world_key=?")) {
        statement.setString(1, worldKey);
        try (ResultSet counted = statement.executeQuery()) {
          return counted.next() ? counted.getInt(1) : 0;
        }
      }
    }
  }

  @Test
  @DisplayName("the swap moves no bytes: the two rows trade world keys and nothing else appears")
  void theSwapTradesWorldKeys() throws Exception {
    String liveKey = source.worldKey();
    String copyKey = backup.worldKey();
    assertNotEquals(liveKey, copyKey);

    assertTrue(experiences.swapWithBackup(source.id(), backup.id(),
        ExperienceBackupService.safetyDisplayName(source), 4_000L));

    assertEquals(copyKey, experiences.get(source.id()).worldKey(),
        "the source experience runs the backup's world now");
    assertEquals(liveKey, experiences.get(backup.id()).worldKey(),
        "and the copy wears the world it replaced -- which is already a perfect backup of itself");
    assertEquals(1, rowsNaming(liveKey), "exactly one row per world_key, always");
    assertEquals(1, rowsNaming(copyKey), "exactly one row per world_key, always");
    assertEquals(2, experiences.byOwner(owner).size(), "no row was created and none was lost");
  }

  @Test
  @DisplayName("the safety row is renamed, forced private, and stamped as taken NOW")
  void theSafetyRowIsANewCopy() {
    experiences.setVisibility(backup.id(), true, 1_000L);
    assertTrue(experiences.swapWithBackup(source.id(), backup.id(),
        ExperienceBackupService.safetyDisplayName(source), 9_000L));

    ExperienceManager.Experience safety = experiences.get(backup.id());
    assertEquals("Death Resets (before restore)", safety.displayName(),
        "the owner has to be able to tell which of the two is the world they walked away from");
    assertFalse(safety.isPublic(),
        "a copy taken by a restore was never offered to anybody; publishing it is the owner's choice");
    assertEquals(9_000L, safety.createdAt(),
        "as a copy, it was taken now -- leaving created_at at the first take would sort it as the"
            + " OLDEST copy in a list ordered by when each was the world");
    assertTrue(safety.isBackup(), "it is still a backup of the same source; only the folder moved");
  }

  @Test
  @DisplayName("the safety row inherits the old key, and no live world shares its base")
  void theSafetyRowInheritsTheOldGeneration() {
    // A world that has been through Death Resets is at generation _r7. After the swap that key belongs
    // to the SAFETY row, which breaks the letter of "a backup is always generation 0 of a fresh base"
    // and keeps its whole purpose: that rule exists so a backup never shares a base with a live world,
    // and after the swap nothing else names that base at all.
    WorldKey generation = WorldKey.parse(source.worldKey()).nextGeneration();
    experiences.updateWorldKey(source.id(), generation, 1_000L);
    ExperienceManager.Experience live = experiences.get(source.id());
    assertEquals(generation.key(), live.worldKey());

    assertTrue(experiences.swapWithBackup(live.id(), backup.id(),
        ExperienceBackupService.safetyDisplayName(live), 5_000L));

    WorldKey safetyKey = experiences.get(backup.id()).key();
    WorldKey restoredKey = experiences.get(source.id()).key();
    assertEquals(generation.key(), safetyKey.key());
    assertFalse(restoredKey.sameRun(safetyKey),
        "the restored world is generation 0 of a FRESH base by construction of the backup, so a later"
            + " Death Reset on it can never collide with the safety row's lineage");
  }

  @Test
  @DisplayName("everybody in the source follows it onto the restored world, in the same transaction")
  void membersFollowTheWorld() {
    UUID member = UUID.randomUUID();
    experiences.rememberPlayerWorld(source.id(), member, source.key(), 1_000L);
    assertEquals(source.worldKey(), experiences.rememberedWorldOf(member).key());

    assertTrue(experiences.swapWithBackup(source.id(), backup.id(),
        ExperienceBackupService.safetyDisplayName(source), 6_000L));

    assertEquals(backup.worldKey(), experiences.rememberedWorldOf(member).key(),
        "a member offline across the restore would otherwise come back to a folder their experience no"
            + " longer names, and land in the node's default overworld");
  }

  @Test
  @DisplayName("the COPY's members follow their folder too, or they end up naming somebody else's")
  void membersOfTheBackupFollowItAsWell() throws Exception {
    // How this happens without anybody doing anything strange: the owner enters the backup to look
    // around (which writes a pointer at the COPY's world), disconnects hard so the leave path never
    // drops the row, then clicks Restore. Moving only the source's pointers leaves this row naming
    // copyKey -- the world the SOURCE now runs -- and `rememberedWorldOf` hands back a world that
    // belongs to a different experience row entirely.
    UUID visitor = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    experiences.rememberPlayerWorld(backup.id(), visitor, backup.key(), 1_000L);
    experiences.rememberPlayerWorld(source.id(), member, source.key(), 1_000L);
    String liveKey = source.worldKey();
    String copyKey = backup.worldKey();

    assertTrue(experiences.swapWithBackup(source.id(), backup.id(),
        ExperienceBackupService.safetyDisplayName(source), 6_500L));

    assertEquals(liveKey, memberWorldOf(backup.id(), visitor),
        "the invariant is per ROW: a member row must name the world its own experience_id names,"
            + " and after the swap that is the safety copy's folder");
    assertEquals(liveKey, experiences.rememberedWorldOf(visitor).key(),
        "otherwise the visitor comes back into the world the SOURCE is now running, and the state"
            + " loaded for the backup is a snapshot the entry guard rejects as foreign");
    assertEquals(copyKey, memberWorldOf(source.id(), member),
        "and the source's own members still moved the other way, exactly as before");
  }

  /** The world_key stored for one player in one experience, straight out of the table. */
  private String memberWorldOf(String experienceId, UUID player) throws Exception {
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT world_key FROM experience_players WHERE experience_id=? AND player_uuid=?")) {
        statement.setString(1, experienceId);
        statement.setString(2, player.toString());
        try (ResultSet found = statement.executeQuery()) {
          return found.next() ? found.getString(1) : null;
        }
      }
    }
  }

  @Test
  @DisplayName("the run's counters travel with the folder, which is what rolling a run back MEANS")
  void theCountersFollowTheirFolder() {
    experiences.updateChallengeState(source.id(), "stats.run.seconds.total=62983", 1_000L);
    assertTrue(experiences.swapWithBackup(source.id(), backup.id(),
        ExperienceBackupService.safetyDisplayName(source), 7_000L));

    assertEquals("days=27", experiences.challengeState(source.id()),
        "the restored world's counters are the ones inside the folder it now names");
    assertEquals("stats.run.seconds.total=62983", experiences.challengeState(backup.id()),
        "and the world that was live keeps its own, so restoring the restore gives them back");
  }

  @Test
  @DisplayName("INTENDED: restoring a copy taken before a hardcore death gives back a world that is"
      + " not dead — this is the escape hatch, not a regression")
  void aPreDeathBackupRestoresALivingWorld() {
    // Read this one carefully before "fixing" it. `markDead` is one-way on purpose and stays one-way:
    // nothing here revives a row. What happens is that the SOURCE row takes on the backup's world and
    // the flags that world was copied with -- and a copy taken before the death was copied with
    // dead=0. Entering a pre-death backup is already the sanctioned way back from a hardcore loss,
    // documented and shipped; a restore only changes which row wears which world.
    experiences.updateHardcore(source.id(), true, 1_000L);
    ExperienceManager.Experience beforeTheDeath = takeBackup("Death Resets (before the death)");
    assertTrue(beforeTheDeath.hardcore());
    assertFalse(beforeTheDeath.dead());

    experiences.markDead(source.id(), 2_000L);
    assertTrue(experiences.get(source.id()).isLost(), "the run really did end");

    assertTrue(experiences.swapWithBackup(source.id(), beforeTheDeath.id(),
        ExperienceBackupService.safetyDisplayName(source), 8_000L));

    ExperienceManager.Experience restored = experiences.get(source.id());
    assertTrue(restored.hardcore(), "it is still a hardcore world -- that was never in question");
    assertFalse(restored.dead(),
        "and it is playable again, because the world it now names is one where nobody had died yet");
    assertTrue(experiences.get(beforeTheDeath.id()).dead(),
        "the world where the death happened keeps the death; it is the copy now");
  }

  @Test
  @DisplayName("a swap that names the wrong pair changes nothing at all")
  void anImpossibleSwapIsRefusedWhole() {
    ExperienceManager.Experience other = experiences.create(owner, "Ashu11a", List.of(), "Other",
        System.currentTimeMillis());
    assertNotNull(other);

    // `backup` is a copy of `source`, not of `other`. Nothing about the pair is restorable.
    assertFalse(experiences.swapWithBackup(other.id(), backup.id(), "nope", 3_000L));
    assertEquals(source.worldKey(), experiences.get(source.id()).worldKey());
    assertEquals(backup.worldKey(), experiences.get(backup.id()).worldKey());
    assertEquals(other.worldKey(), experiences.get(other.id()).worldKey());
  }

  @Test
  @DisplayName("a torn transaction rolls BOTH rows back, and never leaves two on one world_key")
  void aTornSwapRollsBack() throws Exception {
    String liveKey = source.worldKey();
    String copyKey = backup.worldKey();

    // The failure the swap exists to survive: the surgery is half done and something throws. Injected
    // by running the same statements the swap runs and then failing inside the same transaction.
    experiences.transact(connection -> {
      try (PreparedStatement park = connection.prepareStatement(
          "UPDATE experiences SET world_key=? WHERE id=?")) {
        park.setString(1, copyKey + "#swapping-test");
        park.setString(2, backup.id());
        park.executeUpdate();
      }
      try (PreparedStatement live = connection.prepareStatement(
          "UPDATE experiences SET world_key=? WHERE id=?")) {
        live.setString(1, copyKey);
        live.setString(2, source.id());
        live.executeUpdate();
      }
      throw new IllegalStateException("the node went down between the two updates");
    });

    assertEquals(liveKey, experiences.get(source.id()).worldKey(),
        "a half-applied swap must leave both rows EXACTLY as they were");
    assertEquals(copyKey, experiences.get(backup.id()).worldKey());
    assertEquals(1, rowsNaming(liveKey));
    assertEquals(1, rowsNaming(copyKey));
  }

  @Test
  @DisplayName("transact commits only what returns a value, and rolls back a null")
  void transactRollsBackOnNull() {
    assertTrue(experiences.transact(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE experiences SET display_name=? WHERE id=?")) {
        statement.setString(1, "Renamed");
        statement.setString(2, source.id());
        statement.executeUpdate();
      }
      return null; // "I changed my mind": nothing above may survive
    }).isEmpty());
    assertEquals("Death Resets", experiences.get(source.id()).displayName());
  }
}
