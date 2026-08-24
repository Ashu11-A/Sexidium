package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.noop.NoopKitAdapter;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A copy that outlives its source counts against the cap, because it is a world now.
 *
 * <p>The bug this pins is an unbounded one and it looks like nothing while it happens.
 * {@code countByOwner} excludes any row carrying a {@code backup_of}, and the delete path dropped the
 * source row without touching the copies — so "create, back up three times, delete the source, repeat"
 * grew the shared experiences tree by a few hundred megabytes an iteration for ever, with the
 * per-player cap reading perfectly healthy the whole time.</p>
 */
class ExperienceOrphanBackupTest {

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
  private ExperienceService service;
  private ExperienceManager.Experience source;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    Database database = new Database(new File(tmp.toFile(), "orphans.db"));
    experiences = new ExperienceManager(SILENT, database);
    FakeExperienceWorlds worlds = new FakeExperienceWorlds();
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public com.sexidium.core.platform.WorldLeaseService worlds() { return worlds; }
    };
    GameManager games = new GameManager(
        new GameContext(server, new NoopKitAdapter(), RankAwardPort.noop()), new GameRegistry(), null);
    service = new ExperienceService(server, games, experiences, null, null);
    source = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Death Resets",
        System.currentTimeMillis());
    assertNotNull(source);
  }

  private List<ExperienceManager.Experience> takeBackups(int count) {
    List<ExperienceManager.Experience> taken = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      String id = ExperienceManager.newExperienceId();
      ExperienceManager.Experience copy = experiences.createBackup(source, "Copy " + index, id,
          WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, Integer.MAX_VALUE);
      assertNotNull(copy);
      taken.add(copy);
    }
    return taken;
  }

  @Test
  @DisplayName("deleting a source promotes its copies, and they then count against the world cap")
  void deletingASourcePromotesItsCopies() {
    List<ExperienceManager.Experience> copies = takeBackups(3);
    assertEquals(1, experiences.countByOwner(owner), "backups do not spend a world slot -- yet");

    assertEquals(3, experiences.promoteOrphanBackups(source.id()));
    experiences.remove(source.id());

    assertEquals(3, experiences.countByOwner(owner),
        "three worlds' worth of disk is three worlds' worth of allowance");
    for (ExperienceManager.Experience copy : copies) {
      assertFalse(experiences.get(copy.id()).isBackup(),
          "a copy whose source is gone is not a copy of anything; it is the only record of that run");
    }
  }

  @Test
  @DisplayName("the delete path promotes them itself — the caller does not have to remember")
  void theDeletePathPromotes() {
    takeBackups(2);
    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    service.delete(owner, source.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.DELETED), outcomes);
    assertEquals(2, experiences.countByOwner(owner));
    assertTrue(experiences.backupsOf(source.id()).isEmpty(),
        "nothing is left pointing at a row that no longer exists");
  }

  @Test
  @DisplayName("promotion is idempotent, because a delete request can be replayed")
  void promotionIsIdempotent() {
    takeBackups(2);
    assertEquals(2, experiences.promoteOrphanBackups(source.id()));
    assertEquals(0, experiences.promoteOrphanBackups(source.id()),
        "the second run has nothing left to promote and must not be an error");
    assertEquals(3, experiences.countByOwner(owner),
        "the source is still there in this test; only the copies were promoted");
  }
}
