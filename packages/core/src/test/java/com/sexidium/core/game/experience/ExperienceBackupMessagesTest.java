package com.sexidium.core.game.experience;

import com.sexidium.core.i18n.MessageKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A key that exists in one catalog and not the other is invisible until a Brazilian player hits it.
 *
 * <p>{@code MessageService} falls back to English for anything {@code pt.properties} does not answer,
 * which is the right runtime behaviour and the reason nothing else fails when a key is added to only
 * one file: the server starts, the message renders, and the only symptom is a line of English in the
 * middle of a Portuguese sentence. {@code MessageKeyTest} guards that paths are unique and non-blank —
 * neither of which says a word about the catalogs — so this is the only place the parity is enforced.</p>
 */
class ExperienceBackupMessagesTest {

  private static final String[] BACKUP_KEYS = {
      "experience.backup.working", "experience.backup.done", "experience.backup.queued",
      "experience.backup.busy", "experience.backup.limit", "experience.backup.failed",
      "experience.backup.not-owner"};

  private static Properties catalog(String language) {
    Properties properties = new Properties();
    try (InputStream stream =
        ExperienceBackupMessagesTest.class.getResourceAsStream("/lang/" + language + ".properties")) {
      assertTrue(stream != null, "lang/" + language + ".properties is not on the classpath");
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException unreadable) {
      throw new AssertionError("Could not read lang/" + language + ".properties", unreadable);
    }
    return properties;
  }

  @Test
  @DisplayName("every backup message exists, and says something, in BOTH catalogs")
  void everyBackupMessageIsTranslated() {
    Properties english = catalog("en");
    Properties portuguese = catalog("pt");
    for (String key : BACKUP_KEYS) {
      assertTrue(english.containsKey(key), "lang/en.properties is missing " + key);
      assertTrue(portuguese.containsKey(key), "lang/pt.properties is missing " + key);
      assertFalse(english.getProperty(key).isBlank(), key + " is blank in English");
      assertFalse(portuguese.getProperty(key).isBlank(), key + " is blank in Portuguese");
      assertFalse(english.getProperty(key).equals(portuguese.getProperty(key)),
          key + " is byte-identical in both catalogs, which means it was copied and never translated");
    }
  }

  /**
   * The two halves of a refused delete. They are separate keys because they ask for opposite things:
   * BUSY clears itself once whoever is inside walks out, while UNPLACED is stable until the world is
   * entered once and gets a placement row — so the single sentence these replaced told half of every
   * refusal to wait for something that was never going to happen.
   */
  private static final String[] DELETE_REFUSAL_KEYS = {
      "experience.delete.refused", "experience.delete.busy", "experience.delete.unplaced"};

  @Test
  @DisplayName("both reasons a delete is refused exist, and say different things, in BOTH catalogs")
  void everyDeleteRefusalIsTranslated() {
    Properties english = catalog("en");
    Properties portuguese = catalog("pt");
    for (String key : DELETE_REFUSAL_KEYS) {
      assertTrue(english.containsKey(key), "lang/en.properties is missing " + key);
      assertTrue(portuguese.containsKey(key), "lang/pt.properties is missing " + key);
      assertFalse(english.getProperty(key).isBlank(), key + " is blank in English");
      assertFalse(portuguese.getProperty(key).isBlank(), key + " is blank in Portuguese");
      assertFalse(english.getProperty(key).equals(portuguese.getProperty(key)),
          key + " is byte-identical in both catalogs, which means it was copied and never translated");
    }
    assertFalse(english.getProperty("experience.delete.refused").contains("inside"),
        "the line every refusal prints must be true of every refusal: nobody is inside a world no node"
            + " is even holding");
    assertNotEquals(english.getProperty("experience.delete.busy"),
        english.getProperty("experience.delete.unplaced"),
        "one sentence for two opposite instructions is the bug these keys exist to fix");
  }

  @Test
  @DisplayName("the message that names the cap carries the placeholder that fills it in")
  void theCapMessageCarriesItsPlaceholder() {
    // The number comes from config, so the sentence cannot state it -- it has to be substituted, and a
    // catalog that dropped the placeholder would tell the player the cap is "<max>".
    assertTrue(catalog("en").getProperty("experience.backup.limit").contains("<max>"));
    assertTrue(catalog("pt").getProperty("experience.backup.limit").contains("<max>"));
  }

  @Test
  @DisplayName("EVERY declared key exists in both catalogs — the convention nothing else enforces")
  void bothCatalogsCoverEveryKey() {
    Properties english = catalog("en");
    Properties portuguese = catalog("pt");
    List<String> missingEnglish = new ArrayList<>();
    List<String> missingPortuguese = new ArrayList<>();
    for (MessageKey key : MessageKey.values()) {
      if (!english.containsKey(key.path())) {
        missingEnglish.add(key.path());
      }
      if (!portuguese.containsKey(key.path())) {
        missingPortuguese.add(key.path());
      }
    }
    assertEquals(List.of(), missingEnglish, "keys with no English text");
    assertEquals(List.of(), missingPortuguese, "keys with no Portuguese text");
    assertEquals(new TreeSet<>(english.stringPropertyNames()),
        new TreeSet<>(portuguese.stringPropertyNames()),
        "the two catalogs have drifted: a key in one and not the other silently renders in English");
  }
}
