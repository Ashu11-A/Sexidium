package com.sexidium.core.game.experience;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives a world regeneration. The rule is load-bearing in both directions: carrying too little
 * loses the run's history at the first death, and carrying too much hands a fresh world state that
 * describes the one it replaced — a map challenge's "already built" flag being the example that leaves
 * everybody standing in empty space.
 */
class StateCarryTest {
  private static Map<String, String> state() {
    Map<String, String> values = new LinkedHashMap<>();
    values.put("deathresets.resets", "7");
    values.put("deathresets.daybaseline", "24000");
    values.put("deathresets.days", "2");
    values.put("stats.run.seconds.total", "11520");
    values.put("stats.run.seconds.player.a", "9300");
    values.put("stats.run.deaths.player.a", "4");
    values.put("stats.blocks.broken", "88123");
    values.put("skyblock.built", "true");
    return values;
  }

  @Test
  void exactKeysCarryOnlyThemselves() {
    Map<String, String> kept = StateCarry.select(state(),
        Set.of("deathresets.resets", "deathresets.daybaseline"));
    assertEquals(Set.of("deathresets.resets", "deathresets.daybaseline"), kept.keySet());
    assertEquals("7", kept.get("deathresets.resets"));
  }

  @Test
  void aPrefixCarriesEveryKeyBeneathIt() {
    Map<String, String> kept = StateCarry.select(state(), Set.of("stats.run.*"));
    assertEquals(Set.of("stats.run.seconds.total", "stats.run.seconds.player.a",
        "stats.run.deaths.player.a"), kept.keySet());
  }

  @Test
  void aPrefixDoesNotReachItsSiblings() {
    // The point of putting run-lifetime counters in their own namespace: "stats.run.*" must not drag
    // the world-scoped "stats.blocks.broken" across with it.
    Map<String, String> kept = StateCarry.select(state(), Set.of("stats.run.*"));
    assertFalse(kept.containsKey("stats.blocks.broken"));
    assertFalse(kept.containsKey("skyblock.built"));
  }

  @Test
  void exactAndPrefixEntriesCompose() {
    Map<String, String> kept = StateCarry.select(state(),
        Set.of("deathresets.resets", "deathresets.daybaseline", "stats.run.*"));
    assertEquals(5, kept.size());
    assertTrue(kept.containsKey("stats.run.deaths.player.a"));
    // The day mirror is NOT named, so a new world starts its day count from nothing.
    assertFalse(kept.containsKey("deathresets.days"));
  }

  @Test
  void anAbsentKeyIsSkippedRatherThanCarriedAsNull() {
    Map<String, String> kept = StateCarry.select(state(), Set.of("deathresets.resets", "nothing.here"));
    assertEquals(Set.of("deathresets.resets"), kept.keySet());
  }

  @Test
  void aBareWildcardCarriesNothing() {
    // "*" would carry everything, which is exactly the failure the allowlist exists to prevent — so it
    // is refused rather than honoured.
    assertTrue(StateCarry.select(state(), Set.of("*")).isEmpty());
  }

  @Test
  void emptyInputsAreEmptyOutputs() {
    assertTrue(StateCarry.select(state(), Set.of()).isEmpty());
    assertTrue(StateCarry.select(state(), null).isEmpty());
    assertTrue(StateCarry.select(Map.of(), Set.of("stats.run.*")).isEmpty());
    assertTrue(StateCarry.select(null, Set.of("stats.run.*")).isEmpty());
  }

  // ----- what the rebuild writes ----------------------------------------------------------------

  /**
   * A challenge that builds its island into the replacement world records that it did, and that record
   * has to survive without being named in anybody's allowlist.
   *
   * <p>It is not old-world state: it describes the world the run is about to move INTO. Filtering it
   * through the allowlist loses it, and the next start then builds the island a second time over the top
   * of whatever the players made of the first one.</p>
   */
  @Test
  void aMarkerWrittenWhileRebuildingIsCarriedWithoutBeingAllowlisted() {
    Map<String, String> before = Map.of("classicskyblock.built", "1");
    Map<String, String> after = new java.util.LinkedHashMap<>(before);
    after.put("randomlayers.frontier", "-4");        // seated into the new world
    after.put("randomlayers.layers", "0");

    Map<String, String> written = StateCarry.writtenDuringRebuild(before, after);

    assertEquals(Set.of("randomlayers.frontier", "randomlayers.layers"), written.keySet());
  }

  /** A value the rebuild changed is new-world state even where the key is not. */
  @Test
  void aChangedValueCountsAsWritten() {
    Map<String, String> written = StateCarry.writtenDuringRebuild(
        Map.of("randomlayers.frontier", "-40"), Map.of("randomlayers.frontier", "-4"));

    assertEquals("-4", written.get("randomlayers.frontier"));
  }

  /**
   * An untouched marker must NOT be carried — that is how a challenge asks for one to be cleared, and
   * Classic Skyblock's Nether markers depend on it: nothing rewrites them, so they are gone after the
   * swap and its timer rebuilds the mirror against the replacement world's own Nether.
   */
  @Test
  void anUntouchedMarkerIsLeftToTheAllowlistToDrop() {
    Map<String, String> unchanged = Map.of("classicskyblock.netherbuilt", "1");

    assertTrue(StateCarry.writtenDuringRebuild(unchanged, unchanged).isEmpty());
    // Re-writing the same value is indistinguishable from not touching it, and is read the same way.
    assertTrue(StateCarry.writtenDuringRebuild(
        Map.of("a", "1"), new java.util.LinkedHashMap<>(Map.of("a", "1"))).isEmpty());
  }

  /** A run with no state before the rebuild carries everything the rebuild wrote, and nothing else. */
  @Test
  void rebuildDeltasCopeWithEmptyAndNullInputs() {
    assertEquals(Set.of("randomskyblock.placed"),
        StateCarry.writtenDuringRebuild(Map.of(), Map.of("randomskyblock.placed", "1")).keySet());
    assertEquals(Set.of("randomskyblock.placed"),
        StateCarry.writtenDuringRebuild(null, Map.of("randomskyblock.placed", "1")).keySet());
    assertTrue(StateCarry.writtenDuringRebuild(state(), Map.of()).isEmpty());
    assertTrue(StateCarry.writtenDuringRebuild(state(), null).isEmpty());
  }
}
