package com.sexidium.core.command;

import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /sx experience rename <id> <name>} — the one entry point where an arbitrary display name is
 * TYPED — and the generated names that reach the same column without anybody typing anything.
 *
 * <p>The name does not stay a label: {@code WorldKey.of(displayName, id)} derives the world's own key
 * and every later backup's {@code world_key} from it, and that column is a {@code VARCHAR(191)} with a
 * unique index — a long enough name makes the INSERT fail with nothing on any screen to explain why.</p>
 *
 * <p>The typed name is not the only unbounded one, which is what this class used to claim. The name a
 * fresh experience is BORN with is generated from its challenge list, and twelve of the catalog's
 * twenty-seven twists joined with {@code " + "} already make 199 characters: the creation INSERT failed,
 * the player who ticked a dozen twists in the builder got no world and no message, and the rename
 * command they never reached was where the only cap lived. Both ends are capped now, at the same
 * number, from {@link ExperienceService#MAX_DISPLAY_NAME}.</p>
 */
class ExperienceRenameCommandTest {

  private Path tempDir;
  private Database database;
  private CommandTestFixture fixture;
  private ExperienceManager registry;
  private FakePlayer owner;
  private ExperienceManager.Experience world;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("sexidium-experience-rename-test");
    database = new Database(tempDir.resolve("sexidium.db").toFile());
    fixture = CommandTestFixture.create(tempDir, database, true);
    registry = fixture.core.experiences();
    owner = fixture.player("Alice");
    world = registry.create(owner.uniqueId(), "Alice", List.of("deathresets"), "Sky",
        System.currentTimeMillis());
    fixture.messages.sent.clear();
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

  private void rename(String... nameWords) {
    String[] args = new String[3 + nameWords.length];
    args[0] = "experience";
    args[1] = "rename";
    args[2] = world.id();
    System.arraycopy(nameWords, 0, args, 3, nameWords.length);
    fixture.commands.execute(owner, args);
  }

  private String output() {
    return String.join("\n", fixture.messages.sent);
  }

  @Test
  @DisplayName("a name of a sane length is accepted, spaces and all")
  void aNormalNameIsAccepted() {
    rename("My", "Very", "Own", "World");

    assertEquals("My Very Own World", registry.get(world.id()).displayName(),
        "the words after the id are joined back into one name; that has always been the point");
    assertTrue(output().contains("Renamed experience."));
  }

  @Test
  @DisplayName("a name longer than the cap is refused, with the length said out loud")
  void anOverlongNameIsRefused() {
    // Joined from several words, because that is how it arrives: everything after the id.
    rename("A".repeat(40), "B".repeat(40));

    assertEquals("Sky", registry.get(world.id()).displayName(),
        "the row must be untouched: this name would later become a backup's world_key, and a"
            + " VARCHAR(191) unique index is where it would fail instead — silently, on the copy");
    String output = output();
    assertTrue(output.contains("too long"), "the refusal has to say what is wrong: " + output);
    assertTrue(output.contains("48"), "and what the limit is, so the second attempt works: " + output);
  }

  @Test
  @DisplayName("the cap itself is not off by one: a name exactly at the limit still renames")
  void aNameExactlyAtTheCapIsAccepted() {
    String atTheLimit = "C".repeat(48);
    rename(atTheLimit);

    assertEquals(atTheLimit, registry.get(world.id()).displayName());
  }

  /** {@code keyText()} is {@code VARCHAR(191)} on every networked dialect (SqlDialect). */
  private static final int COLUMN = 191;

  /** The first {@code count} twists the builder's grid offers, in the order it offers them. */
  private static List<String> twists(int count) {
    List<String> ids = new ArrayList<>();
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.selectable()) {
      if (ids.size() == count) {
        break;
      }
      ids.add(entry.id());
    }
    return ids;
  }

  /** Everything a created row and its later suffixed copies put in a {@code VARCHAR(191)}. */
  private void assertFitsTheColumn(ExperienceManager.Experience created) {
    assertTrue(created.displayName().length() <= COLUMN,
        "display_name is " + created.displayName().length() + " characters");
    assertTrue(created.worldKey().length() <= COLUMN,
        "world_key is " + created.worldKey().length() + " characters");
    // A copy is born from the same name plus a suffix and gets its own world_key from it, so the cap
    // has to leave room for the longest of them.
    String displaced = created.displayName() + " (before restore)";
    assertTrue(WorldKey.of(displaced, created.id()).key().length() <= COLUMN,
        "a restore's displaced world would not fit: " + displaced);
  }

  @Test
  @DisplayName("twelve ticked twists still fit the column — 199 characters is what they used to make")
  void aDozenTwistsStillFits() {
    List<String> chosen = twists(12);
    String generated = ExperienceService.displayNameFor(chosen);

    assertTrue(generated.length() <= ExperienceService.MAX_DISPLAY_NAME,
        "the builder generated a " + generated.length() + "-character name: " + generated);
    ExperienceManager.Experience created = registry.create(owner.uniqueId(), "Alice", chosen,
        generated, System.currentTimeMillis());
    assertNotNull(created, "create() answers null when the INSERT fails, and the player sees nothing");
    assertFitsTheColumn(created);
  }

  @Test
  @DisplayName("every twist at once fits too — the whole catalog joined is 451 characters")
  void theWholeCatalogStillFits() {
    List<String> everything = twists(Integer.MAX_VALUE);
    assertTrue(everything.size() >= 12, "the catalog shrank; this test is about a long list");
    String generated = ExperienceService.displayNameFor(everything);

    assertTrue(generated.length() <= ExperienceService.MAX_DISPLAY_NAME,
        "the builder generated a " + generated.length() + "-character name: " + generated);
    ExperienceManager.Experience created = registry.create(owner.uniqueId(), "Alice", everything,
        generated, System.currentTimeMillis());
    assertNotNull(created);
    assertFitsTheColumn(created);
  }

  @Test
  @DisplayName("what is kept is readable: whole twist names, then a count of the rest")
  void theTruncationKeepsWholeNames() {
    String generated = ExperienceService.displayNameFor(twists(Integer.MAX_VALUE));

    assertTrue(generated.endsWith(" more"), "the tail says how many were dropped: " + generated);
    String first = ChallengeCatalog.displayNameFor(twists(1).get(0));
    assertTrue(generated.startsWith(first),
        "a name cut mid-word is shorter to write and unreadable in a list: " + generated);
  }

  @Test
  @DisplayName("a short list is left exactly as it was — nothing is abbreviated that fits")
  void aShortListIsUntouched() {
    assertEquals("Double Drops + Randomizer",
        ExperienceService.displayNameFor(List.of("doubledrops", "randomizer")));
    assertEquals("Experience", ExperienceService.displayNameFor(List.of()));
  }
}
