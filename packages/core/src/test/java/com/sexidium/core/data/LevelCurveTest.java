package com.sexidium.core.data;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The levelling curve, pinned to the numbers the rework was designed around.
 *
 * <p>The old curve was a flat {@code points / 100}: every level cost the same, so a player's twentieth
 * level was as cheap as their first and Sigma (level 80) was eight thousand points of grinding at a
 * constant rate. The replacement makes each level cost more than the last, which moves BOTH ends —
 * the first levels get cheaper (a new player sees progress immediately) and the top gets ~3x harder.
 * The table below IS the specification; if it changes, the change was deliberate.</p>
 */
class LevelCurveTest {

  private static final LevelCurve CURVE = LevelCurve.defaults();

  @Test
  void pointsForLevelMatchesTheTable() {
    assertEquals(0L, CURVE.pointsForLevel(0), "level 0 is free — a player with no points is level 0");
    assertEquals(40L, CURVE.pointsForLevel(1));
    assertEquals(141L, CURVE.pointsForLevel(3));
    assertEquals(270L, CURVE.pointsForLevel(5));
    assertEquals(715L, CURVE.pointsForLevel(10));
    assertEquals(2130L, CURVE.pointsForLevel(20));
    assertEquals(5565L, CURVE.pointsForLevel(35));
    assertEquals(12595L, CURVE.pointsForLevel(55));
    assertEquals(25320L, CURVE.pointsForLevel(80));
  }

  /**
   * The headline constraint: Sigma has to mean something. Bounded on both sides because "harder" that
   * overshoots is just as wrong as no change — nobody would ever reach it.
   */
  @Test
  void newCurveIsAboutThreeTimesHarderAtTheTopRank() {
    long oldTotal = 100L * 80L;
    long newTotal = CURVE.pointsForLevel(80);
    assertTrue(newTotal >= 3L * oldTotal, "Sigma must cost at least 3x the old flat curve, was " + newTotal);
    assertTrue(newTotal <= (long) (3.5 * oldTotal), "…but not so much that it is unreachable, was " + newTotal);
  }

  /** The other half of the deal: the start of the game gets FASTER, not slower. */
  @Test
  void earlyLevelsAreCheaperThanTheOldFlatCurve() {
    for (int level = 1; level <= 5; level++) {
      assertTrue(CURVE.pointsForLevel(level) < 100L * level,
          "level " + level + " must cost less than the old flat curve, was " + CURVE.pointsForLevel(level));
    }
  }

  @Test
  void costPerLevelIsStrictlyIncreasing() {
    for (int level = 0; level < 500; level++) {
      assertTrue(CURVE.costOfLevel(level + 1) > CURVE.costOfLevel(level),
          "each level must cost strictly more than the one before it, broke at " + level);
    }
  }

  /**
   * THE test that matters. {@code levelFor} starts from a {@code Math.sqrt} closed form, and a double
   * that lands on 79.999999999 for the points that are exactly level 80 would silently demote a Sigma
   * to an Alpha. Checking both sides of every boundary in 0..500 is the only way to catch that.
   */
  @Test
  void levelForIsTheExactInverse() {
    for (int level = 0; level <= 500; level++) {
      int exact = (int) CURVE.pointsForLevel(level);
      assertEquals(level, CURVE.levelFor(exact),
          "the exact cumulative cost of level " + level + " must BE level " + level);
      if (level > 0) {
        assertEquals(level - 1, CURVE.levelFor(exact - 1),
            "one point short of level " + level + " must still be level " + (level - 1));
      }
    }
  }

  @Test
  void levelForIsMonotonic() {
    int previous = 0;
    for (int points = 0; points <= 200_000; points++) {
      int level = CURVE.levelFor(points);
      assertTrue(level >= previous, "more points must never mean a lower level, broke at " + points);
      previous = level;
    }
  }

  @Test
  void progressIsZeroAtABoundaryAndApproachesOne() {
    assertEquals(0f, CURVE.progress((int) CURVE.pointsForLevel(10)), 0.0001f,
        "landing exactly on a level starts the next one empty");
    float almostThere = CURVE.progress((int) CURVE.pointsForLevel(11) - 1);
    assertTrue(almostThere > 0.98f && almostThere < 1f,
        "one point short of the next level must be an almost-full bar, was " + almostThere);
    assertEquals(0f, CURVE.progress(0), 0.0001f);
  }

  /** The documented escape hatch back to the old behaviour, so an operator is never trapped. */
  @Test
  void growthZeroReproducesTheLegacyFlatCurve() {
    LevelCurve flat = new LevelCurve(100, 0);
    for (int points : new int[] {0, 1, 99, 100, 101, 999, 1000, 8000, 123_456}) {
      assertEquals(points / 100, flat.levelFor(points), "flat curve must still be points/100 at " + points);
    }
    assertEquals(100L * 37L, flat.pointsForLevel(37));
  }

  /**
   * {@code 8*growth*points} leaves int range long before {@code Integer.MAX_VALUE}, so the closed form
   * is computed in double/long. A player cannot actually hold two billion points, but a corrupt row can.
   */
  @Test
  void hugePointsDoNotOverflow() {
    int level = CURVE.levelFor(Integer.MAX_VALUE);
    assertEquals(LevelCurve.MAX_LEVEL, level, "an absurd point total clamps at the ceiling, it does not wrap");
    assertTrue(CURVE.pointsForLevel(LevelCurve.MAX_LEVEL) > 0L, "the ceiling's cumulative cost must stay positive");
    assertEquals(0, CURVE.levelFor(Integer.MIN_VALUE), "and a negative total is simply level 0");
  }

  @Test
  void fromConfigurationDefaultsToTheNewCurve() {
    assertEquals(LevelCurve.defaults(), LevelCurve.from(new PropertiesConfigurationAdapter()));
    assertEquals(LevelCurve.defaults(), LevelCurve.from(null));
  }

  /**
   * A stock pre-rework config still carries {@code points-per-level: 100}. It must be IGNORED unless the
   * operator also asks for the flat curve by setting growth to 0 — otherwise every existing server would
   * boot into a curve that starts at 100 and climbs, which is neither the old nor the new behaviour.
   */
  @Test
  void fromConfigurationHonoursPointsPerLevelOnlyOnTheFlatCurve() {
    ConfigurationAdapter legacyOnly = new PropertiesConfigurationAdapter();
    legacyOnly.set("ranks.points-per-level", 250);
    assertEquals(LevelCurve.defaults(), LevelCurve.from(legacyOnly),
        "points-per-level alone must not change the new curve");

    ConfigurationAdapter optedOut = new PropertiesConfigurationAdapter();
    optedOut.set("ranks.level-curve.growth", 0);
    optedOut.set("ranks.points-per-level", 250);
    assertEquals(new LevelCurve(250, 0), LevelCurve.from(optedOut));
  }

  @Test
  void fromConfigurationPrefersAnExplicitCurve() {
    ConfigurationAdapter configuration = new PropertiesConfigurationAdapter();
    configuration.set("ranks.level-curve.base", 60);
    configuration.set("ranks.level-curve.growth", 12);
    configuration.set("ranks.points-per-level", 250);
    assertEquals(new LevelCurve(60, 12), LevelCurve.from(configuration));
  }

  /** A misconfigured curve must degrade, not divide by zero or run backwards. */
  @Test
  void nonsenseTunablesAreClamped() {
    assertEquals(new LevelCurve(1, 0), new LevelCurve(0, -5));
    assertEquals(0, new LevelCurve(0, -5).levelFor(0));
  }
}
