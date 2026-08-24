package com.sexidium.core.game.persist;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A match belongs to the node running it.
 *
 * <p>{@code matches} had no {@code node_id}, so {@code loadAll()} read the whole shared table and every
 * node imported every other node's live matches. Two failures fell straight out of that. The lobby
 * restarts, reads worker-2's row, and indexes worker-2's players as pending — so the next reconnect
 * ran {@code PendingMatchStore.rehydrate}, which opened the same shared world folder worker-2 had open
 * through a door with no placement check at all. And ten minutes after every boot,
 * {@code discardStalePending} deleted every OTHER node's live match rows as stale.</p>
 */
class PendingMatchNodeScopeTest {

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

  private MatchRepository repositoryFor(String nodeId) throws Exception {
    if (database == null) {
      database = new Database(new File(tmp.toFile(), "matches.db"));
    }
    MatchRepository repository = new MatchRepository(SILENT, database);
    repository.setNodeId(nodeId);
    return repository;
  }

  private static MatchSnapshot snapshot(String modeId, String worldName) {
    MatchSnapshot snapshot = new MatchSnapshot(UUID.randomUUID(), modeId);
    snapshot.worldName = worldName;
    snapshot.createdAt = 1_000L;
    return snapshot;
  }

  @Test
  @DisplayName("a node reads back only its OWN matches")
  void aNodeReadsOnlyItsOwnMatches() throws Exception {
    MatchRepository workerOne = repositoryFor("worker-1");
    MatchRepository workerTwo = repositoryFor("worker-2");
    MatchRepository lobby = repositoryFor("lobby");

    workerOne.saveBlocking(snapshot("tntwar", "sexidium_temp/a"));
    workerTwo.saveBlocking(snapshot("tntwar", "sexidium_temp/b"));

    assertEquals(1, workerOne.loadAll().size());
    assertEquals(1, workerTwo.loadAll().size());
    assertEquals("sexidium_temp/a", workerOne.loadAll().get(0).worldName);
    assertTrue(lobby.loadAll().isEmpty(),
        "the lobby importing a worker's live match is how a shared world folder got opened twice");
  }

  @Test
  @DisplayName("deleting one node's match leaves another node's alone")
  void deletingIsScopedToo() throws Exception {
    MatchRepository workerOne = repositoryFor("worker-1");
    MatchRepository workerTwo = repositoryFor("worker-2");
    MatchSnapshot mine = snapshot("tntwar", "sexidium_temp/a");
    MatchSnapshot theirs = snapshot("tntwar", "sexidium_temp/b");
    workerOne.saveBlocking(mine);
    workerTwo.saveBlocking(theirs);

    // discardStalePending runs ten minutes after every boot, over whatever loadAll() returned.
    for (MatchSnapshot stale : workerOne.loadAll()) {
      workerOne.deleteAsync(stale.matchId);
    }
    workerOne.shutdown();
    // deleteAsync is queued on the writer thread; give it the same shutdown wait the server does.
    Thread.sleep(200L);

    assertEquals(1, workerTwo.loadAll().size(),
        "a boot on one node must never delete another node's live match row");
  }

  @Test
  @DisplayName("standalone keeps its own scope without configuring anything")
  void standaloneIsItsOwnScope() throws Exception {
    MatchRepository standalone = repositoryFor("");
    standalone.saveBlocking(snapshot("tntwar", "sexidium_temp/a"));

    assertEquals(1, standalone.loadAll().size());
  }

}
