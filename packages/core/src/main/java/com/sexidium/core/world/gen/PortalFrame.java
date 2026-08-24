package com.sexidium.core.world.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the empty interior of an obsidian Nether-portal frame around a point, so a portal can be created
 * <b>directly</b> instead of by simulating the fire that would have created it.
 *
 * <h2>Why this exists</h2>
 * Omni Chunk replays a player's flint-and-steel as a real use, and the natural way to do that is to place
 * a fire block and let vanilla notice the frame. That turned out to be a bad way to copy a portal: fire is
 * a live block with its own behaviour — it spreads, it burns out, it lands somewhere unintended when a
 * copied frame is not exactly like the original — so lighting one portal did not reliably light the rest,
 * and the fire could leave the copies broken. Placing the portal blocks outright is deterministic, has no
 * side effects, and is naturally <b>idempotent</b>: a frame that is already lit is simply left alone,
 * which matters because a chunk can replay its history more than once.
 *
 * <h2>The shape it accepts</h2>
 * The vanilla rule: a rectangle of air (2–21 wide, 3–21 tall) enclosed by obsidian on all four sides,
 * lying in the XY or ZY plane. The search starts from the ignition point, walks down to the floor of the
 * cavity, then measures its width and height and verifies the frame around it.
 *
 * <p>Pure and host-free — it reads the world through a {@link Blocks} probe and returns coordinates, so
 * the whole rule set is unit-tested without a server ({@code PortalFrameTest}) and the caller decides how
 * to place the result.</p>
 */
public final class PortalFrame {
  /** Vanilla's limits on a portal's inner cavity. */
  public static final int MIN_WIDTH = 2;
  public static final int MAX_WIDTH = 21;
  public static final int MIN_HEIGHT = 3;
  public static final int MAX_HEIGHT = 21;

  /** The block a frame must be built from. */
  public static final String FRAME_BLOCK = "obsidian";
  /** The block that fills a lit portal. */
  public static final String PORTAL_BLOCK = "nether_portal";

  /** Reads block ids out of a world. Returns the plain id ({@code obsidian}), never null. */
  @FunctionalInterface
  public interface Blocks {
    String valueAt(int blockX, int blockY, int blockZ);
  }

  /**
   * A located frame: every interior position to fill, and which way the portal faces.
   *
   * @param interior the cavity's block positions as {@code {x, y, z}}, bottom row first
   * @param alongX   true when the portal's plane runs along X (its axis is {@code x}), false for Z
   * @param lit      true when the cavity is already full of portal blocks — nothing to do
   */
  public record Frame(List<int[]> interior, boolean alongX, boolean lit) {
    public String axis() {
      return alongX ? "x" : "z";
    }

    public int size() {
      return interior.size();
    }
  }

  private PortalFrame() {
  }

  /**
   * The portal frame whose interior contains {@code (x, y, z)}, or null when there is no valid frame
   * there. Both orientations are tried; a frame is returned whether or not it is already lit, so the
   * caller can tell "nothing to do" (a lit frame) apart from "not a portal" (null).
   */
  public static Frame find(Blocks blocks, int blockX, int blockY, int blockZ) {
    if (blocks == null) {
      return null;
    }
    Frame alongX = findOnAxis(blocks, blockX, blockY, blockZ, true);
    return alongX != null ? alongX : findOnAxis(blocks, blockX, blockY, blockZ, false);
  }

  private static Frame findOnAxis(Blocks blocks, int blockX, int blockY, int blockZ, boolean alongX) {
    // 1. Drop to the floor of the cavity: the lowest interior block still sitting on the frame.
    int bottomY = blockY;
    while (blockY - bottomY < MAX_HEIGHT && isInterior(blocks, alongX, blockX, bottomY - 1, blockZ)) {
      bottomY--;
    }
    if (!isObsidian(blocks, blockX, bottomY - 1, blockZ)) {
      return null; // nothing solid underneath — not a portal floor
    }

    // 2. Measure the cavity's width, walking both ways along the axis until the frame stops it.
    int left = 0;
    while (left < MAX_WIDTH && isInterior(blocks, alongX, shift(blockX, alongX, -left - 1),
        bottomY, shift(blockZ, !alongX, -left - 1))) {
      left++;
    }
    int right = 0;
    while (left + right < MAX_WIDTH && isInterior(blocks, alongX, shift(blockX, alongX, right + 1),
        bottomY, shift(blockZ, !alongX, right + 1))) {
      right++;
    }
    int width = left + right + 1;
    if (width < MIN_WIDTH || width > MAX_WIDTH) {
      return null;
    }
    int originX = shift(blockX, alongX, -left);
    int originZ = shift(blockZ, !alongX, -left);

    // 3. Measure the height: rows of cavity stacked on top of each other, all the way across.
    int height = 0;
    while (height < MAX_HEIGHT && rowIsInterior(blocks, alongX, originX, bottomY + height, originZ, width)) {
      height++;
    }
    if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
      return null;
    }

    // 4. Verify the frame itself: obsidian all the way round the cavity.
    if (!encloses(blocks, alongX, originX, bottomY, originZ, width, height)) {
      return null;
    }

    List<int[]> interior = new ArrayList<>(width * height);
    boolean lit = true;
    for (int row = 0; row < height; row++) {
      for (int column = 0; column < width; column++) {
        int x = shift(originX, alongX, column);
        int z = shift(originZ, !alongX, column);
        interior.add(new int[] {x, bottomY + row, z});
        lit &= PORTAL_BLOCK.equals(valueAt(blocks, x, bottomY + row, z));
      }
    }
    return new Frame(List.copyOf(interior), alongX, lit);
  }

  /** Whether the frame's obsidian runs all the way round a cavity of this size. */
  private static boolean encloses(Blocks blocks, boolean alongX, int originX, int bottomY, int originZ,
      int width, int height) {
    for (int column = 0; column < width; column++) {
      int x = shift(originX, alongX, column);
      int z = shift(originZ, !alongX, column);
      if (!isObsidian(blocks, x, bottomY - 1, z) || !isObsidian(blocks, x, bottomY + height, z)) {
        return false; // floor or lintel missing
      }
    }
    for (int row = 0; row < height; row++) {
      int y = bottomY + row;
      if (!isObsidian(blocks, shift(originX, alongX, -1), y, shift(originZ, !alongX, -1))
          || !isObsidian(blocks, shift(originX, alongX, width), y, shift(originZ, !alongX, width))) {
        return false; // a side post is missing
      }
    }
    return true;
  }

  /** Whether every block of one horizontal row of the cavity is interior. */
  private static boolean rowIsInterior(Blocks blocks, boolean alongX, int originX, int y, int originZ, int width) {
    for (int column = 0; column < width; column++) {
      if (!isInterior(blocks, alongX, shift(originX, alongX, column), y, shift(originZ, !alongX, column))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a block may be part of a portal's inside: empty, or already portal (so a lit frame is still
   * recognised and can be reported as "nothing to do" rather than as no frame at all). Fire counts too —
   * that is the state a half-finished ignition leaves behind, and it must not stop the frame being found.
   */
  private static boolean isInterior(Blocks blocks, boolean alongX, int blockX, int blockY, int blockZ) {
    String value = valueAt(blocks, blockX, blockY, blockZ);
    return "air".equals(value) || "cave_air".equals(value) || "void_air".equals(value)
        || "fire".equals(value) || PORTAL_BLOCK.equals(value);
  }

  private static boolean isObsidian(Blocks blocks, int blockX, int blockY, int blockZ) {
    return FRAME_BLOCK.equals(valueAt(blocks, blockX, blockY, blockZ));
  }

  /** Adds {@code delta} to a coordinate only when {@code active} — the axis-selection trick. */
  private static int shift(int coordinate, boolean active, int delta) {
    return active ? coordinate + delta : coordinate;
  }

  private static String valueAt(Blocks blocks, int blockX, int blockY, int blockZ) {
    String value = blocks.valueAt(blockX, blockY, blockZ);
    return value == null ? "air" : value.toLowerCase(Locale.ROOT);
  }
}
