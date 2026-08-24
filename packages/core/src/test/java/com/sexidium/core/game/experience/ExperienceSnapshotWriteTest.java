package com.sexidium.core.game.experience;

import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snapshot write that did not happen must never read as one that did.
 *
 * <p>Three callers clear the player's live inventory on the strength of this answer —
 * {@code ExperienceGame.stop}, {@code ExperienceGame.onParticipantLeaving} and
 * {@code ChaosGame.onParticipantLeaving} — so "stored" is a load-bearing word and it used to be a
 * constant: the write swallowed its {@code IOException}, returned void, and
 * {@code capturePlayerState} answered {@code true} regardless. A full volume, a read-only mount or a
 * permission fault therefore ended with the items deleted by the code that exists to save them.</p>
 *
 * <p>The second half is that the write is now whole-or-nothing. {@code Files.writeString} opens with
 * TRUNCATE_EXISTING, so a failure part way through had already emptied the file holding the good
 * snapshot: the previous save was destroyed by the attempt to replace it.</p>
 */
class ExperienceSnapshotWriteTest {

  private static final String WORLD = "Ashu11a/Death_Resets_ab12cd34";

  private static PlayerSnapshot snapshotOf(UUID player, double health) {
    PlayerSnapshot snapshot = new PlayerSnapshot(player, "Ashu11a", null);
    snapshot.worldName = WORLD;
    snapshot.coordinateX = 12.5;
    snapshot.coordinateY = 64.0;
    snapshot.coordinateZ = -8.5;
    snapshot.health = health;
    return snapshot;
  }

  @Test
  @DisplayName("a write that lands answers true; one that cannot answers false")
  void theAnswerFollowsTheDisk(@TempDir Path subdir) throws IOException {
    ExperienceStateStore store = new ExperienceStateStore(subdir, new StdoutLoggerAdapter("Test"));
    Files.createDirectories(subdir.resolve(WORLD));
    UUID player = UUID.randomUUID();

    assertTrue(store.savePlayerSnapshot(WORLD, snapshotOf(player, 100.0)),
        "an ordinary save says so, and that is what licenses the caller to clear the inventory");

    Path stateDir = subdir.resolve(WORLD).resolve("sexidium");
    Path players = stateDir.resolve("players");
    Files.setPosixFilePermissions(players, PosixFilePermissions.fromString("r-xr-xr-x"));
    Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeTrue(!Files.isWritable(players) && !Files.isWritable(stateDir),
          "this test needs directories it cannot write into");

      assertFalse(store.savePlayerSnapshot(WORLD, snapshotOf(player, 20.0)),
          "the file did not change, so the caller must NOT go on to clear the live inventory");
      assertFalse(store.saveSharedState(WORLD, ExperienceState.empty()),
          "the shared counters answer the same way, for the same reason");

      assertEquals(100.0, store.loadPlayerSnapshot(WORLD, player, "Ashu11a").health,
          "and the snapshot that WAS there is still there: the write is a temp file and a rename, so"
              + " a failure never truncates the good save on its way to failing");
    } finally {
      // Restore, or the temp-dir cleanup cannot remove the tree.
      Files.setPosixFilePermissions(stateDir, PosixFilePermissions.fromString("rwxr-xr-x"));
      Files.setPosixFilePermissions(players, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test
  @DisplayName("a transient experience has no folder, saves nothing, and says nothing was saved")
  void aTransientExperienceSavesNothing(@TempDir Path subdir) {
    ExperienceStateStore store = new ExperienceStateStore(subdir, new StdoutLoggerAdapter("Test"));
    assertFalse(store.savePlayerSnapshot(WORLD, snapshotOf(UUID.randomUUID(), 100.0)),
        "clearing an inventory here would destroy the items instead of parking them");
    assertFalse(store.saveSharedState(WORLD, ExperienceState.empty()));
  }

  @Test
  @DisplayName("no half-written file is left where a reader could pick it up as the snapshot")
  void nothingPartialSurvives(@TempDir Path subdir) throws IOException {
    ExperienceStateStore store = new ExperienceStateStore(subdir, new StdoutLoggerAdapter("Test"));
    Files.createDirectories(subdir.resolve(WORLD));
    UUID player = UUID.randomUUID();
    assertTrue(store.savePlayerSnapshot(WORLD, snapshotOf(player, 100.0)));

    Path players = subdir.resolve(WORLD).resolve("sexidium").resolve("players");
    try (var entries = Files.list(players)) {
      assertEquals(1, entries.count(),
          "the temp file the write publishes through is renamed onto the target, never left beside"
              + " it: a directory of `.writing-*` leftovers is what a reader would have to learn to"
              + " ignore");
    }
    assertTrue(Files.isRegularFile(players.resolve(player + ".yml")));
  }
}
