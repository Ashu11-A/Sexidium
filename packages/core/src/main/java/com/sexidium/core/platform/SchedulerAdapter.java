package com.sexidium.core.platform;

/**
 * Platform scheduling seam. The four base methods cover global / world-independent work and run on the
 * server's global region under Folia (or the main thread on regular Paper / NeoForge).
 *
 * <p>The {@code runForPlayer*} / {@code runAtRegion} methods express <b>region-scoped</b> scheduling:
 * on Folia, where the world is split into independently-ticked regions, a task that touches a specific
 * player/entity or world location must run on the thread owning that region. Platforms that are not
 * region-threaded inherit the default implementations, which simply fall back to the global scheduler —
 * so existing adapters keep working unchanged while Folia-correct scheduling becomes available where it
 * matters.
 */
public interface SchedulerAdapter {
  ScheduledTask runNow(Runnable runnable);

  ScheduledTask runLater(Runnable runnable, long delayTicks);

  ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks);

  void runAsync(Runnable runnable);

  /** Whether the server splits the world into independently-ticked regions (Folia). */
  default boolean isRegionThreaded() {
    return false;
  }

  /**
   * Runs a task on the region thread that owns the given player (Folia EntityScheduler), so the task may
   * safely mutate that player / their entity. {@code retired} runs instead if the player has left before
   * the task executes (may be {@code null}). On a non-region-threaded server this is {@link #runNow}.
   */
  default ScheduledTask runForPlayer(PlayerAdapter player, Runnable task, Runnable retired) {
    return runNow(task);
  }

  /** Delayed variant of {@link #runForPlayer}. */
  default ScheduledTask runForPlayerLater(PlayerAdapter player, Runnable task, Runnable retired, long delayTicks) {
    return runLater(task, delayTicks);
  }

  /**
   * Runs a task on the region thread that owns the given world location (Folia RegionScheduler), so the
   * task may safely mutate blocks/entities there. On a non-region-threaded server this is {@link #runNow}.
   */
  default ScheduledTask runAtRegion(WorldAdapter world, int blockX, int blockZ, Runnable task) {
    return runNow(task);
  }
}
