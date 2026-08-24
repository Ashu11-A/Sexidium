package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.challenges.LookMultiplierLadder.Milestone;
import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookMultiplierLadderTest {
  private static final ItemKey COBBLE = ItemKey.minecraft("cobblestone");
  private static final ItemKey IRON = ItemKey.minecraft("iron_ingot");
  private static final ItemKey DIAMOND = ItemKey.minecraft("diamond");

  private static LookMultiplierLadder ladder() {
    return new LookMultiplierLadder(List.of(
        new Milestone(COBBLE, 3, 2),
        new Milestone(IRON, 1, 5),
        new Milestone(DIAMOND, 1, 10)), 1);
  }

  private static ToIntFunction<ItemKey> held(Map<ItemKey, Integer> counts) {
    return item -> counts.getOrDefault(item, 0);
  }

  @Test
  void multiplierAt_usesStartingBeforeAnyRung_thenTheClearedRung() {
    LookMultiplierLadder ladder = ladder();
    assertEquals(1, ladder.multiplierAt(0));
    assertEquals(2, ladder.multiplierAt(1));
    assertEquals(5, ladder.multiplierAt(2));
    assertEquals(10, ladder.multiplierAt(3));
    // Clamped past the top rung.
    assertEquals(10, ladder.multiplierAt(99));
  }

  @Test
  void next_pointsAtTheRungBeingWorkedToward_andNullWhenMaxed() {
    LookMultiplierLadder ladder = ladder();
    assertEquals(COBBLE, ladder.next(0).item());
    assertEquals(DIAMOND, ladder.next(2).item());
    assertFalse(ladder.maxed(2));
    assertTrue(ladder.maxed(3));
    assertNull(ladder.next(3));
  }

  @Test
  void advance_clearsAllSatisfiedRungsAtOnce_andNeverRegresses() {
    LookMultiplierLadder ladder = ladder();
    Map<ItemKey, Integer> counts = new HashMap<>();

    counts.put(COBBLE, 2); // one short of the 3 required
    assertEquals(0, ladder.advance(0, held(counts)));

    counts.put(COBBLE, 3); // meets cobblestone, but no iron yet
    assertEquals(1, ladder.advance(0, held(counts)));

    counts.put(IRON, 1); // now both cobblestone and iron clear in a single pass
    assertEquals(2, ladder.advance(0, held(counts)));

    // From an already-advanced level it does not fall back even if the earlier item is now gone.
    counts.remove(COBBLE);
    assertEquals(2, ladder.advance(2, held(counts)));
  }

  @Test
  void progress_isHeldOverRequired_clampedAndFullWhenMaxed() {
    LookMultiplierLadder ladder = ladder();
    Map<ItemKey, Integer> counts = new HashMap<>();

    assertEquals(0.0f, ladder.progress(0, held(counts)));
    counts.put(COBBLE, 2);
    assertEquals(2.0f / 3.0f, ladder.progress(0, held(counts)), 1e-6);
    counts.put(COBBLE, 9); // over-held is clamped to a full bar
    assertEquals(1.0f, ladder.progress(0, held(counts)));
    assertEquals(1.0f, ladder.progress(3, held(counts))); // maxed
  }

  @Test
  void emptyLadder_isImmediatelyMaxed_atStartingMultiplier() {
    LookMultiplierLadder ladder = new LookMultiplierLadder(List.of(), 4);
    assertTrue(ladder.maxed(0));
    assertNull(ladder.next(0));
    assertEquals(4, ladder.multiplierAt(0));
    assertEquals(1.0f, ladder.progress(0, item -> 0));
  }
}
