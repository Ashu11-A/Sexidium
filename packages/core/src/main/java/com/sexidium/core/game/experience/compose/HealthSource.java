package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.PlayerAdapter;

import java.util.OptionalDouble;

/**
 * A challenge's contribution to a player's displayed/effective health, merged by {@link HealthModel}.
 * Registered via {@link ChallengeRegistry#healthSource(HealthSource)}. The model applies the
 * highest-{@link #priority()} present {@link #value(PlayerAdapter)} and {@link #scale(PlayerAdapter)}
 * exactly once per write, so Shared Life (pool value) and XP Health (heart scale) stop fighting over
 * {@code setHealth} every tick.
 */
public interface HealthSource {
  /** Higher wins when several sources offer a value/scale for the same player. */
  int priority();

  /** Target health for this player, if this source governs it. */
  default OptionalDouble value(PlayerAdapter player) {
    return OptionalDouble.empty();
  }

  /** Heart-display scale for this player, if this source governs it. */
  default OptionalDouble scale(PlayerAdapter player) {
    return OptionalDouble.empty();
  }
}
