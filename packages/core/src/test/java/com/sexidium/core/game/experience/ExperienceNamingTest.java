package com.sexidium.core.game.experience;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sanitization of experience world-name segments and recovery of the stable key from a runtime name. */
class ExperienceNamingTest {

  @Test
  void sanitizeSegment_lowercasesAndCollapsesUnsafeRuns() {
    assertEquals("ashu11a", ExperienceManager.sanitizeSegment("Ashu11a", "player"));
    assertEquals("diamond_hunt", ExperienceManager.sanitizeSegment("Diamond Hunt!", "map"));
    assertEquals("a_b", ExperienceManager.sanitizeSegment("a   b", "map"));   // run collapses to one _
    assertEquals("keep-_x", ExperienceManager.sanitizeSegment("keep-_x", "y")); // '-' and '_' preserved
  }

  @Test
  void sanitizeSegment_fallsBackWhenNothingUsable() {
    assertEquals("player", ExperienceManager.sanitizeSegment("", "player"));
    assertEquals("player", ExperienceManager.sanitizeSegment("   ", "player"));
    assertEquals("map", ExperienceManager.sanitizeSegment("***", "map"));
    assertEquals("map", ExperienceManager.sanitizeSegment(null, "map"));
  }

  @Test
  void experienceKey_stripsPaperSubdirPrefix() {
    assertEquals("ashu11a/diamond_hunt_ab12",
        ExperienceGame.experienceKey("worlds/experience/ashu11a/diamond_hunt_ab12", "experience"));
  }

  @Test
  void experienceKey_stripsNeoForgeNamespace() {
    assertEquals("diamond_hunt_ab12",
        ExperienceGame.experienceKey("sexidium:diamond_hunt_ab12", "experience"));
  }

  @Test
  void experienceKey_fallsBackToLastSegmentForNonExperienceWorlds() {
    assertEquals("sexidium_temp_3",
        ExperienceGame.experienceKey("worlds/temp/sexidium_temp_3", "experience"));
    assertEquals("lobby", ExperienceGame.experienceKey("lobby", "experience"));
  }
}
