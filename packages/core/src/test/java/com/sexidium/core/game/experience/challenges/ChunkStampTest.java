package com.sexidium.core.game.experience.challenges;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Omni-Chunk geometry: the chunk-local slot that means "the same place in every chunk", and the
 * centre-outwards order the surrounding chunks are visited in. The history itself is {@code ChunkLedgerTest};
 * which blocks may be touched is {@code BlockGuardTest}.
 */
class ChunkStampTest {

  @Test
  void negativeCoordinatesMapToTheSameSlotAsTheirPositiveTwin() {
    // x=-1 is the last column of chunk -1, i.e. local 15 — the classic sign-handling trap.
    assertEquals(15, ChunkStamp.localOf(-1));
    assertEquals(-1, ChunkStamp.chunkOf(-1));
    assertEquals(0, ChunkStamp.localOf(-16));
    assertEquals(-1, ChunkStamp.chunkOf(-16));
    assertEquals(15, ChunkStamp.localOf(15));
    assertEquals(0, ChunkStamp.chunkOf(15));
  }

  @Test
  void theRippleExpandsOutwardsFromTheEdit() {
    int[][] offsets = ChunkStamp.offsets(2);
    assertEquals(25, offsets.length, "a radius of 2 is a 5x5 chunk area");
    assertArrayEqualsPair(new int[] {0, 0}, offsets[0], "the edit's own chunk comes first");
    // Ring order: everything at distance 1 must come before anything at distance 2.
    int lastRing = 0;
    for (int[] offset : offsets) {
      int ring = Math.max(Math.abs(offset[0]), Math.abs(offset[1]));
      assertTrue(ring >= lastRing, "offsets must never step back towards the centre");
      lastRing = ring;
    }
    assertEquals(2, lastRing);
    assertEquals(1, ChunkStamp.offsets(0).length, "radius 0 is just the edit's own chunk");
  }

  @Test
  void theSameSlotResolvesIntoAnyChunk() {
    // x=37 is local 5 of chunk 2; z=-20 is local 12 of chunk -2. The pair is what "the same place in
    // every chunk" means, and it must survive being resolved back out into far-apart chunks.
    assertEquals(5, ChunkStamp.localOf(37));
    assertEquals(2, ChunkStamp.chunkOf(37));
    assertEquals(12, ChunkStamp.localOf(-20));
    assertEquals(-2, ChunkStamp.chunkOf(-20));
  }

  private static void assertArrayEqualsPair(int[] expected, int[] actual, String message) {
    assertEquals(expected[0], actual[0], message);
    assertEquals(expected[1], actual[1], message);
  }
}
