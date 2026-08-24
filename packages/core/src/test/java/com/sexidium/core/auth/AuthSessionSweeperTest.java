package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthRequestRepository.RequestRow;
import com.sexidium.core.auth.AuthSessionRepository.SessionRow;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** One pass over the three expiring tables, and the promise that failing it costs nothing but growth. */
class AuthSessionSweeperTest {

  private Database db;
  private AuthSessionRepository sessions;
  private AuthRequestRepository requests;
  private AuthIpBlockRepository blocks;
  private AuthSessionSweeper sweeper;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-sweep-test");
    db = new Database(dir.resolve("sweep.db").toFile());
    sessions = new AuthSessionRepository(db);
    requests = new AuthRequestRepository(db);
    blocks = new AuthIpBlockRepository(db);
    sweeper = new AuthSessionSweeper(sessions, requests, blocks, new StdoutLoggerAdapter("T"));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("one pass expires sessions, times out unanswered requests and lifts stale blocks")
  void sweepsAllThreeTables() throws Exception {
    long now = System.currentTimeMillis();
    sessions.upsert(new SessionRow("id-1", "hash-a", "s1", "steve", null, "java", "node", null,
        0L, 0L, now - 1_000L, now + 100_000L, null));
    requests.insert(new RequestRow("req-1", "id-1", "steve", "Steve", "discord-1", "hash-a", null,
        AuthRequestRepository.KIND_SESSION, AuthRequestRepository.STATE_PENDING, false, "node",
        null, 0L, null, null, null, 0, 0L, now - 1_000L, null));
    blocks.block("id-1", "hash-a", "denied", 0L, now - 1_000L);

    sweeper.tick();

    assertNull(sessions.find("id-1", "hash-a"));
    assertEquals(AuthRequestRepository.STATE_EXPIRED, requests.byId("req-1").state());
    assertFalse(blocks.blocked("id-1", "hash-a", now));
  }

  @Test
  @DisplayName("live rows survive the sweep")
  void livedataIsUntouched() throws Exception {
    long now = System.currentTimeMillis();
    sessions.upsert(new SessionRow("id-1", "hash-a", "s1", "steve", null, "java", "node", null,
        0L, 0L, now + 100_000L, now + 200_000L, null));
    blocks.block("id-1", "hash-b", "denied", 0L, now + 100_000L);

    sweeper.tick();

    assertNotNull(sessions.find("id-1", "hash-a"));
    assertTrue(blocks.blocked("id-1", "hash-b", now));
  }

  @Test
  @DisplayName("a database that will not answer costs a log line, never the timer")
  void aFailedSweepIsSurvivable() {
    db.close();
    assertDoesNotThrow(sweeper::tick);
  }
}
