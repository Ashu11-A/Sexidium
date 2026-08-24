package com.sexidium.core.game.experience.challenges;

/**
 * Deterministic enumeration of the integer offsets around an origin in order of expanding Chebyshev
 * (cube) "shells": shell 0 is the origin, shell {@code r} is every offset whose largest absolute
 * coordinate equals {@code r}. Used by {@link BreakOneBreakAllChallenge} to sweep blocks outward from
 * a player like a growing ball, a fixed budget at a time, resuming exactly where it left off.
 *
 * <p>The enumeration is closed-form (no per-offset list is ever materialised), so a sweep can walk a
 * very large radius using O(1) memory and resume from any {@code (shell, index)} cursor. Within a
 * shell the surface is partitioned into three non-overlapping face groups so every offset is yielded
 * exactly once:
 * <ol>
 *   <li>the two {@code z = ±r} faces (full {@code (2r+1)×(2r+1)} squares);</li>
 *   <li>the two {@code y = ±r} faces with {@code z} in {@code [-(r-1), r-1]};</li>
 *   <li>the two {@code x = ±r} faces with {@code y, z} in {@code [-(r-1), r-1]}.</li>
 * </ol>
 */
final class SphereSweep {
  private SphereSweep() {
  }

  /** Number of distinct offsets on Chebyshev shell {@code r} ({@code 1} for r=0, else {@code 24r²+2}). */
  static int shellSize(int r) {
    return r <= 0 ? 1 : 24 * r * r + 2;
  }

  /**
   * Decodes the {@code index}-th offset of shell {@code r} into {@code out} (length ≥ 3, as
   * {@code {dx, dy, dz}}). {@code index} must be in {@code [0, shellSize(r))}. Every decoded offset has
   * {@code max(|dx|,|dy|,|dz|) == r}.
   */
  static void offsetAt(int r, int index, int[] out) {
    if (r <= 0) {
      out[0] = 0;
      out[1] = 0;
      out[2] = 0;
      return;
    }
    int side = 2 * r + 1;       // full edge length of the shell's bounding cube
    int inner = 2 * r - 1;      // edge length of the excluded interior range [-(r-1), r-1]
    int faceZCount = 2 * side * side;
    int faceYCount = 2 * side * inner;

    if (index < faceZCount) {
      // z = ±r ; x, y in [-r, r]
      int half = side * side;
      int z = index < half ? -r : r;
      int j = index % half;
      out[0] = j / side - r;
      out[1] = j % side - r;
      out[2] = z;
      return;
    }
    index -= faceZCount;
    if (index < faceYCount) {
      // y = ±r ; x in [-r, r] ; z in [-(r-1), r-1]
      int half = side * inner;
      int y = index < half ? -r : r;
      int j = index % half;
      out[0] = j / inner - r;
      out[1] = y;
      out[2] = j % inner - (r - 1);
      return;
    }
    index -= faceYCount;
    // x = ±r ; y, z in [-(r-1), r-1]
    int half = inner * inner;
    int x = index < half ? -r : r;
    int j = index % half;
    out[0] = x;
    out[1] = j / inner - (r - 1);
    out[2] = j % inner - (r - 1);
  }
}
