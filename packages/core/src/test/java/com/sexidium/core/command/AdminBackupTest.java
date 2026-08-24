package com.sexidium.core.command;

import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /sx admin backup …} — the headless surface for experience backups.
 *
 * <p>This is the prerequisite the whole backup feature was missing: before it existed the ONLY entry
 * point was a tile in the lobby menu, so nothing about a backup could be exercised on the live network
 * without a client standing in the lobby. Every test here is therefore about the two things an
 * operator depends on and a GUI cannot give them — that a verb <b>parses and routes</b>, and that the
 * answer says which row it is about.</p>
 *
 * <p>Console-shaped source throughout ({@code fixture.admin(...)} would register its caller as an
 * online player, which is exactly the thing these commands must not require).</p>
 */
class AdminBackupTest {

  private Path tempDir;
  private Database database;
  private CommandTestFixture fixture;
  private ExperienceManager registry;
  private ExperienceManager.Experience source;
  private ExperienceManager.Experience copy;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("sexidium-admin-backup-test");
    database = new Database(tempDir.resolve("sexidium.db").toFile());
    fixture = CommandTestFixture.create(tempDir, database, true);
    registry = fixture.core.experiences();
    long now = System.currentTimeMillis();
    // Deliberately the SAME display name for both rows: that is what the live database holds, because
    // backupDisplayName's " (backup)" suffix never reaches the row that is written. The id is the only
    // thing that tells them apart, which is why the console output leads with it.
    source = registry.create(UUID.randomUUID(), "Alice", List.of("deathresets"), "Death Resets", now);
    copy = registry.createBackup(source, source.displayName(), now);
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

  private FakeSource console() {
    return new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
  }

  private void run(String... args) {
    fixture.commands.execute(console(), args);
  }

  private String output() {
    return String.join("\n", fixture.messages.sent);
  }

  @Test
  @DisplayName("list names every copy of a world, by id, with the world it is a copy of")
  void listsTheCopiesOfAWorld() {
    run("admin", "backup", "list", source.id());

    String output = output();
    assertTrue(output.contains(copy.id()),
        "the id leads, because both rows carry the same display name on the live database");
    assertTrue(output.contains("age="), "an operator scanning a list wants the age, not a timestamp");
  }

  @Test
  @DisplayName("a copy's id lists its SOURCE's copies, rather than answering nothing")
  void namingACopyListsItsSource() {
    run("admin", "backup", "list", copy.id());

    assertTrue(output().contains("is itself a copy"),
        "an operator who copy-pasted the wrong id gets told so AND gets the list they meant");
    assertTrue(output().contains(copy.id()));
  }

  @Test
  @DisplayName("list by player name works while that player is connected")
  void listsByPlayer() {
    FakePlayer bob = fixture.player("Bob");
    ExperienceManager.Experience his = registry.create(bob.uniqueId(), "Bob", List.of("randommob"),
        "Bob's World", System.currentTimeMillis());
    registry.createBackup(his, his.displayName(), System.currentTimeMillis());
    fixture.messages.sent.clear();

    run("admin", "backup", "list", "Bob");

    assertTrue(output().contains("Bob"));
    assertTrue(output().contains("1</white> copy"), "one copy across one world: " + output());
  }

  @Test
  @DisplayName("a bare list with nobody online says why it cannot answer, instead of printing nothing")
  void aBareListExplainsItself() {
    run("admin", "backup", "list");

    assertTrue(output().contains("no 'every row' query"),
        "a short list that reads like a complete one is worse than a refusal: the registry has no"
            + " all-rows query, and an operator has to know that before they trust the output");
  }

  @Test
  @DisplayName("a bare list with somebody online still says the offline players' copies are missing")
  void aBareListSaysItIsOnlyTheConnectedPlayers() {
    fixture.player("Bob");
    fixture.messages.sent.clear();

    run("admin", "backup", "list");

    String output = output();
    assertTrue(output.contains("ONLY the connected players"),
        "the header alone reads like every backup on the server; an operator acting on that during an"
            + " incident acts on a list that silently omits everybody logged off: " + output);
    assertTrue(output.contains("no 'every row' query"),
        "and they have to be told WHY it is partial, or they will assume it is a bug and retry");
  }

  @Test
  @DisplayName("an id that is neither an experience nor a connected player is refused clearly")
  void anUnknownTargetIsRefused() {
    run("admin", "backup", "list", "not-a-thing");

    assertTrue(output().contains("no online") || output().contains("No experience"));
  }

  @Test
  @DisplayName("info prints what the row says, and never a run statistic decoded from challenge_state")
  void infoPrintsTheIdentityCard() {
    run("admin", "backup", "info", copy.id());

    String output = output();
    assertTrue(output.contains("is a copy: <white>yes"),
        "identity first: the two rows are byte-identical apart from this");
    assertTrue(output.contains(source.id()), "and it names the world it is a copy OF");
    assertTrue(output.contains("taken:"));
    assertTrue(output.contains("run statistics are NOT shown"),
        "experiences.challenge_state LAGS the state.yml inside the folder on the live server -- a"
            + " stale day or death count on output whose whole job is telling two identical worlds"
            + " apart is worse than no number, and the operator has to be told which they got");
    assertFalse(output.contains("days="), "no decoded day count");
    assertFalse(output.contains("deaths="), "no decoded death count");
  }

  @Test
  @DisplayName("delete refuses a row that is not a copy: the same call would drop a live world")
  void deleteRefusesALiveWorld() {
    run("admin", "backup", "delete", source.id());

    assertTrue(output().contains("is not a copy"),
        "an operator reaching for 'backup delete' is not reaching for 'delete the world somebody"
            + " plays', and ExperienceService.delete cannot tell the difference");
  }

  @Test
  @DisplayName("restore on a live world's id says it is not a copy, not that its source was deleted")
  void restoreRefusesARowThatIsNotACopy() {
    run("admin", "backup", "restore", source.id());

    String output = output();
    assertTrue(output.contains("is not a copy"),
        "the service answers GONE for a row that is not a copy, and GONE renders as 'the world this"
            + " is a copy OF has been deleted' -- on a live world's id that reads like data loss that"
            + " never happened: " + output);
    assertFalse(output.contains("Restoring"),
        "and it must be refused BEFORE routing, or the untrue answer still arrives");
  }

  @Test
  @DisplayName("refresh on a live world's id says it is not a copy, not that its source was deleted")
  void refreshRefusesARowThatIsNotACopy() {
    run("admin", "backup", "refresh", source.id());

    String output = output();
    assertTrue(output.contains("is not a copy"), output);
    assertFalse(output.contains("Re-taking"),
        "same GONE trap as restore, and the same fix: never route a row that is not a copy");
  }

  @Test
  @DisplayName("create on a copy's id says a copy of a copy is not taken, not that the cap is full")
  void createRefusesACopy() {
    run("admin", "backup", "create", copy.id());

    String output = output();
    assertTrue(output.contains("already a copy"),
        "the service refuses a copy with LIMIT_REACHED, which reads 'the cap is already reached."
            + " Delete one first.' -- that sends the operator hunting for a copy to delete that may"
            + " not exist, over a limit that was never hit: " + output);
    assertFalse(output.contains("Copying"), "refused before routing, so the wrong answer never comes");
  }

  @Test
  @DisplayName("every verb parses and routes, and none of them throws on a console")
  void everyVerbRoutes() {
    assertDoesNotThrow(() -> run("admin", "backup", "create", source.id()));
    assertTrue(output().contains("Copying"), output());

    fixture.messages.sent.clear();
    assertDoesNotThrow(() -> run("admin", "backup", "restore", copy.id()));
    assertTrue(output().contains("Restoring"), output());
    assertTrue(output().contains("KEPT as a copy"),
        "the one sentence that stops an operator hesitating: a restore throws nothing away");

    fixture.messages.sent.clear();
    assertDoesNotThrow(() -> run("admin", "backup", "refresh", copy.id()));
    assertTrue(output().contains("FULL re-copy"),
        "calling a refresh incremental would be a lie on the console as much as on a tile");

    fixture.messages.sent.clear();
    assertDoesNotThrow(() -> run("admin", "backup", "duplicate", copy.id()));
    assertTrue(output().contains("world slots"),
        "duplicate is the one verb that spends something the owner can run out of");

    fixture.messages.sent.clear();
    assertDoesNotThrow(() -> run("admin", "backup", "pending"));
    assertFalse(output().isBlank());
  }

  @Test
  @DisplayName("a verb with no id prints its own usage rather than a stack trace")
  void aVerbWithNoIdPrintsUsage() {
    for (String verb : List.of("info", "create", "restore", "refresh", "duplicate", "delete")) {
      fixture.messages.sent.clear();
      assertDoesNotThrow(() -> run("admin", "backup", verb));
      assertTrue(output().contains("/sx admin backup " + verb), verb + ": " + output());
    }
  }

  @Test
  @DisplayName("an unknown verb and a bare 'backup' both print the usage line")
  void unknownVerbsAreRefused() {
    run("admin", "backup");
    assertTrue(output().contains("/sx admin backup <list"));

    fixture.messages.sent.clear();
    run("admin", "backup", "obliterate", copy.id());
    assertTrue(output().contains("/sx admin backup <list"),
        "an unrecognised verb must never fall through to one that does something");
  }

  @Test
  @DisplayName("an id that names nothing is refused before anything is routed")
  void anUnknownIdIsRefused() {
    run("admin", "backup", "restore", "no-such-row");

    assertTrue(output().contains("No experience"));
    assertFalse(output().contains("Restoring"),
        "the row is resolved BEFORE the verb runs, so a typo never reaches the router");
  }

  @Test
  @DisplayName("backup and its verbs are offered in the completions, so an operator can find them")
  void isSuggested() {
    assertTrue(fixture.commands.suggest(console(), new String[]{"admin", "back"}).contains("backup"),
        "an operator who cannot discover a command does not have it");
    List<String> verbs = fixture.commands.suggest(console(), new String[]{"admin", "backup", ""});
    assertTrue(verbs.containsAll(List.of("list", "info", "create", "restore", "refresh",
        "duplicate", "delete", "pending")), verbs.toString());
  }

  @Test
  @DisplayName("only list completes player names: the id verbs would offer values that cannot work")
  void playerNamesAreOfferedOnlyToList() {
    fixture.player("Bob");

    assertTrue(fixture.commands.suggest(console(), new String[]{"admin", "backup", "list", ""})
        .contains("Bob"), "a connected player IS a valid list target");
    for (String verb : List.of("info", "create", "restore", "refresh", "duplicate", "delete")) {
      List<String> offered =
          fixture.commands.suggest(console(), new String[]{"admin", "backup", verb, ""});
      assertFalse(offered.contains("Bob"),
          verb + " takes an experience id, so completing a player name offers an argument that is"
              + " refused every single time: " + offered);
    }
  }
}
