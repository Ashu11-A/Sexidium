package com.sexidium.core.game.experience.compose;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Counts how long an experience has been PLAYED, as opposed to how long it has existed.
 *
 * <h2>The rule</h2>
 * One second is accrued for every second at least one participant is inside, and none at all while the
 * experience is empty. An experience is open-ended and long-lived — its world sits on disk between
 * visits, sometimes for weeks — so wall-clock age says nothing about it. A run that has been played for
 * three hours has been played for three hours whether that took an afternoon or a fortnight.
 *
 * <p>The total is <b>wall clock while occupied</b>, not the sum of the per-player figures: three players
 * together for ten minutes is ten minutes of run, and thirty minutes of player time. Both are kept,
 * because they answer different questions and neither can be derived from the other.</p>
 *
 * <h2>Why it buffers instead of writing straight through</h2>
 * Its output lands in the experience's shared state, which is a file inside the world folder. Two things
 * follow. A write every second would mean a file write every second for the life of the run, for a
 * number nobody reads that often. And during a world regeneration the folder being written to is on its
 * way out — so seconds accrued across the swap have to be held until the new state is installed, or they
 * are written into a directory that is about to be deleted. Buffering solves both: the caller drains on
 * its own schedule, and simply does not drain while a reset is in flight.
 *
 * <p>Pure and host-free (no scheduler, no state, no player adapters) so the arithmetic is testable
 * without a server, in the manner of {@code DeathResetsClock}.</p>
 */
public final class OccupancyLedger {
  private long pendingSeconds;
  private final Map<UUID, Long> pendingPlayerSeconds = new LinkedHashMap<>();

  /**
   * Accrues one second against the run and against each player present for it.
   *
   * <p>An empty roster accrues NOTHING — not a zero-valued entry, not a tick of the total. That is the
   * whole pause rule, and it lives here rather than in the caller so it cannot be forgotten by a second
   * caller later.</p>
   *
   * @param present the participants currently inside; null or empty pauses the clock
   */
  public void tick(Collection<UUID> present) {
    if (present == null || present.isEmpty()) {
      return;
    }
    boolean counted = false;
    for (UUID playerId : present) {
      if (playerId == null) {
        continue;
      }
      pendingPlayerSeconds.merge(playerId, 1L, Long::sum);
      counted = true;
    }
    // Only once, and only if somebody real was there: the total is occupied wall-clock, not player-time.
    if (counted) {
      pendingSeconds++;
    }
  }

  /** Whether anything is waiting to be committed, so a caller can skip an empty drain entirely. */
  public boolean pending() {
    return pendingSeconds > 0 || !pendingPlayerSeconds.isEmpty();
  }

  /**
   * The buffered run seconds, WITHOUT taking them.
   *
   * <p>For anything that displays played time. The committed total only moves when the caller drains,
   * which is deliberately infrequent — a write lands in a file — so a readout built on the committed
   * figure alone advances in jumps of however long the commit window is, however often it repaints.
   * Adding this to it gives a per-second figure at no extra write.</p>
   */
  public long peekSeconds() {
    return pendingSeconds;
  }

  /** The buffered seconds for one player, WITHOUT taking them. See {@link #peekSeconds()}. */
  public long peekSeconds(UUID playerId) {
    return playerId == null ? 0L : pendingPlayerSeconds.getOrDefault(playerId, 0L);
  }

  /** Takes the buffered run seconds and clears them. */
  public long drainSeconds() {
    long drained = pendingSeconds;
    pendingSeconds = 0L;
    return drained;
  }

  /** Takes the buffered per-player seconds and clears them. */
  public Map<UUID, Long> drainPlayerSeconds() {
    Map<UUID, Long> drained = new LinkedHashMap<>(pendingPlayerSeconds);
    pendingPlayerSeconds.clear();
    return drained;
  }
}
