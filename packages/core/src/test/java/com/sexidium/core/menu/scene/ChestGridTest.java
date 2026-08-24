package com.sexidium.core.menu.scene;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Guards the chest-slot hitbox geometry that makes a baked screen clickable: {@link Box#intersects(Box)},
 * {@link ChestGrid#slotsIntersecting(Box)} and the derived {@link SceneTemplates#hubHitGroups()}. The hub
 * regression that motivated this is "only the centre slot under each big tile was clickable" — the groups
 * below give every tile its full footprint while staying disjoint so no slot routes to two tabs.
 */
class ChestGridTest {

  @Test
  void boxIntersectionIsByPositiveAreaOnly() {
    Box a = new Box(0, 0, 10, 10);
    assertTrue(a.intersects(new Box(5, 5, 10, 10)), "overlapping boxes intersect");
    assertTrue(a.intersects(new Box(-5, -5, 10, 10)), "overlap from the top-left intersects");
    assertFalse(a.intersects(new Box(10, 0, 10, 10)), "edge-touching on x does not intersect");
    assertFalse(a.intersects(new Box(0, 10, 10, 10)), "edge-touching on y does not intersect");
    assertFalse(a.intersects(new Box(20, 20, 5, 5)), "disjoint boxes do not intersect");
  }

  @Test
  void slotsIntersectingFindsEverySlotUnderABox() {
    // A 32x30 tile centred on slot 12 covers its centre plus the slots its art clips into.
    int[] hits = ChestGrid.slotsIntersecting(ChestGrid.centeredOn(12, 32, 30));
    assertArrayEquals(new int[] {2, 3, 4, 11, 12, 13, 20, 21, 22}, hits,
        "the raw footprint of the tile centred on slot 12");
    // The single-slot icon box only ever hits its own slot.
    assertArrayEquals(new int[] {30}, ChestGrid.slotsIntersecting(ChestGrid.iconBox(30)));
  }

  @Test
  void hubHitGroupsCoverEachCardAndStayDisjoint() {
    // Each hub card spans a 3-column × 2-row slot block; the group is every slot under the card so a click
    // anywhere on it fires that tab. Locked so a layout change is a visible diff, not a silent re-routing.
    int[][] normal = {
        {0, 1, 2, 9, 10, 11},     // card (0,0) Minigames
        {3, 4, 5, 12, 13, 14},    // card (1,0) Create
        {6, 7, 8, 15, 16, 17},    // card (2,0) My Worlds
        {18, 19, 20, 27, 28, 29}, // card (0,1) Browse
        {21, 22, 23, 30, 31, 32}, // card (1,1) Lobby
        {24, 25, 26, 33, 34, 35}, // card (2,1) Friends
    };
    int[][] op = new int[normal.length + 1][];
    System.arraycopy(normal, 0, op, 0, normal.length);
    op[normal.length] = new int[] {39, 40, 41, 48, 49, 50}; // card (1,2) Settings

    assertEquals(SceneTemplates.HUB_TABS_NORMAL, SceneTemplates.hubHitGroups(false).length,
        "one group per normal card");
    assertEquals(SceneTemplates.HUB_TABS_OP, SceneTemplates.hubHitGroups(true).length,
        "one group per operator card");
    for (int i = 0; i < normal.length; i++) {
      assertArrayEquals(normal[i], SceneTemplates.hubHitGroups(false)[i], "normal card " + i + " footprint");
    }
    for (int i = 0; i < op.length; i++) {
      assertArrayEquals(op[i], SceneTemplates.hubHitGroups(true)[i], "operator card " + i + " footprint");
    }

    // Every group is disjoint (no slot routes to two cards) in both variants.
    for (int[][] groups : new int[][][] {SceneTemplates.hubHitGroups(false), SceneTemplates.hubHitGroups(true)}) {
      Set<Integer> seen = new HashSet<>();
      for (int[] group : groups) {
        for (int slot : group) {
          assertTrue(seen.add(slot), "slot " + slot + " is claimed by two cards");
        }
      }
    }
  }
}
