package com.sexidium.core.game.modes.minigames.race;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceCatalogTest {
  @Test
  void everyRolledMixHasThreeTiersAndNeverTwoHard() {
    Random random = new Random(42);
    for (int round = 0; round < 1000; round++) {
      List<String> mix = RaceCatalog.rollDifficultyMix(random);
      assertEquals(3, mix.size(), "a round is always three targets");
      long hard = mix.stream().filter(RaceCatalog.HARD::equals).count();
      assertTrue(hard <= 1, "a round must never contain two hard targets: " + mix);
      assertTrue(RaceCatalog.isValidMix(mix));
    }
  }

  @Test
  void easyPoolIsAllOresAndStructuresOnlyForHarderTiers() {
    assertTrue(RaceCatalog.defaultItems(RaceCatalog.EASY).contains("iron_ingot"));
    assertTrue(RaceCatalog.defaultItems(RaceCatalog.EASY).contains("diamond"));
    assertTrue(RaceCatalog.defaultItems(RaceCatalog.EASY).contains("coal"));
    assertTrue(RaceCatalog.defaultStructures(RaceCatalog.EASY).isEmpty(), "easy is always an item objective");
    assertFalse(RaceCatalog.defaultStructures(RaceCatalog.MEDIUM).isEmpty());
    assertFalse(RaceCatalog.defaultStructures(RaceCatalog.HARD).isEmpty());
  }

  @Test
  void isValidMixRejectsTwoHard() {
    assertFalse(RaceCatalog.isValidMix(List.of(RaceCatalog.HARD, RaceCatalog.HARD, RaceCatalog.EASY)));
    assertTrue(RaceCatalog.isValidMix(List.of(RaceCatalog.EASY, RaceCatalog.MEDIUM, RaceCatalog.HARD)));
  }

  @Test
  void pointsRiseWithDifficulty() {
    assertTrue(RaceCatalog.defaultPoints(RaceCatalog.HARD) > RaceCatalog.defaultPoints(RaceCatalog.MEDIUM));
    assertTrue(RaceCatalog.defaultPoints(RaceCatalog.MEDIUM) > RaceCatalog.defaultPoints(RaceCatalog.EASY));
  }
}
