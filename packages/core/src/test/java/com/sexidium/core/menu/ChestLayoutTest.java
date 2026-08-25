package com.sexidium.core.menu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestLayoutTest {

  @Test
  @DisplayName("chest layout dimensions and constants are correct")
  void constantsMatchDoubleChestSpecification() {
    assertEquals(6, ChestLayout.ROWS);
    assertEquals(9, ChestLayout.COLS);
    assertEquals(54, ChestLayout.SIZE);
    assertEquals(28, ChestLayout.CONTENT_CAPACITY);
    assertEquals(6, ChestLayout.SIDEBAR_CAPACITY);
    assertEquals(47, ChestLayout.SLOT_BACK);
    assertEquals(48, ChestLayout.SLOT_PREV);
    assertEquals(49, ChestLayout.SLOT_PAGE);
    assertEquals(50, ChestLayout.SLOT_NEXT);
    assertEquals(51, ChestLayout.SLOT_CHAOS);
    assertEquals(53, ChestLayout.SLOT_PRIMARY);
  }

  @Test
  @DisplayName("content slots map 0..27 to columns 2..8 and rows 0..3 correctly")
  void contentSlotMapping() {
    // First row of content (Row 0, Cols 2..8 -> slots 2..8)
    assertEquals(2, ChestLayout.contentSlot(0));
    assertEquals(8, ChestLayout.contentSlot(6));

    // Second row of content (Row 1, Cols 2..8 -> slots 11..17)
    assertEquals(11, ChestLayout.contentSlot(7));
    assertEquals(17, ChestLayout.contentSlot(13));

    // Last slot (Row 3, Col 8 -> slot 35)
    assertEquals(35, ChestLayout.contentSlot(27));

    // Roundtrip verification
    for (int i = 0; i < ChestLayout.CONTENT_CAPACITY; i++) {
      int slot = ChestLayout.contentSlot(i);
      assertEquals(i, ChestLayout.contentIndex(slot));
      assertTrue(ChestLayout.isContentSlot(slot));
      assertFalse(ChestLayout.isSidebarSlot(slot));
      assertFalse(ChestLayout.isSeparatorSlot(slot));
      assertFalse(ChestLayout.isBottomNavSlot(slot));
    }
  }

  @Test
  @DisplayName("sidebar slots map 0..5 to column 0 correctly")
  void sidebarSlotMapping() {
    assertEquals(0, ChestLayout.sidebarSlot(0));
    assertEquals(9, ChestLayout.sidebarSlot(1));
    assertEquals(18, ChestLayout.sidebarSlot(2));
    assertEquals(27, ChestLayout.sidebarSlot(3));
    assertEquals(36, ChestLayout.sidebarSlot(4));
    assertEquals(45, ChestLayout.sidebarSlot(5));

    for (int i = 0; i < ChestLayout.SIDEBAR_CAPACITY; i++) {
      int slot = ChestLayout.sidebarSlot(i);
      assertEquals(i, ChestLayout.sidebarIndex(slot));
      assertTrue(ChestLayout.isSidebarSlot(slot));
      assertFalse(ChestLayout.isContentSlot(slot));
      assertFalse(ChestLayout.isSeparatorSlot(slot));
    }
  }

  @Test
  @DisplayName("separator slots map to column 1 and row 4 divider across double chest")
  void separatorSlotMapping() {
    int[] col1Expected = {1, 10, 19, 28, 37, 46};
    for (int slot : col1Expected) {
      assertTrue(ChestLayout.isSeparatorSlot(slot));
      assertFalse(ChestLayout.isContentSlot(slot));
      assertFalse(ChestLayout.isSidebarSlot(slot));
    }

    int[] row4Expected = {38, 39, 40, 41, 42, 43, 44};
    for (int slot : row4Expected) {
      assertTrue(ChestLayout.isSeparatorSlot(slot));
      assertFalse(ChestLayout.isContentSlot(slot));
      assertFalse(ChestLayout.isSidebarSlot(slot));
    }

    MenuView view = new MenuView("Test", 6);
    ChestLayout.fillSeparator(view);
    for (int slot : col1Expected) {
      assertTrue(view.buttons().containsKey(slot));
    }
    for (int slot : row4Expected) {
      assertTrue(view.buttons().containsKey(slot));
    }
  }

  @Test
  @DisplayName("out of bounds indices throw IllegalArgumentException")
  void outOfBoundsChecks() {
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.contentSlot(-1));
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.contentSlot(35));
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.sidebarSlot(-1));
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.sidebarSlot(6));
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.slot(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.slot(6, 0));
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.slot(0, -1));
    assertThrows(IllegalArgumentException.class, () -> ChestLayout.slot(0, 9));
  }
}
