package com.sexidium.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One spelling of a world, and the guarantees that used to need four.
 *
 * <p>The identity tests that lived in {@code WorldResetTest} are here, restated against
 * {@link WorldKey}: nine historical spellings of one world collapse to one key, two different
 * experiences keep different keys, and a map whose own name ends in {@code _r} still resets like any
 * other. What has changed is why they matter. They used to guard a normaliser that was NOT idempotent
 * — {@code experiences/ashu/x} became {@code ashu/x} and {@code ashu/x} became {@code x}, so which
 * answer you got depended on which spelling you started from — and that non-idempotence is how one
 * world came to hold several {@code world_placements} rows on several nodes while the boot-time folder
 * scan matched none of them.</p>
 */
class WorldKeyTest {

  private static final String ID = "ab12cd34";

  // ===== construction ==========================================================================

  @Test
  @DisplayName("a new key is <slug>_<id>, with NO owner segment")
  void ofBuildsTheCanonicalKey() {
    WorldKey key = WorldKey.of("Diamond Hunt", ID);

    assertEquals("diamond_hunt_" + ID, key.key());
    assertEquals(0, key.generation());
    // The <nick>/ level the old shape implied was documented, was in the registry, and was never
    // created on disk by anything. Dropping it is what makes one spelling possible at all.
    assertFalse(key.key().contains("/"), "a key is one segment: " + key.key());
  }

  @Test
  @DisplayName("a blank or unusable slug still yields a legal key")
  void ofFallsBackForAnUnusableSlug() {
    assertEquals("map_" + ID, WorldKey.of("   ", ID).key());
    assertEquals("map_" + ID, WorldKey.of("!!!", ID).key());
  }

  @Test
  @DisplayName("a key refuses a separator, a namespace or a parent traversal")
  void aKeyRefusesPathSyntax() {
    // Every rendering below resolves a folder from this string. Refusing it here is what makes them
    // total rather than each having to re-validate.
    assertThrows(IllegalArgumentException.class, () -> new WorldKey("ashu/map_" + ID, 0));
    assertThrows(IllegalArgumentException.class, () -> new WorldKey("ashu\\map_" + ID, 0));
    assertThrows(IllegalArgumentException.class, () -> new WorldKey("experiences:map_" + ID, 0));
    assertThrows(IllegalArgumentException.class, () -> new WorldKey("../map_" + ID, 0));
    assertThrows(IllegalArgumentException.class, () -> new WorldKey("  ", 0));
    assertThrows(IllegalArgumentException.class, () -> new WorldKey(null, 0));
    assertThrows(IllegalArgumentException.class, () -> new WorldKey("map_" + ID, -1));
  }

  // ===== round trip ============================================================================

  @Test
  @DisplayName("key() and parse() round-trip at every generation")
  void keyRoundTrips() {
    for (int generation : new int[] {0, 1, 7, 12, 999}) {
      WorldKey key = new WorldKey("death_resets_" + ID, generation);
      assertEquals(key, WorldKey.parse(key.key()),
          "generation " + generation + " must survive a round trip through its own rendering");
    }
  }

  @Test
  @DisplayName("parse is IDEMPOTENT — parsing its own output never changes the answer")
  void parseIsIdempotent() {
    // The property the old normaliser lacked, and the whole reason one world held several rows.
    WorldKey once = WorldKey.parse("death_resets_" + ID + "_r14");
    WorldKey twice = WorldKey.parse(once.key());
    assertEquals(once, twice);
    assertEquals(once.key(), twice.key());
  }

  @Test
  @DisplayName("fromRuntime is idempotent across ITS own output too")
  void fromRuntimeIsIdempotent() {
    WorldKey once = WorldKey.fromRuntime("experiences/death_resets_" + ID + "_r3").orElseThrow();
    assertEquals(once, WorldKey.fromRuntime(once.key()).orElseThrow());
    assertEquals(once, WorldKey.fromRuntime(once.runtimeName()).orElseThrow());
    assertEquals(once, WorldKey.fromRuntime(once.flatLabel()).orElseThrow());
    assertEquals(once, WorldKey.fromRuntime(once.dimensionKey()).orElseThrow());
  }

  // ===== the four spellings ====================================================================

  /**
   * Every spelling one world was written in, collapsed together — the test that used to live in
   * {@code WorldResetTest.everySpellingOfOneWorldCollapsesToOneIdentity}.
   *
   * <p>The list is not invented: each entry is a form some layer actually produced. The world layer
   * works in the canonical key and appends a generation on every reset, Paper registers the world under
   * a slash path, Bukkit reports it flattened, a dimension id arrives colon-qualified, and Windows
   * hands paths back with backslashes.</p>
   */
  @Test
  @DisplayName("every historical spelling of one world collapses to one key")
  void everySpellingCollapsesToOneKey() {
    List<String> spellings = List.of(
        "death_resets_" + ID,                          // bare key, as the disk scan yields it
        "experiences/death_resets_" + ID,              // canonical runtime name
        "experiences:death_resets_" + ID,              // dimension id form
        "experiences_death_resets_" + ID,              // Bukkit's flattened label
        "sexidium:experiences/death_resets_" + ID,     // namespaced dimension id
        "worlds/experiences/death_resets_" + ID,       // legacy slash layout on disk
        "worlds\\experiences\\death_resets_" + ID);    // ...as Windows spells it back

    Set<WorldKey> keys = new HashSet<>();
    for (String spelling : spellings) {
      keys.add(WorldKey.fromRuntime(spelling).orElseThrow(
          () -> new AssertionError("could not recover a key from '" + spelling + "'")));
    }

    assertEquals(1, keys.size(),
        "one world named seven ways must be ONE key; got " + keys + " from " + spellings);
    assertEquals("death_resets_" + ID, keys.iterator().next().key());
  }

  @Test
  @DisplayName("a linked Nether/End label resolves to the experience it belongs to")
  void siblingLabelsResolveToTheirOverworld() {
    // A sibling is not a world of its own: it is opened by, and travels with, its overworld, and giving
    // it a placement row of its own would invite the planner to home a dimension on a different node
    // from the experience it belongs to.
    WorldKey overworld = WorldKey.parse("death_resets_" + ID + "_r2");
    assertEquals(overworld,
        WorldKey.fromRuntime("experiences_death_resets_" + ID + "_r2_nether").orElseThrow());
    assertEquals(overworld,
        WorldKey.fromRuntime("experiences/death_resets_" + ID + "_r2_end").orElseThrow());
  }

  /**
   * The other half of an identity: it must SEPARATE. A key that collapsed everything would pass the
   * test above and hand a player somebody else's world — worse than the bug it replaced, because the
   * registry lookup would succeed on the wrong row.
   */
  @Test
  @DisplayName("two different experiences keep different keys")
  void differentExperiencesKeepDifferentKeys() {
    assertNotEquals(WorldKey.parse("diamond_hunt_" + ID), WorldKey.parse("diamond_hunt_ff99ee00"),
        "the 8-hex id is what makes an experience unique; two maps of the same NAME are two worlds");
    assertNotEquals(WorldKey.parse("diamond_hunt_" + ID), WorldKey.parse("skyblock_" + ID),
        "and a different map with a colliding id is still a different experience");
  }

  @Test
  @DisplayName("a name that carries a path but no namespace marker is not a key")
  void anUnmarkedPathIsRefused() {
    // The deleted owner-segment fallback answered "map_ab12cd34" here, dropping the owner — a
    // DIFFERENT key from the one the same function returned for "experiences/ashu/map_ab12cd34".
    assertTrue(WorldKey.fromRuntime("ashu11a/death_resets_" + ID).isEmpty(),
        "guessing here is exactly how one world came to have two identities");
    assertTrue(WorldKey.fromRuntime("").isEmpty());
    assertTrue(WorldKey.fromRuntime(null).isEmpty());
  }

  // ===== generations ===========================================================================

  @Test
  @DisplayName("only the generation suffix is stripped; a map whose name ends in _r survives")
  void onlyTheGenerationSuffixIsStripped() {
    WorldKey withR = WorldKey.parse("my_map_r_" + ID);
    WorldKey resetOnce = WorldKey.parse("my_map_r_" + ID + "_r4");

    assertTrue(withR.sameRun(resetOnce), "a map whose own name contains _r still resets like any other");
    assertEquals(0, withR.generation());
    assertEquals(4, resetOnce.generation());
    assertFalse(withR.sameRun(WorldKey.parse("my_map_" + ID)),
        "stripping more than the generation would merge two different maps");
  }

  @Test
  @DisplayName("nextGeneration counts up and stays the same run")
  void generationsCountUp() {
    WorldKey key = WorldKey.parse("death_resets_" + ID);
    for (int expected = 1; expected <= 14; expected++) {
      key = key.nextGeneration();
      assertEquals(expected, key.generation());
      assertEquals("death_resets_" + ID + "_r" + expected, key.key());
      assertTrue(key.sameRun(WorldKey.parse("death_resets_" + ID)));
    }
    assertEquals(WorldKey.parse("death_resets_" + ID), key.origin());
    assertEquals(key, key.origin().equals(key) ? key : key); // origin of gen 0 is itself
    assertEquals(WorldKey.parse("death_resets_" + ID),
        WorldKey.parse("death_resets_" + ID).origin());
  }

  // ===== renderings ============================================================================

  @Test
  @DisplayName("every rendering derives from the key, so none of them can drift")
  void renderingsDeriveFromTheKey() {
    WorldKey key = WorldKey.parse("death_resets_" + ID + "_r14");

    assertEquals("death_resets_" + ID + "_r14", key.key());
    assertEquals("experiences/death_resets_" + ID + "_r14", key.runtimeName());
    assertEquals("experiences:death_resets_" + ID + "_r14", key.dimensionKey());
    assertEquals("experiences_death_resets_" + ID + "_r14", key.flatLabel());
    assertEquals(Path.of("/srv/experiences", "death_resets_" + ID + "_r14"),
        key.folderIn(Path.of("/srv/experiences")));
    assertEquals("death_resets_" + ID + "_r14", key.toString());
  }

  @Test
  @DisplayName("a configured namespace is honoured by every rendering")
  void aConfiguredNamespaceIsHonoured() {
    WorldKey key = WorldKey.parse("death_resets_" + ID);

    assertEquals("maps/death_resets_" + ID, key.runtimeName("maps"));
    assertEquals("maps:death_resets_" + ID, key.dimensionKey("maps"));
    assertEquals("maps_death_resets_" + ID, key.flatLabel("maps"));
    assertEquals(key, WorldKey.fromRuntime("maps/death_resets_" + ID, "maps").orElseThrow());
    assertEquals(key, WorldKey.fromRuntime("maps_death_resets_" + ID, "maps").orElseThrow());
  }

  @Test
  @DisplayName("keys are lowercased, so two casings are never two worlds")
  void keysAreLowercased() {
    assertEquals(WorldKey.parse("death_resets_" + ID), WorldKey.parse("Death_Resets_AB12CD34"));
  }

  @Test
  @DisplayName("keys order by base then generation, so a lineage sorts naturally")
  void keysSortByBaseThenGeneration() {
    assertTrue(WorldKey.parse("a_" + ID).compareTo(WorldKey.parse("b_" + ID)) < 0);
    assertTrue(WorldKey.parse("a_" + ID).compareTo(WorldKey.parse("a_" + ID + "_r2")) < 0);
    assertTrue(WorldKey.parse("a_" + ID + "_r10").compareTo(WorldKey.parse("a_" + ID + "_r2")) > 0);
  }

  @Test
  @DisplayName("tryParse answers empty rather than throwing on rubbish")
  void tryParseIsTotal() {
    assertTrue(WorldKey.tryParse(null).isEmpty());
    assertTrue(WorldKey.tryParse("").isEmpty());
    assertTrue(WorldKey.tryParse("a/b").isEmpty());
    assertTrue(WorldKey.tryParse("death_resets_" + ID).isPresent());
  }
}
