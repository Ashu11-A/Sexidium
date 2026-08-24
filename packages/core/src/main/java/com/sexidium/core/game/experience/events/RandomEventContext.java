package com.sexidium.core.game.experience.events;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;

import java.util.List;
import java.util.Random;

/**
 * The environment a {@link RandomEvent} acts on when it fires: the players it should affect, the world
 * they are in, a source of randomness, and a broadcast channel for the event's flavour message. Kept as a
 * small interface (no dependency on any challenge internals) so the {@link RandomEventEngine} can be
 * driven by ANY challenge — or a test harness — the same way.
 */
public interface RandomEventContext {
  /** Players the fired event should affect (usually every online participant). Never null. */
  List<PlayerAdapter> players();

  /** The world the event plays out in, or null when unavailable (e.g. a headless test). */
  WorldAdapter world();

  /** The randomness source the event should use, so a seeded run is reproducible. Never null. */
  Random random();

  /** Broadcasts a MiniMessage line to the participants (the event's "what just happened" announcement). */
  void announce(String miniMessage);
}
