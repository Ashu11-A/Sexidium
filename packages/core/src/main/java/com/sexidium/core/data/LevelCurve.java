package com.sexidium.core.data;

import com.sexidium.core.platform.ConfigurationAdapter;

/**
 * The points &rarr; level curve. Per-level cost is an arithmetic progression
 * ({@code base}, {@code base+growth}, {@code base+2*growth}, ...), so the CUMULATIVE cost is quadratic:
 *
 * <pre>
 *   costOfLevel(n)    = base + growth*(n-1)
 *   pointsForLevel(L) = base*L + growth*L*(L-1)/2
 * </pre>
 *
 * <p>Early levels are cheaper than the old flat {@code points-per-level} and late levels are far more
 * expensive; reaching level 80 (Sigma) costs ~3.2x what the flat curve did. {@code L*(L-1)} is always
 * even, so the halving is exact integer arithmetic — no rounding creeps into the totals.</p>
 *
 * <p>{@code growth == 0} reproduces the legacy flat curve exactly ({@code level = points / base}), which
 * is the only supported way back for an operator who does not want the rework.</p>
 */
public record LevelCurve(int base, int growth) {

  public static final int DEFAULT_BASE = 40;
  public static final int DEFAULT_GROWTH = 7;
  /** Legacy flat-curve key, honoured only when growth is explicitly 0. */
  public static final int LEGACY_POINTS_PER_LEVEL = 100;
  /** Hard ceiling; keeps pointsForLevel inside a long and levelFor's correction loops bounded. */
  public static final int MAX_LEVEL = 10_000;

  public LevelCurve {
    // A base of 0 would make the flat branch divide by zero and the quadratic branch hand out infinite
    // levels for a single point; a negative growth would make the curve non-monotonic, which every
    // caller (RankClass.forLevel, the XP bar) silently assumes it is not.
    base = Math.max(1, base);
    growth = Math.max(0, growth);
  }

  public static LevelCurve defaults() {
    return new LevelCurve(DEFAULT_BASE, DEFAULT_GROWTH);
  }

  /**
   * Reads the curve from configuration. {@code ranks.level-curve.growth: 0} opts back into the legacy
   * flat curve, and ONLY then does {@code base} default to the deprecated {@code ranks.points-per-level}.
   *
   * <p>That asymmetry is deliberate: a stock pre-rework config carries {@code points-per-level: 100} and
   * no {@code level-curve} block at all. Letting it seed {@code base} unconditionally would give such a
   * server a curve starting at 100 and climbing by 7 — neither the old behaviour nor the new one.</p>
   */
  public static LevelCurve from(ConfigurationAdapter configuration) {
    if (configuration == null) {
      return defaults();
    }
    int growth = Math.max(0, configuration.getInt("ranks.level-curve.growth", DEFAULT_GROWTH));
    int base = configuration.getInt("ranks.level-curve.base",
        growth == 0
            ? configuration.getInt("ranks.points-per-level", LEGACY_POINTS_PER_LEVEL)
            : DEFAULT_BASE);
    return new LevelCurve(base, growth);
  }

  /** Cumulative points needed to BE this level. {@code pointsForLevel(0) == 0}. */
  public long pointsForLevel(int level) {
    if (level <= 0) {
      return 0L;
    }
    long steps = Math.min(level, MAX_LEVEL);
    // Everything is widened to long BEFORE multiplying: growth*steps*steps leaves int range around
    // level 24 750, and an int overflow here would make the curve fold back on itself.
    return (long) base * steps + (long) growth * steps * (steps - 1) / 2L;
  }

  /** Points the step from {@code level} to {@code level + 1} costs. */
  public long costOfLevel(int level) {
    return (long) base + (long) growth * Math.max(0, level);
  }

  /** The highest level whose cumulative cost the points cover. Integer-exact. */
  public int levelFor(int points) {
    if (points <= 0) {
      return 0;
    }
    int level = growth == 0
        ? (int) Math.min(MAX_LEVEL, (long) points / base)
        : quadraticEstimate(points);
    // The closed form is a double; correct it against the exact integer cumulative cost. Both loops
    // move at most one step in practice, so this stays O(1) on the HUD's per-tick, per-player path —
    // and it is what makes levelFor the EXACT inverse of pointsForLevel at every boundary, which a
    // bare Math.sqrt is not (it lands on 79.99999999 for the points that are exactly level 80).
    while (level < MAX_LEVEL && pointsForLevel(level + 1) <= points) {
      level++;
    }
    while (level > 0 && pointsForLevel(level) > points) {
      level--;
    }
    return level;
  }

  /** Progress 0..1 from the player's current level toward the next — the vanilla XP bar's green fill. */
  public float progress(int points) {
    int level = levelFor(points);
    long floor = pointsForLevel(level);
    long ceiling = pointsForLevel(level + 1);
    if (ceiling <= floor) {
      // Only reachable at MAX_LEVEL, where there is no next level to progress toward.
      return 0f;
    }
    float fraction = (points - floor) / (float) (ceiling - floor);
    return Math.max(0f, Math.min(1f, fraction));
  }

  /**
   * Solves {@code growth*L^2 + (2*base - growth)*L - 2*points <= 0} for L. Never called when
   * {@code growth == 0} (the formula divides by growth). Only an ESTIMATE — {@link #levelFor} corrects it.
   */
  private int quadraticEstimate(int points) {
    double linear = 2.0 * base - growth;
    double root = Math.sqrt(linear * linear + 8.0 * (double) growth * (double) points);
    double level = (root - linear) / (2.0 * growth);
    return (int) Math.max(0, Math.min(MAX_LEVEL, Math.floor(level)));
  }
}
