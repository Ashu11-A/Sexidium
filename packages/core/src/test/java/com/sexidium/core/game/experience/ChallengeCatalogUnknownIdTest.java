package com.sexidium.core.game.experience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The silent-drop bug, and the four questions that must never guess.
 *
 * <p>{@link ChallengeCatalog#create} skips ids it does not know. That is right for composition — a
 * cosmetic twist that vanished is survivable. It is catastrophic for the four <b>world-shaping</b>
 * questions, because they were answered from the same list: a node one build behind (or an
 * {@code experiences.challenges} row with a typo) answered "no, this does not need a void world" and
 * the world was generated with the normal terrain generator over what should have been an empty
 * SkyBlock. Silent, permanent, and reachable today with no version skew at all.</p>
 */
class ChallengeCatalogUnknownIdTest {

  private static final String MISSING = "notachallenge";

  @Test
  @DisplayName("a void-world decision refuses to be made from an id this build does not have")
  void voidWorldDecisionRejectsUnknownChallenge() {
    // Before the fix this returned false -- and false means "generate normal terrain".
    UnknownChallengeException refused = assertThrows(UnknownChallengeException.class,
        () -> ChallengeCatalog.anyRequiresVoidWorld(List.of("randomlayers", MISSING)));
    assertEquals(List.of(MISSING), refused.unknownIds());
  }

  @Test
  @DisplayName("the void-Nether, hardcore and hardcore-demand decisions refuse the same way")
  void everyWorldShapingQueryIsStrict() {
    assertThrows(UnknownChallengeException.class,
        () -> ChallengeCatalog.anyRequiresVoidNether(List.of(MISSING)));
    assertThrows(UnknownChallengeException.class,
        () -> ChallengeCatalog.anyRequiresHardcore(List.of(MISSING)));
    assertThrows(UnknownChallengeException.class,
        () -> ChallengeCatalog.hardcoreDemand(List.of(MISSING)));
  }

  @Test
  @DisplayName("a known selection is answered exactly as before")
  void knownIdsAreUnaffected() {
    assertTrue(ChallengeCatalog.anyRequiresVoidWorld(List.of("randomlayers")));
    assertFalse(ChallengeCatalog.anyRequiresVoidWorld(List.of("doubledrops")));
    assertFalse(ChallengeCatalog.anyRequiresVoidWorld(List.of()));
    // Null and blank are "nothing asked", not "something unknown", and always were.
    assertFalse(ChallengeCatalog.anyRequiresVoidWorld(null));
    assertFalse(ChallengeCatalog.anyRequiresVoidWorld(java.util.Arrays.asList("", "  ")));
  }

  @Test
  @DisplayName("composition stays tolerant: a dropped cosmetic twist must not refuse a whole world")
  void createStaysTolerant() {
    List<Challenge> created = ChallengeCatalog.create(List.of("doubledrops", MISSING));
    assertEquals(1, created.size());
    assertEquals("doubledrops", created.get(0).id());
  }

  @Test
  @DisplayName("unknown() names exactly what is missing, deduplicated, in request order")
  void unknownNamesTheMissingIds() {
    assertEquals(List.of("zzz", "yyy"),
        ChallengeCatalog.unknown(List.of("doubledrops", "zzz", "yyy", "ZZZ", "")));
    assertEquals(List.of(), ChallengeCatalog.unknown(List.of("doubledrops")));
    assertEquals(List.of(), ChallengeCatalog.unknown(null));
  }

  @Test
  @DisplayName("case and whitespace are normalised, so a stored id never reads as unknown by accident")
  void idsAreNormalisedBeforeTheyAreJudged() {
    assertTrue(ChallengeCatalog.anyRequiresVoidWorld(List.of("  RandomLayers  ")));
  }
}
