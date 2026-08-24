package com.sexidium.core.i18n;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the race sidebar templates against regressing to a form that silently drops the values
 * {@code RaceGame.updatePanel} supplies — the bug where the board showed only " (N pts)" with no item
 * name. Every placeholder the game passes must appear in the template, in every shipped language.
 */
class RaceBoardTemplateTest {

  private Properties load(String language) throws Exception {
    Properties properties = new Properties();
    try (InputStream stream = getClass().getClassLoader().getResourceAsStream("lang/" + language + ".properties")) {
      assertNotNull(stream, "missing lang catalog: " + language);
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    }
    return properties;
  }

  @Test
  void itemPointsTemplate_containsItemAmountAndPoints() throws Exception {
    for (String language : new String[]{"en", "pt"}) {
      String template = load(language).getProperty("race.item-points");
      assertNotNull(template, "race.item-points missing in " + language);
      assertTrue(template.contains("<item>"), "race.item-points must include <item> in " + language);
      assertTrue(template.contains("<amount>"), "race.item-points must include <amount> in " + language);
      assertTrue(template.contains("<points>"), "race.item-points must include <points> in " + language);
    }
  }

  @Test
  void scoreboardFoundTemplate_containsPlayerProgressAndPoints() throws Exception {
    for (String language : new String[]{"en", "pt"}) {
      String template = load(language).getProperty("race.scoreboard.found");
      assertNotNull(template, "race.scoreboard.found missing in " + language);
      assertTrue(template.contains("<player>"), "race.scoreboard.found must include <player> in " + language);
      assertTrue(template.contains("<found>"), "race.scoreboard.found must include <found> in " + language);
      assertTrue(template.contains("<total>"), "race.scoreboard.found must include <total> in " + language);
      assertTrue(template.contains("<points>"), "race.scoreboard.found must include <points> in " + language);
    }
  }
}
