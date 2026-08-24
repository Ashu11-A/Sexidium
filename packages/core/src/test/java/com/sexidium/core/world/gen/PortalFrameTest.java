package com.sexidium.core.world.gen;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the portal geometry Omni Chunk uses to copy a lit portal into every chunk. The point of doing
 * this by shape rather than by simulating fire is that it is deterministic and idempotent: an already-lit
 * frame must be recognised as "nothing to do", because a chunk can replay its history more than once.
 */
class PortalFrameTest {

  @Test
  void aStandardFrameIsFoundFromTheFloorTheePlayerClicks() {
    World world = new World();
    world.buildFrame(0, 64, 0, 2, 3, true);

    // The player clicks the frame's floor; the cavity starts one block up.
    PortalFrame.Frame frame = PortalFrame.find(world, 0, 65, 0);

    assertNotNull(frame);
    assertEquals(6, frame.size(), "a 2x3 cavity");
    assertTrue(frame.alongX());
    assertEquals("x", frame.axis());
    assertFalse(frame.lit(), "an empty frame still needs lighting");
  }

  @Test
  void bothOrientationsWork() {
    World alongZ = new World();
    alongZ.buildFrame(0, 64, 0, 2, 3, false);
    PortalFrame.Frame frame = PortalFrame.find(alongZ, 0, 65, 0);

    assertNotNull(frame);
    assertFalse(frame.alongX());
    assertEquals("z", frame.axis());
    assertEquals(6, frame.size());
  }

  @Test
  void anAlreadyLitFrameIsReportedAsSuchRatherThanRelit() {
    // The whole reason for finding the shape: replaying an ignition into a chunk that already has the
    // portal must do nothing at all, not disturb it.
    World world = new World();
    world.buildFrame(0, 64, 0, 2, 3, true);
    world.fillInterior(0, 64, 0, 2, 3, true, PortalFrame.PORTAL_BLOCK);

    PortalFrame.Frame frame = PortalFrame.find(world, 0, 65, 0);

    assertNotNull(frame, "a lit frame is still a frame");
    assertTrue(frame.lit(), "…and must be recognised as already done");
  }

  @Test
  void aHalfLitFrameIsFinishedRatherThanLeftBroken() {
    // The state a failed fire-based ignition leaves behind: some portal, some fire, some air.
    World world = new World();
    world.buildFrame(0, 64, 0, 2, 3, true);
    world.set(0, 65, 0, PortalFrame.PORTAL_BLOCK);
    world.set(1, 65, 0, "fire");

    PortalFrame.Frame frame = PortalFrame.find(world, 0, 65, 0);

    assertNotNull(frame);
    assertFalse(frame.lit(), "it is not fully lit, so the caller must fill it");
    assertEquals(6, frame.size(), "…and fill the WHOLE cavity, including what is already portal");
  }

  @Test
  void aBrokenFrameIsNotAPortal() {
    World missingCorner = new World();
    missingCorner.buildFrame(0, 64, 0, 2, 3, true);
    missingCorner.set(0, 64, 0, "air"); // knock out a floor block

    assertNull(PortalFrame.find(missingCorner, 0, 65, 0));

    World missingPost = new World();
    missingPost.buildFrame(0, 64, 0, 2, 3, true);
    missingPost.set(-1, 66, 0, "air"); // knock out a side post

    assertNull(PortalFrame.find(missingPost, 0, 65, 0));
  }

  @Test
  void openAirIsNotAPortal() {
    assertNull(PortalFrame.find(new World(), 0, 65, 0), "nothing but air must never look like a frame");
  }

  @Test
  void aCavityOutsideVanillaLimitsIsRejected() {
    // Too short: a 2-high cavity is not a portal.
    World tooShort = new World();
    tooShort.buildFrame(0, 64, 0, 2, 2, true);
    assertNull(PortalFrame.find(tooShort, 0, 65, 0));

    // Too narrow: one block wide is not a portal either.
    World tooNarrow = new World();
    tooNarrow.buildFrame(0, 64, 0, 1, 3, true);
    assertNull(PortalFrame.find(tooNarrow, 0, 65, 0));

    // …and the largest legal frame still works.
    World largest = new World();
    largest.buildFrame(0, 64, 0, PortalFrame.MAX_WIDTH, PortalFrame.MAX_HEIGHT, true);
    PortalFrame.Frame frame = PortalFrame.find(largest, 0, 65, 0);
    assertNotNull(frame);
    assertEquals(PortalFrame.MAX_WIDTH * PortalFrame.MAX_HEIGHT, frame.size());
  }

  @Test
  void theFrameIsFoundFromAnywhereInsideIt() {
    // Omni Chunk records whichever block the player clicked, so the search must not depend on hitting a
    // particular corner.
    World world = new World();
    world.buildFrame(0, 64, 0, 3, 4, true);

    for (int x = 0; x < 3; x++) {
      for (int y = 65; y < 69; y++) {
        PortalFrame.Frame frame = PortalFrame.find(world, x, y, 0);
        assertNotNull(frame, "not found from inside at " + x + "," + y);
        assertEquals(12, frame.size());
      }
    }
  }

  @Test
  void everyInteriorPositionIsReturnedExactlyOnce() {
    World world = new World();
    world.buildFrame(0, 64, 0, 3, 4, true);
    PortalFrame.Frame frame = PortalFrame.find(world, 0, 65, 0);

    assertNotNull(frame);
    Map<String, Boolean> seen = new HashMap<>();
    for (int[] at : frame.interior()) {
      assertNull(seen.put(at[0] + "," + at[1] + "," + at[2], Boolean.TRUE), "duplicate position");
      assertEquals(0, at[2], "an X-axis portal is one block deep");
      assertTrue(at[0] >= 0 && at[0] < 3, "x inside the cavity");
      assertTrue(at[1] >= 65 && at[1] < 69, "y inside the cavity");
    }
    assertEquals(12, seen.size());
  }

  /** A sparse block world: everything is air unless something was written to it. */
  private static final class World implements PortalFrame.Blocks {
    private final Map<Long, String> blocks = new HashMap<>();

    @Override
    public String valueAt(int blockX, int blockY, int blockZ) {
      return blocks.getOrDefault(key(blockX, blockY, blockZ), "air");
    }

    void set(int blockX, int blockY, int blockZ, String value) {
      blocks.put(key(blockX, blockY, blockZ), value);
    }

    /**
     * Builds an obsidian frame whose CAVITY is {@code width} × {@code height}, with its bottom-left
     * interior block at {@code (originX, originY + 1, originZ)}.
     */
    void buildFrame(int originX, int originY, int originZ, int width, int height, boolean alongX) {
      for (int column = -1; column <= width; column++) {
        set(shift(originX, alongX, column), originY, shift(originZ, !alongX, column), PortalFrame.FRAME_BLOCK);
        set(shift(originX, alongX, column), originY + height + 1, shift(originZ, !alongX, column),
            PortalFrame.FRAME_BLOCK);
      }
      for (int row = 1; row <= height; row++) {
        set(shift(originX, alongX, -1), originY + row, shift(originZ, !alongX, -1), PortalFrame.FRAME_BLOCK);
        set(shift(originX, alongX, width), originY + row, shift(originZ, !alongX, width), PortalFrame.FRAME_BLOCK);
      }
    }

    void fillInterior(int originX, int originY, int originZ, int width, int height, boolean alongX, String value) {
      for (int column = 0; column < width; column++) {
        for (int row = 1; row <= height; row++) {
          set(shift(originX, alongX, column), originY + row, shift(originZ, !alongX, column), value);
        }
      }
    }

    private static int shift(int coordinate, boolean active, int delta) {
      return active ? coordinate + delta : coordinate;
    }

    private static long key(int blockX, int blockY, int blockZ) {
      return ((long) blockX << 40) ^ ((long) blockZ << 20) ^ (blockY & 0xFFFFFL);
    }
  }
}
