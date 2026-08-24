package com.sexidium.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one place that answers "how far around a player does this reach": the render-distance basis,
 * the overscan past the visible edge, and the rule that a boundary chunk only counts when most of it is
 * actually inside the circle.
 */
class PlayerRadiusTest {

  @Test
  void theReachIsTheRenderDistancePlusTheOverscan() {
    PlayerRadius.Area area = PlayerRadius.of(10, 1, 32, PlayerRadius.DEFAULT_OVERSCAN);
    // 10 chunks = 160 blocks, +10% = 176.
    assertEquals(176.0, area.blockRadius(), 1e-9);
    assertEquals(11, area.chunkRadius(), "just enough whole chunks to contain the circle");

    // No overscan means exactly the visible edge.
    assertEquals(160.0, PlayerRadius.of(10, 1, 32, 0.0).blockRadius(), 1e-9);
  }

  @Test
  void theRenderDistanceIsClampedIntoTheConfiguredBand() {
    assertEquals(4 * 16 * 1.1, PlayerRadius.of(2, 4, 32, 0.1).blockRadius(), 1e-9, "raised to the minimum");
    assertEquals(8 * 16 * 1.1, PlayerRadius.of(64, 4, 8, 0.1).blockRadius(), 1e-9, "capped at the maximum");
    assertEquals(0.0, PlayerRadius.of(-5, 0, 32, 0.1).blockRadius(), 1e-9, "a nonsense distance is floored");
  }

  @Test
  void aBoundaryChunkIsIncludedOnlyWhenMostOfItIsInside() {
    // A chunk whose centre sits exactly on the circle is half in, half out — it must NOT be included,
    // because the rule is strictly more than half.
    double radius = 32.0; // two chunks
    assertTrue(PlayerRadius.coverage(0, 0, radius) > 0.99, "the player's own chunk is fully covered");
    assertTrue(PlayerRadius.coverage(1, 0, radius) > PlayerRadius.COVERAGE_THRESHOLD,
        "a chunk well inside is: " + PlayerRadius.coverage(1, 0, radius));
    // A chunk centred exactly ON the circle is half in, half out. The rule is strictly MORE than half, so
    // it is excluded — the tie goes to leaving it out rather than paying for a chunk nobody really sees.
    assertEquals(0.5, PlayerRadius.coverage(2, 0, radius), 1e-9, "the boundary case really is a tie");
    assertFalse(contains(PlayerRadius.of(2, 0, 32, 0.0), 2, 0), "a tie must not be included");
    // Diagonals are further away than straight lines, so the area is a circle, not a square.
    assertTrue(PlayerRadius.coverage(2, 2, radius) < PlayerRadius.coverage(2, 0, radius));
  }

  @Test
  void theAreaIsACircleNotASquare() {
    PlayerRadius.Area area = PlayerRadius.of(4, 1, 32, 0.0);
    int side = area.chunkRadius() * 2 + 1;
    assertTrue(area.chunkCount() < side * side,
        "a square would be " + (side * side) + " chunks; a circle must be fewer: " + area.chunkCount());
    // Every kept offset really is mostly inside (bar the player's own chunk, which is always kept).
    for (int[] offset : area.chunkOffsets()) {
      if (offset[0] == 0 && offset[1] == 0) {
        continue;
      }
      assertTrue(PlayerRadius.coverage(offset[0], offset[1], area.blockRadius()) > PlayerRadius.COVERAGE_THRESHOLD,
          "kept a chunk that is mostly outside: " + offset[0] + "," + offset[1]);
    }
  }

  @Test
  void thePlayersOwnChunkAlwaysComesFirst() {
    PlayerRadius.Area area = PlayerRadius.of(6, 1, 32, 0.1);
    assertEquals(0, area.chunkOffsets()[0][0]);
    assertEquals(0, area.chunkOffsets()[0][1]);
    // …and the rest are ordered nearest-first, so budgeted work resolves what is under the player's nose.
    long previous = -1;
    for (int[] offset : area.chunkOffsets()) {
      long distance = (long) offset[0] * offset[0] + (long) offset[1] * offset[1];
      assertTrue(distance >= previous, "offsets must never step back towards the centre");
      previous = distance;
    }
  }

  @Test
  void aTinyRadiusStillCoversTheGroundUnderTheirFeet() {
    // Even when the circle is smaller than one chunk, the player's own chunk must never be skipped.
    PlayerRadius.Area area = PlayerRadius.of(0, 0, 32, 0.0);
    assertEquals(1, area.chunkCount());
    assertEquals(0, area.chunkOffsets()[0][0]);
    assertFalse(PlayerRadius.coverage(0, 0, 0.0) > 0.0, "…even though nothing is technically inside");
  }

  @Test
  void theOffsetTableIsStableAcrossCalls() {
    // It is cached per radius; a caller must never be handed a table that differs run to run.
    PlayerRadius.Area first = PlayerRadius.of(8, 1, 32, 0.1);
    PlayerRadius.Area second = PlayerRadius.of(8, 1, 32, 0.1);
    assertEquals(first.chunkCount(), second.chunkCount());
    assertEquals(first.blockRadius(), second.blockRadius(), 1e-9);
    for (int index = 0; index < first.chunkCount(); index++) {
      assertEquals(first.chunkOffsets()[index][0], second.chunkOffsets()[index][0]);
      assertEquals(first.chunkOffsets()[index][1], second.chunkOffsets()[index][1]);
    }
  }

  private static boolean contains(PlayerRadius.Area area, int offsetX, int offsetZ) {
    for (int[] offset : area.chunkOffsets()) {
      if (offset[0] == offsetX && offset[1] == offsetZ) {
        return true;
      }
    }
    return false;
  }

  @Test
  void aMissingViewDistanceFallsBackRatherThanCollapsingToZero() {
    assertEquals(PlayerRadius.FALLBACK_VIEW_DISTANCE, PlayerRadius.viewDistanceOf(null));
  }
}
