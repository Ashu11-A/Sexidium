package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the canonical world name/key codec that every layer now shares. */
class WorldNamingTest {
  private final WorldNaming naming = new WorldNaming(new DefaultConfig());

  @Test
  void experienceRuntimeName_prefixesNamespace() {
    assertEquals("experiences/ashu11a/diamond_hunt_ab12cd34",
        naming.experienceRuntimeName("ashu11a/diamond_hunt_ab12cd34"));
  }

  @Test
  void experienceKey_roundTripsFromCanonicalName() {
    String key = "ashu11a/diamond_hunt_ab12cd34";
    assertEquals(key, naming.experienceKeyOf(naming.experienceRuntimeName(key)));
  }

  @Test
  void experienceKey_recoversFromDimensionId() {
    assertEquals("ashu11a/diamond_hunt_ab12cd34",
        WorldNaming.experienceKeyOf("experiences:ashu11a/diamond_hunt_ab12cd34", "experiences"));
  }

  @Test
  void experienceKey_recoversFromLegacySlashPath() {
    assertEquals("ashu11a/diamond_hunt_ab12cd34",
        WorldNaming.experienceKeyOf("worlds/experience/ashu11a/diamond_hunt_ab12cd34", "experience"));
  }

  @Test
  void experienceKey_fallsBackToLastSegment() {
    assertEquals("diamond_hunt_ab12cd34",
        WorldNaming.experienceKeyOf("sexidium:diamond_hunt_ab12cd34", "experiences"));
  }

  @Test
  void classifiesLobbyInEveryForm() {
    assertTrue(naming.isLobby("lobby"));
    assertTrue(naming.isLobby("minecraft:lobby"));
    assertTrue(naming.isLobby("minecraft/lobby"));
    assertFalse(naming.isLobby("experiences/ashu11a/map_x"));
  }

  @Test
  void classifiesExperienceAndTemp() {
    assertTrue(naming.isExperience("experiences/ashu11a/diamond_hunt_x"));
    assertTrue(naming.isExperience("experiences:ashu11a/diamond_hunt_x"));
    assertTrue(naming.isExperience("worlds/experience/ashu11a/map_x"));
    assertFalse(naming.isExperience("sexidium_temp/sexidium_temp_abc_0"));

    assertTrue(naming.isTemp("sexidium_temp/sexidium_temp_abc_0"));
    assertTrue(naming.isTemp("sexidium:sexidium_temp/sexidium_temp_abc_0"));
    assertFalse(naming.isTemp("experiences/ashu11a/map_x"));
  }

  @Test
  void lastSegmentAndStripNamespace() {
    assertEquals("map_x", WorldNaming.lastSegment("experiences:ashu11a/map_x"));
    assertEquals("lobby", WorldNaming.lastSegment("minecraft:lobby"));
    assertEquals("experiences/ashu11a/map_x", WorldNaming.stripNamespace("sexidium:experiences/ashu11a/map_x"));
  }

  @Test
  void sameWorldComparesLastSegment() {
    assertTrue(WorldNaming.sameWorld("experiences/ashu11a/map_x", "experiences:ashu11a/map_x"));
    assertFalse(WorldNaming.sameWorld("experiences/a/map_x", "experiences/a/map_y"));
  }

  @Test
  void sameWorld_matchesCanonicalAgainstFlattenedPlatformLabel() {
    // The leased temp world's canonical name ("<ns>/<short>") vs the live Paper world label
    // ("<ns>_<short>") must compare equal, or a player is ejected from their own match world on entry.
    assertTrue(WorldNaming.sameWorld(
        "sexidium_temp/sexidium_temp_mqayywv9_0", "sexidium_temp_sexidium_temp_mqayywv9_0"));
    assertTrue(WorldNaming.sameWorld(
        "experiences/ashu11a/diamond_hunt_ab12", "experiences_ashu11a_diamond_hunt_ab12"));
    // distinct temp worlds still differ
    assertFalse(WorldNaming.sameWorld(
        "sexidium_temp_sexidium_temp_aaa_0", "sexidium_temp_sexidium_temp_bbb_1"));
  }

  @Test
  void sanitizeSegment_isCrossPlatformSafe() {
    assertEquals("diamond_hunt", WorldNaming.sanitizeSegment("Diamond Hunt!", "map"));
    assertEquals("map", WorldNaming.sanitizeSegment("***", "map"));
    assertEquals("a.b-c_d", WorldNaming.sanitizeSegment("a.b-c_d", "map"));
  }

  // ----- regeneration generations ---------------------------------------------------------------

  /**
   * The invariant the whole suffix scheme rests on: an experience key always ends in the experience's
   * 8-character HEXADECIMAL id, and hex contains no {@code r}. So a trailing {@code _r<digits>} can only
   * ever be a marker this class wrote, and a brand-new experience always reads as generation 0.
   */
  @Test
  void aFreshExperienceKeyIsGenerationZero() {
    String key = "ashu11a/diamond_hunt_ab12cd34";

    assertEquals(0, WorldNaming.generationOf(key));
    assertEquals(key, WorldNaming.baseExperienceKey(key));
  }

  @Test
  void generationsRoundTripThroughTheKey() {
    String base = "ashu11a/diamond_hunt_ab12cd34";

    String third = WorldNaming.experienceKeyForGeneration(base, 3);

    assertEquals(base + "_r3", third);
    assertEquals(3, WorldNaming.generationOf(third));
    assertEquals(base, WorldNaming.baseExperienceKey(third));
    // Counting on from an already-suffixed key must replace the marker, never stack a second one.
    assertEquals(base + "_r4", WorldNaming.experienceKeyForGeneration(third, 4));
  }

  @Test
  void generationZeroOrLessIsTheBareBase() {
    String base = "ashu11a/diamond_hunt_ab12cd34";

    assertEquals(base, WorldNaming.experienceKeyForGeneration(base + "_r7", 0));
    assertEquals(base, WorldNaming.experienceKeyForGeneration(base + "_r7", -1));
  }

  /** A key that merely happens to contain "_r" is not a generation marker. */
  @Test
  void onlyATrailingUnderscoreRPlusDigitsCounts() {
    assertEquals(0, WorldNaming.generationOf("ashu11a/red_rock_ab12cd34"));
    assertEquals(0, WorldNaming.generationOf("ashu11a/map_rocket"));
    assertEquals("ashu11a/map_r2x", WorldNaming.baseExperienceKey("ashu11a/map_r2x"));
  }

  // ----- experience membership --------------------------------------------------------------------

  @Test
  void everyDimensionOfAnExperienceBelongsToIt() {
    String overworld = "experiences/ashu11a/diamond_hunt_ab12cd34";

    assertTrue(WorldNaming.belongsToExperience(overworld, overworld));
    assertTrue(WorldNaming.belongsToExperience(overworld + "_nether", overworld));
    assertTrue(WorldNaming.belongsToExperience(overworld + "_end", overworld));
    // The platform reports a flattened live label; it must still match the canonical slash path.
    assertTrue(WorldNaming.belongsToExperience("experiences_ashu11a_diamond_hunt_ab12cd34", overworld));
  }

  @Test
  void anotherWorldNeverBelongsToThisExperience() {
    String overworld = "experiences/ashu11a/diamond_hunt_ab12cd34";

    assertFalse(WorldNaming.belongsToExperience("lobby", overworld));
    assertFalse(WorldNaming.belongsToExperience("experiences/ashu11a/other_map_cd34ef56", overworld));
    // A regeneration is a DIFFERENT world; the teardown depends on telling them apart.
    assertFalse(WorldNaming.belongsToExperience(overworld + "_r1", overworld));
    assertFalse(WorldNaming.belongsToExperience(null, overworld));
    assertFalse(WorldNaming.belongsToExperience(overworld, "  "));
  }

  /** A ConfigurationAdapter that always returns the caller's default — so WorldNaming uses its defaults. */
  private static final class DefaultConfig implements ConfigurationAdapter {
    @Override public boolean getBoolean(String path, boolean defaultValue) {
      return defaultValue;
    }

    @Override public int getInt(String path, int defaultValue) {
      return defaultValue;
    }

    @Override public long getLong(String path, long defaultValue) {
      return defaultValue;
    }

    @Override public double getDouble(String path, double defaultValue) {
      return defaultValue;
    }

    @Override public String getString(String path, String defaultValue) {
      return defaultValue;
    }

    @Override public List<String> getStringList(String path) {
      return List.of();
    }

    @Override public List<Map<String, Object>> getMapList(String path) {
      return List.of();
    }

    @Override public Set<String> keys(String path) {
      return Set.of();
    }

    @Override public Object get(String path) {
      return null;
    }

    @Override public boolean contains(String path) {
      return false;
    }

    @Override public void set(String path, Object value) {
    }

    @Override public void reload() {
    }

    @Override public void save() {
    }
  }
}
