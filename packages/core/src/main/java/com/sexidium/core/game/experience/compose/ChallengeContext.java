package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ExperienceHost;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;
import java.util.Optional;

/**
 * The surface every {@link Challenge} holds. Extends {@link ExperienceHost} (participants, world,
 * scheduler, shared persistent state) with the composition layer: the shared pipelines, a typed
 * capability registry, and sibling-challenge discovery — so a challenge can use another's Java
 * methods and objects instead of smuggling strings through {@code ExperienceState}.
 */
public interface ChallengeContext extends ExperienceHost {
  // ----- sibling discovery ---------------------------------------------------------------------

  List<Challenge> challenges();

  Optional<Challenge> challenge(String id);

  <C extends Challenge> Optional<C> challenge(Class<C> type);

  // ----- typed capability registry -------------------------------------------------------------

  <T> void publish(Class<T> type, T implementation);

  <T> Optional<T> service(Class<T> type);

  // ----- shared pipelines ----------------------------------------------------------------------

  DropPipeline drops();

  BlockBreakService blocks();

  DamagePipeline damage();

  HealthModel health();

  MobRegistry mobs();

  /** Shared, persistent stat counters (deaths, blocks broken, …) for HUD display. */
  ExperienceStats stats();

  /**
   * Played time including whatever has accrued since the last commit.
   *
   * <p>Separate from {@code stats().runSeconds()} because that one is the PERSISTED figure, and it is
   * written on a slow cadence on purpose: its home is a file in the world folder, and a write per
   * second for the life of a run is a poor trade for a number that is only ever read on screen. A
   * readout built on the persisted figure therefore advances in jumps the length of the commit window
   * no matter how often it repaints — which is exactly what it looks like when a "seconds played"
   * counter ticks five at a time.</p>
   *
   * <p>Defaults to the persisted figure, which is correct for a host that does not buffer.</p>
   */
  default long liveRunSeconds() {
    return stats().runSeconds();
  }

  /** The same, for one player. See {@link #liveRunSeconds()}. */
  default long liveRunSeconds(java.util.UUID playerId) {
    return stats().runSeconds(playerId);
  }


  /**
   * Every player taking part in the wider mode, regardless of per-player challenge scoping. In a plain
   * experience this equals {@link #online()}. In the per-player Chaos mode a {@code PlayerScope} narrows
   * {@code online()} to a single owner but keeps {@code modePopulation()} the full roster, so a
   * challenge that needs a mode-wide aggregate (e.g. Shared Life's "average health of everyone") can
   * still see all players. Default: the online participants.
   */
  default List<PlayerAdapter> modePopulation() {
    return online();
  }
}
