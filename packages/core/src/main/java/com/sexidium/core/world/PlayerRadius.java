package com.sexidium.core.world;

import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single source of truth for "how far around a player does this mechanic reach". Every effect that
 * follows a player — the Omni-Chunk replication and replay, the Break-One-Break-All sweep, the ground-item
 * consolidation sweep — used to derive its own reach from {@link PlayerAdapter#viewDistanceChunks()} with a
 * slightly different formula, so they disagreed about where the world ends. They now all resolve an
 * {@link Area} from here.
 *
 * <h2>The rules, once</h2>
 * <ul>
 *   <li><b>The player's own render distance</b> is the basis, so a mechanic covers exactly what that
 *       player can see and spends nothing on what they cannot. Paper reports the CLIENT's real render
 *       distance, so a player on a low video setting genuinely costs less.</li>
 *   <li><b>Overscan</b> ({@link #DEFAULT_OVERSCAN}, 10%) extends the reach slightly past the visible
 *       edge, so the effect is already correct in the chunk a player is about to walk into instead of
 *       resolving in front of their eyes.</li>
 *   <li><b>A circle, not a square.</b> Reach is a radius in blocks; a chunk on the boundary is included
 *       only when <b>more than {@link #COVERAGE_THRESHOLD} of its area</b> actually falls inside that
 *       circle. Half-covered chunks at the corners are what made the old square reach feel arbitrary — a
 *       chunk barely clipped by the edge cost as much to process as one directly under the player.</li>
 *   <li><b>Nearest first.</b> Offsets are ordered by distance from the centre, so budgeted work ripples
 *       outwards and the chunks the player is actually looking at resolve first.</li>
 * </ul>
 *
 * <p>Pure, deterministic and cached per (radius, overscan), so the offset table for a given render
 * distance is computed once for the whole server. Unit-tested in {@code PlayerRadiusTest}.</p>
 */
public final class PlayerRadius {
  /** How far past the visible edge an effect reaches, as a fraction of the view distance. */
  public static final double DEFAULT_OVERSCAN = 0.10;
  /** A boundary chunk is included only when strictly more than this fraction of it is inside the circle. */
  public static final double COVERAGE_THRESHOLD = 0.5;
  /** Fallback view distance when the platform cannot report one. */
  public static final int FALLBACK_VIEW_DISTANCE = 10;

  private static final int CHUNK_SIZE = 16;
  /** Sub-samples per chunk axis when measuring coverage: 8×8 = 64 samples, ~1.6% resolution. */
  private static final int COVERAGE_SAMPLES = 8;

  private static final Map<String, int[][]> OFFSETS = new HashMap<>();

  private PlayerRadius() {
  }

  /**
   * A resolved working area around a player.
   *
   * @param chunkRadius  how many chunks out the area reaches (after overscan, rounded up)
   * @param blockRadius  the same reach in blocks — the circle the coverage rule is measured against
   * @param chunkOffsets chunk offsets from the player's chunk, nearest first, boundary chunks filtered
   *                     by the coverage rule. Index 0 is always the player's own chunk.
   */
  public record Area(int chunkRadius, double blockRadius, int[][] chunkOffsets) {
    /** How many chunks this area actually covers. */
    public int chunkCount() {
      return chunkOffsets.length;
    }
  }

  /**
   * The area around {@code player}, from their render distance clamped into {@code [minChunks, maxChunks]}
   * and extended by {@link #DEFAULT_OVERSCAN}.
   */
  public static Area around(PlayerAdapter player, int minChunks, int maxChunks) {
    return around(player, minChunks, maxChunks, DEFAULT_OVERSCAN);
  }

  /** As {@link #around(PlayerAdapter, int, int)} with an explicit overscan fraction. */
  public static Area around(PlayerAdapter player, int minChunks, int maxChunks, double overscan) {
    return of(viewDistanceOf(player), minChunks, maxChunks, overscan);
  }

  /**
   * The area for an explicit view distance — the testable core of {@link #around}, and what a caller with
   * a view distance from somewhere other than a player should use.
   */
  public static Area of(int viewDistanceChunks, int minChunks, int maxChunks, double overscan) {
    int low = Math.max(0, minChunks);
    int high = Math.max(low, maxChunks);
    int clamped = Math.max(low, Math.min(high, Math.max(0, viewDistanceChunks)));
    double safeOverscan = Math.max(0.0, overscan);
    // The circle is what the coverage rule measures against; the chunk radius is just enough whole chunks
    // to contain it, since a chunk further out than that cannot possibly be half-covered.
    double blockRadius = clamped * CHUNK_SIZE * (1.0 + safeOverscan);
    int chunkRadius = (int) Math.ceil(blockRadius / CHUNK_SIZE);
    return new Area(chunkRadius, blockRadius, offsets(chunkRadius, blockRadius));
  }

  /** The player's render distance, or {@link #FALLBACK_VIEW_DISTANCE} when it cannot be reported. */
  public static int viewDistanceOf(PlayerAdapter player) {
    int reported = player == null ? 0 : player.viewDistanceChunks();
    return reported > 0 ? reported : FALLBACK_VIEW_DISTANCE;
  }

  /**
   * The reach in BLOCKS for a player, for the mechanics that work in blocks rather than chunks (sweeps,
   * item scans). Same basis and same overscan as {@link #around}, so they agree on where the edge is.
   */
  public static double blockRadius(PlayerAdapter player, int minChunks, int maxChunks) {
    return around(player, minChunks, maxChunks).blockRadius();
  }

  /**
   * Chunk offsets inside {@code blockRadius}, nearest first. A chunk is kept only when more than
   * {@link #COVERAGE_THRESHOLD} of its area lies inside the circle — the player's own chunk is always
   * kept, since a mechanic must never skip the ground under their feet.
   */
  private static int[][] offsets(int chunkRadius, double blockRadius) {
    String key = chunkRadius + "@" + Math.round(blockRadius * 100.0);
    int[][] cached = OFFSETS.get(key);
    if (cached != null) {
      return cached;
    }
    List<int[]> kept = new ArrayList<>();
    for (int offsetX = -chunkRadius; offsetX <= chunkRadius; offsetX++) {
      for (int offsetZ = -chunkRadius; offsetZ <= chunkRadius; offsetZ++) {
        boolean center = offsetX == 0 && offsetZ == 0;
        if (center || coverage(offsetX, offsetZ, blockRadius) > COVERAGE_THRESHOLD) {
          kept.add(new int[] {offsetX, offsetZ});
        }
      }
    }
    // Nearest first, so budgeted work resolves what the player is looking at before the fringe. The
    // tie-break keeps the order deterministic (and therefore the cache and the tests stable).
    kept.sort(Comparator
        .comparingLong((int[] offset) -> (long) offset[0] * offset[0] + (long) offset[1] * offset[1])
        .thenComparingInt(offset -> offset[0])
        .thenComparingInt(offset -> offset[1]));
    int[][] result = kept.toArray(new int[0][]);
    OFFSETS.put(key, result);
    return result;
  }

  /**
   * What fraction of the chunk at {@code (offsetX, offsetZ)} lies within {@code blockRadius} of the centre
   * of the player's chunk. Measured by sub-sampling the chunk on an
   * {@link #COVERAGE_SAMPLES}² grid — exact enough to decide a >50% rule, and far simpler to reason about
   * (and to test) than an analytic circle/square intersection.
   *
   * <p>The circle is centred on the player's CHUNK centre rather than their exact position, which is what
   * lets one offset table serve every player at that render distance instead of being recomputed as they
   * walk.</p>
   */
  static double coverage(int offsetX, int offsetZ, double blockRadius) {
    if (blockRadius <= 0.0) {
      return 0.0;
    }
    double radiusSquared = blockRadius * blockRadius;
    double step = (double) CHUNK_SIZE / COVERAGE_SAMPLES;
    // The chunk's span relative to the centre of the player's chunk.
    double originX = offsetX * CHUNK_SIZE - CHUNK_SIZE / 2.0;
    double originZ = offsetZ * CHUNK_SIZE - CHUNK_SIZE / 2.0;
    int inside = 0;
    for (int sampleX = 0; sampleX < COVERAGE_SAMPLES; sampleX++) {
      double x = originX + (sampleX + 0.5) * step;
      for (int sampleZ = 0; sampleZ < COVERAGE_SAMPLES; sampleZ++) {
        double z = originZ + (sampleZ + 0.5) * step;
        if (x * x + z * z <= radiusSquared) {
          inside++;
        }
      }
    }
    return inside / (double) (COVERAGE_SAMPLES * COVERAGE_SAMPLES);
  }
}
