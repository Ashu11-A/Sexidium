package com.sexidium.core.command;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /sx admin broadcast <seconds> <update|restart|shutdown>} — the orchestrator's countdown.
 *
 * <p>The pipeline sends a NUMBER and a REASON KEY, never a sentence, and this is what makes that
 * possible. It cannot do otherwise and be correct: it has no idea what language each player reads, so
 * formatting the warning on the scripting side would be one language for everybody and a second copy
 * of a string that is already localized here. Until this command existed the pipeline fell back to
 * {@code minecraft:say} in English, announced as a warning.</p>
 */
class AdminBroadcastTest {

  private Path tempDir;
  private Database database;
  private CommandTestFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("sexidium-broadcast-test");
    database = new Database(tempDir.resolve("sexidium.db").toFile());
    fixture = CommandTestFixture.create(tempDir, database, true);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (fixture != null) {
      fixture.close();
    }
    if (database != null) {
      database.close();
    }
    if (tempDir != null) {
      Files.walk(tempDir)
          .sorted((left, right) -> right.compareTo(left))
          .forEach(path -> {
            try {
              Files.deleteIfExists(path);
            } catch (IOException ignored) {
              // Best effort; the temp directory is the OS's problem after this.
            }
          });
    }
  }

  /**
   * A console-shaped source: it has the permission but is NOT an online player.
   *
   * <p>{@code fixture.admin(...)} registers its caller as online, which would quietly make "nobody is
   * online" untestable — and that branch is the one the pipeline relies on every time it rolls an
   * empty worker at four in the morning.</p>
   */
  private FakeSource console() {
    return new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
  }

  private void run(String... args) {
    fixture.commands.execute(console(), args);
  }

  @Test
  @DisplayName("every online player is warned, in a localized message and not a literal string")
  void warnsEveryPlayerWithALocalizedMessage() {
    fixture.player("Alice");
    fixture.player("Bob");

    run("admin", "broadcast", "60", "update");

    assertTrue(fixture.messages.sent.stream()
            .filter("network.broadcast-update"::equals).count() >= 2,
        "the pipeline sends seconds + a reason key; the WORDS are ours, per player, in their own"
            + " language");
  }

  @Test
  @DisplayName("restart and shutdown are their own messages, not one wording for all three")
  void eachReasonHasItsOwnWording() {
    fixture.player("Alice");

    run("admin", "broadcast", "30", "restart");
    assertTrue(fixture.messages.sent.contains("network.broadcast-restart"));

    run("admin", "broadcast", "10", "shutdown");
    assertTrue(fixture.messages.sent.contains("network.broadcast-shutdown"));
  }

  @Test
  @DisplayName("an unknown reason still warns, because the countdown matters more than the adjective")
  void anUnknownReasonStillWarns() {
    fixture.player("Alice");

    run("admin", "broadcast", "60", "somethingThisBuildHasNeverHeardOf");

    assertTrue(fixture.messages.sent.contains("network.broadcast-update"),
        "a future pipeline sending a reason this build predates must not produce SILENCE");
  }

  @Test
  @DisplayName("with nobody online it is a no-op, which is why the pipeline can call it every time")
  void noOpWhenNobodyIsOnline() {
    run("admin", "broadcast", "60", "update");

    assertFalse(fixture.messages.sent.contains("network.broadcast-update"));
  }

  @Test
  @DisplayName("bad arguments print usage and never throw: a chat message must not abort a roll")
  void badArgumentsNeverThrow() {
    fixture.player("Alice");

    run("admin", "broadcast");
    run("admin", "broadcast", "60");
    run("admin", "broadcast", "notanumber", "update");
    run("admin", "broadcast", "-5", "update");

    assertFalse(fixture.messages.sent.contains("network.broadcast-update"));
  }

  @Test
  @DisplayName("broadcast is offered in the admin completions, so an operator can find it")
  void isSuggested() {
    assertTrue(fixture.commands.suggest(console(), new String[]{"admin", "bro"})
        .contains("broadcast"));
    assertTrue(fixture.commands.suggest(console(),
        new String[]{"admin", "broadcast", "60", ""}).contains("restart"));
  }
}
