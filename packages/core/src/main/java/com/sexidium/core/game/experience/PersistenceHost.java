package com.sexidium.core.game.experience;

import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;

/**
 * The minimal surface {@link ExperiencePersistence} needs from its owning game: the match world and a
 * delayed scheduler for the debounced state flush. Both {@link ExperienceGame} and the persistent
 * {@code ChaosGame} implement it, so the same per-player inventory/position persistence is reused across
 * every open-ended, save-on-leave mode without coupling the store to one concrete game.
 */
public interface PersistenceHost {
  WorldAdapter world();

  ScheduledTask runLater(Runnable runnable, long delayTicks);
}
