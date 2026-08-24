package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.PlayerAdapter;

/**
 * A capability published by the Shared Life challenge: one health pool the whole group draws from.
 * Siblings (notably XP Health) fetch it via {@link ChallengeContext#service(Class)} to coordinate —
 * e.g. XP Health stops governing the heart bar and only converts residual damage when a pool is
 * present, instead of the two fighting over a player's health every tick.
 */
public interface SharedHealthPool {
  double current();

  double max();

  /** Whether this pool governs the given player's health bar. */
  boolean governs(PlayerAdapter player);
}
