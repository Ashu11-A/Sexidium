package com.sexidium.core.game.experience.compose;

/**
 * A capability published by the XP Health challenge exposing its (start-time) configuration so a
 * sibling can run XP-as-health on its behalf. When Shared Life is also active it fetches this via
 * {@link ChallengeContext#service(Class)} and turns its pool into a single, group-shared XP bar that
 * doubles as everyone's life — instead of the two challenges showing two separate numbers and both
 * draining the same hit. XP Health itself stands down (no per-player drain/seed/HUD) while a
 * {@link SharedHealthPool} is present.
 *
 * <p>The values are snapshotted in {@code register()} (before any {@code onStart}), so a sibling's
 * {@code onStart} reads populated config regardless of challenge ordering.</p>
 */
public interface XpHealthModel {
  /** Full/refill XP for the shared pool (the pool's maximum, in XP points). */
  int maxXp();

  /** XP burned per point of incoming damage. */
  double xpPerDamagePoint();

  /** Pool value at or below which the group soft-respawns and the pool refills. */
  int deathThresholdXp();

  /** Whether falling into the void drains the whole pool at once. */
  boolean voidConsumesAllXp();

  /** Heart-display scale to mimic the standalone XP-as-health look. */
  double displayHealthScale();

  /** Whether the heart display should be scaled at all. */
  boolean scaleHeartDisplay();
}
