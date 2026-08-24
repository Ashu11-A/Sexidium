package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table an owner's delete is written into before it is announced.
 *
 * <p>Against a real {@link Database}, because the whole point of the row is that it survives things an
 * in-memory stand-in cannot fail at: a bus message lost while the target node was restarting, the same
 * message delivered twice, and an answer that has to be readable by whoever asks later.</p>
 */
class DbExperienceCommandStoreTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private DbExperienceCommandStore commands;

  @BeforeEach
  void setUp() throws Exception {
    commands = new DbExperienceCommandStore(
        new Database(new File(tmp.toFile(), "commands.db")), SILENT);
  }

  private boolean submit(String id, String target, long deadline) {
    long now = System.currentTimeMillis();
    return commands.insert(new ExperienceCommandStore.Command(
        id, "exp-1", "death_resets_ab12cd34", "DELETE", "key=death_resets_ab12cd34",
        "lobby", target, ExperienceCommandStore.State.PENDING, null, deadline, now, now));
  }

  @Test
  @DisplayName("a request is readable by whoever it was addressed to, and by whoever asked")
  void aRequestIsRecorded() {
    assertTrue(submit("req-1", "worker-2", System.currentTimeMillis() + 60_000L));

    List<ExperienceCommandStore.Command> mine = commands.pendingFor("worker-2", 0L);
    assertEquals(1, mine.size());
    assertEquals("exp-1", mine.get(0).experienceId());
    assertEquals("key=death_resets_ab12cd34", mine.get(0).args(),
        "the world key travels with the request: the node that runs it may find the registry row"
            + " already gone and still has to know which folder to remove");
    assertTrue(commands.pendingFor("worker-3", 0L).isEmpty(), "addressed, not broadcast");
  }

  @Test
  @DisplayName("exactly ONE claim wins, which is what makes a replayed message harmless")
  void onlyOneClaimWins() {
    submit("req-1", "worker-2", System.currentTimeMillis() + 60_000L);

    assertTrue(commands.claim("req-1", "worker-2").isPresent());
    assertTrue(commands.claim("req-1", "worker-2").isEmpty(),
        "the second delivery must not delete the world a second time");
    assertTrue(commands.claim("req-1", "worker-3").isEmpty(), "and neither may a peer");
    assertTrue(commands.pendingFor("worker-2", 0L).isEmpty(), "it is no longer waiting to be run");
  }

  @Test
  @DisplayName("the answer is the row: applied, declined, and both readable afterwards")
  void theAnswerIsTheRow() {
    submit("req-1", "worker-2", System.currentTimeMillis() + 60_000L);
    commands.claim("req-1", "worker-2");

    assertTrue(commands.complete("req-1", true, "applied on 'worker-2'"));
    assertEquals(ExperienceCommandStore.State.DONE, commands.byId("req-1").orElseThrow().state());
    assertTrue(commands.byId("req-1").orElseThrow().terminal());
    assertFalse(commands.complete("req-1", false, "changed my mind"),
        "an answered request cannot be re-answered by a node that came back and tried again");

    submit("req-2", "worker-2", System.currentTimeMillis() + 60_000L);
    commands.claim("req-2", "worker-2");
    commands.complete("req-2", false, "players still inside");
    assertEquals(ExperienceCommandStore.State.FAILED, commands.byId("req-2").orElseThrow().state());
    assertEquals("players still inside", commands.byId("req-2").orElseThrow().detail());
  }

  @Test
  @DisplayName("a request outlives the owner's patience, and only its DEADLINE ends it")
  void onlyTheDeadlineExpiresIt() {
    long now = System.currentTimeMillis();
    submit("req-1", "worker-2", now + 60_000L);
    submit("req-2", "gone-forever", now - 1L);

    assertEquals(1, commands.expire(now), "the live one is still worth running when its node returns");
    assertEquals(ExperienceCommandStore.State.PENDING, commands.byId("req-1").orElseThrow().state());
    assertEquals(ExperienceCommandStore.State.EXPIRED, commands.byId("req-2").orElseThrow().state());
  }
}
