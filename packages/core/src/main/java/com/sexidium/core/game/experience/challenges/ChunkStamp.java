package com.sexidium.core.game.experience.challenges;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pure geometry behind Omni Chunk: converting a world coordinate into the chunk-local slot that
 * identifies "the same place in every chunk", and the order in which surrounding chunks are visited.
 *
 * <p>Host-free and deterministic, so it is unit-tested without a world ({@code ChunkStampTest}). The
 * history of what was done to those slots lives in {@link ChunkLedger}; the challenge class owns the
 * lifecycle and the actual world calls.</p>
 */
final class ChunkStamp {
  /** Chunk-offset rings by radius, ordered centre-outwards. Cached: the same radius is reused constantly. */
  private static final Map<Integer, int[][]> OFFSETS = new HashMap<>();

  private ChunkStamp() {
  }

  /** The chunk-local coordinate of a world coordinate (correct for negative coordinates too). */
  static int localOf(int blockCoordinate) {
    return blockCoordinate & 15;
  }

  /** The chunk coordinate containing a world coordinate. */
  static int chunkOf(int blockCoordinate) {
    return blockCoordinate >> 4;
  }

  /**
   * Chunk offsets within {@code radius}, ordered by ring (Chebyshev distance) so replication expands
   * outwards from the edit rather than sweeping in from a corner. Index 0 is always the edit's own chunk,
   * which the caller skips — it already holds the real block.
   */
  static int[][] offsets(int radius) {
    int safeRadius = Math.max(0, radius);
    int[][] cached = OFFSETS.get(safeRadius);
    if (cached != null) {
      return cached;
    }
    List<int[]> ordered = new ArrayList<>();
    for (int ring = 0; ring <= safeRadius; ring++) {
      for (int offsetX = -ring; offsetX <= ring; offsetX++) {
        for (int offsetZ = -ring; offsetZ <= ring; offsetZ++) {
          if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) == ring) {
            ordered.add(new int[] {offsetX, offsetZ});
          }
        }
      }
    }
    int[][] result = ordered.toArray(new int[0][]);
    OFFSETS.put(safeRadius, result);
    return result;
  }
}
