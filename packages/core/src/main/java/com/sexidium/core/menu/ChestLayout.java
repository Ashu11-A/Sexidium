package com.sexidium.core.menu;

import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Slot coordinates and mapping for the standard 54-slot (6 rows × 9 columns) double-chest screen layout.
 *
 * <pre>
 * Row 0: [S0] [|] [C00] [C01] [C02] [C03] [C04] [C05] [C06]  (slots  0.. 8)
 * Row 1: [S1] [|] [C07] [C08] [C09] [C10] [C11] [C12] [C13]  (slots  9..17)
 * Row 2: [S2] [|] [C14] [C15] [C16] [C17] [C18] [C19] [C20]  (slots 18..26)
 * Row 3: [S3] [|] [C21] [C22] [C23] [C24] [C25] [C26] [C27]  (slots 27..35)
 * Row 4: [S4] [|] [---] [---] [---] [---] [---] [---] [---]  (slots 36..44)
 * Row 5: [S5] [|] [   ] [BCK] [PRV] [PAG] [NXT] [CHS] [PRI]  (slots 45..53)
 * </pre>
 *
 * <ul>
 *   <li><b>Sidebar (Column 0):</b> 6 slots: 0, 9, 18, 27, 36, 45.</li>
 *   <li><b>Separator (Column 1):</b> 6 divider slots: 1, 10, 19, 28, 37, 46.</li>
 *   <li><b>Content (Columns 2–8, Rows 0–3):</b> 28 capacity (4 rows × 7 columns), indices 0..27.</li>
 *   <li><b>Row divider (Row 4, Columns 2–8):</b> slots 38..44, separating the content area from the nav row.</li>
 *   <li><b>Bottom Nav (Row 5):</b> Back at 47, Prev at 48, Page at 49, Next at 50, Chaos at 51, Primary at 53.</li>
 * </ul>
 */
public final class ChestLayout {

  public static final int ROWS = 6;
  public static final int COLS = 9;
  public static final int COLUMNS = 9;
  public static final int SIZE = 54;

  public static final int CONTENT_ROWS = 4;
  public static final int CONTENT_COLUMNS = 7;
  public static final int CONTENT_CAPACITY = 28;
  public static final int SIDEBAR_CAPACITY = 6;

  public static final int SLOT_BACK = 47;
  public static final int SLOT_PREV = 48;
  public static final int SLOT_PAGE = 49;
  public static final int SLOT_NEXT = 50;
  public static final int SLOT_CHAOS = 51;
  public static final int SLOT_PRIMARY = 53;

  // Common aliases
  public static final int BACK_SLOT = SLOT_BACK;
  public static final int PREV_PAGE_SLOT = SLOT_PREV;
  public static final int PAGE_INFO_SLOT = SLOT_PAGE;
  public static final int NEXT_PAGE_SLOT = SLOT_NEXT;
  public static final int PRIMARY_ACTION_SLOT = SLOT_PRIMARY;
  public static final int CHAOS_SLOT = SLOT_CHAOS;

  public static final List<Integer> SEPARATOR_SLOTS = List.of(1, 10, 19, 28, 37, 46);
  public static final List<Integer> ROW_DIVIDER_SLOTS = List.of(38, 39, 40, 41, 42, 43, 44);
  public static final List<Integer> SIDEBAR_SLOTS = List.of(0, 9, 18, 27, 36, 45);
  public static final List<Integer> CONTENT_SLOTS;

  static {
    List<Integer> slots = new ArrayList<>(CONTENT_CAPACITY);
    for (int i = 0; i < CONTENT_CAPACITY; i++) {
      slots.add(contentSlot(i));
    }
    CONTENT_SLOTS = Collections.unmodifiableList(slots);
  }

  private ChestLayout() {
  }

  public static int slot(int row, int col) {
    if (row < 0 || row >= ROWS) {
      throw new IllegalArgumentException("Row out of bounds (0.." + (ROWS - 1) + "): " + row);
    }
    if (col < 0 || col >= COLS) {
      throw new IllegalArgumentException("Column out of bounds (0.." + (COLS - 1) + "): " + col);
    }
    return row * COLS + col;
  }

  public static int contentSlot(int contentIndex) {
    if (contentIndex < 0 || contentIndex >= CONTENT_CAPACITY) {
      throw new IllegalArgumentException("Content index out of bounds (0.." + (CONTENT_CAPACITY - 1) + "): " + contentIndex);
    }
    int contentRow = contentIndex / CONTENT_COLUMNS;
    int contentCol = contentIndex % CONTENT_COLUMNS;
    return slot(contentRow, contentCol + 2);
  }

  public static int contentIndex(int slot) {
    if (!isContentSlot(slot)) {
      return -1;
    }
    int row = slot / COLS;
    int col = slot % COLS;
    return row * CONTENT_COLUMNS + (col - 2);
  }

  public static int sidebarSlot(int sidebarIndex) {
    if (sidebarIndex < 0 || sidebarIndex >= SIDEBAR_CAPACITY) {
      throw new IllegalArgumentException("Sidebar index out of bounds (0.." + (SIDEBAR_CAPACITY - 1) + "): " + sidebarIndex);
    }
    return slot(sidebarIndex, 0);
  }

  public static int sidebarIndex(int slot) {
    if (!isSidebarSlot(slot)) {
      return -1;
    }
    return slot / COLS;
  }

  public static boolean isContentSlot(int slot) {
    if (slot < 0 || slot >= SIZE) {
      return false;
    }
    int row = slot / COLS;
    int col = slot % COLS;
    return row < CONTENT_ROWS && col >= 2 && col < COLS;
  }

  public static boolean isSidebarSlot(int slot) {
    if (slot < 0 || slot >= SIZE) {
      return false;
    }
    return (slot % COLS) == 0;
  }

  public static boolean isSeparatorSlot(int slot) {
    if (slot < 0 || slot >= SIZE) {
      return false;
    }
    return (slot % COLS) == 1 || ROW_DIVIDER_SLOTS.contains(slot);
  }

  public static boolean isBottomNavSlot(int slot) {
    if (slot < 0 || slot >= SIZE) {
      return false;
    }
    return (slot / COLS) == (ROWS - 1) && (slot % COLS) >= 2;
  }

  public static MenuButton separatorButton() {
    return MenuButton.label(ItemKey.minecraft("gray_stained_glass_pane"), " ", List.of());
  }

  public static MenuButton separatorButton(ItemKey icon) {
    return MenuButton.label(icon != null ? icon : ItemKey.minecraft("gray_stained_glass_pane"), " ", List.of());
  }

  public static MenuButton separatorItem() {
    return separatorButton();
  }

  public static void fillSeparator(MenuView view) {
    if (view == null) {
      return;
    }
    MenuButton separator = separatorButton();
    for (int slot : SEPARATOR_SLOTS) {
      if (slot < view.size()) {
        view.set(slot, separator);
      }
    }
    for (int slot : ROW_DIVIDER_SLOTS) {
      if (slot < view.size()) {
        view.set(slot, separator);
      }
    }
  }

  public static void fillSeparators(MenuView view) {
    fillSeparator(view);
  }
}
