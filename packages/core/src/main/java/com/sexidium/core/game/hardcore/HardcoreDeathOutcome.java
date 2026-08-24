package com.sexidium.core.game.hardcore;

/**
 * What a death costs in a hardcore world.
 *
 * <p>Hardcore used to mean exactly one thing here — the world is gone — and that single meaning was
 * written into the experience host rather than into anything a mode could choose. It is a choice now,
 * because "one death and it is over" and "one death and it starts again" are the same stakes with the
 * same machinery behind them, and only the last step differs.</p>
 */
public enum HardcoreDeathOutcome {
  /**
   * The default, and what every hardcore experience did before this existed: the world is marked dead
   * in the registry, one way, and from then on it can only be spectated.
   */
  LOSE_WORLD,

  /**
   * The world is thrown away and a new one takes its place. Nothing is recorded as dead — the run
   * continues, so a registry that said otherwise would lock the owner out of their own experience.
   * The regeneration itself is the mode's business; the rule only makes sure nothing else pre-empts it.
   */
  RESET_WORLD
}
