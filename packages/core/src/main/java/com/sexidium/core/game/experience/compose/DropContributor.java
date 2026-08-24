package com.sexidium.core.game.experience.compose;

/**
 * A challenge's contribution to the shared {@link DropPipeline}. Registered via
 * {@link ChallengeRegistry#dropContributor(DropContributor)} during {@code Challenge.register}.
 * Contributors run grouped by {@link #phase()} then by {@link #order()} (ascending), so layering is
 * deterministic: a Randomizer remap ({@link DropPhase#GENERATE}) precedes a Double Drops multiply
 * ({@link DropPhase#TRANSFORM}).
 */
public interface DropContributor {
  DropPhase phase();

  /** Tie-break within a phase; lower runs first. */
  default int order() {
    return 0;
  }

  void contribute(DropContext context);
}
