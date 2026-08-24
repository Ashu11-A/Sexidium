package com.sexidium.core.menu.scene;

/**
 * Maps a chest-GUI slot index to its pixel {@link Box} in the same coordinate space the scene glyph is
 * drawn in (origin = chest top-left, y down, width {@code 176}). This is the bridge that makes a fully
 * baked screen <i>clickable</i>: a baked tile/icon is drawn at {@link #iconBox(int)} for slot {@code n},
 * and the in-game adapter puts the (invisible) hitbox item in that same slot {@code n}, so the art and the
 * click target coincide.
 *
 * <p>Geometry is the vanilla generic-container layout: the top-left slot's 16×16 icon renders at GUI
 * {@code (8, 18)} and each subsequent slot steps by {@code 18} px. The scene glyph anchors the chest top
 * edge at {@code y = 0} (ascent 13), so glyph-pixel y equals GUI y and these boxes line up.</p>
 */
public final class ChestGrid {

  public static final int COLUMNS = 9;
  /** Rows in a full (baked-screen) generic container — the largest chest the scene system draws into. */
  public static final int ROWS = 6;
  /** Pixel pitch between adjacent slots. */
  public static final int SLOT = 18;
  /** GUI-pixel top-left of the first slot's 16×16 icon. */
  public static final int ORIGIN_X = 8;
  public static final int ORIGIN_Y = 18;
  /** Rendered item icon size in a slot. */
  public static final int ICON = 16;

  private ChestGrid() {
  }

  public static int column(int slot) {
    return slot % COLUMNS;
  }

  public static int row(int slot) {
    return slot / COLUMNS;
  }

  /** The 16×16 icon box for {@code slot}. */
  public static Box iconBox(int slot) {
    return new Box(ORIGIN_X + column(slot) * SLOT, ORIGIN_Y + row(slot) * SLOT, ICON, ICON);
  }

  /** The slot's icon centre x. */
  public static int centerX(int slot) {
    return ORIGIN_X + column(slot) * SLOT + ICON / 2;
  }

  /** The slot's icon centre y. */
  public static int centerY(int slot) {
    return ORIGIN_Y + row(slot) * SLOT + ICON / 2;
  }

  /** A {@code w×h} box centred on {@code slot}'s icon centre (for a tile/decoration around the slot). */
  public static Box centeredOn(int slot, int w, int h) {
    return new Box(centerX(slot) - w / 2, centerY(slot) - h / 2, w, h);
  }

  /**
   * Every slot in a six-row container whose 16×16 icon box overlaps {@code box}, ascending. This is how a
   * baked tile becomes clickable across its whole footprint instead of just its centre slot: the tile's
   * art box is mapped to the set of underlying slots a player can click to hit it.
   */
  public static int[] slotsIntersecting(Box box) {
    int size = COLUMNS * ROWS;
    int[] hits = new int[size];
    int count = 0;
    for (int slot = 0; slot < size; slot++) {
      if (iconBox(slot).intersects(box)) {
        hits[count++] = slot;
      }
    }
    return java.util.Arrays.copyOf(hits, count);
  }
}
