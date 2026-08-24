package com.sexidium.core.game.experience.compose;

/**
 * A capability published by the Double Drops challenge so siblings can read the live drop multiplier
 * (e.g. a status row, or a challenge that wants to scale its own rewards in step). Fetched via
 * {@link ChallengeContext#service(Class)}.
 */
public interface DropMultiplierService {
  int currentMultiplier();
}
