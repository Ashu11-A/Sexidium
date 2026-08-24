package com.sexidium.paper.adapter.scheduler;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.paper.adapter.world.PaperWorldAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Adaptador de agendamento para Paper. Utiliza o {@code GlobalRegionScheduler}
 * para tarefas na thread principal (substituindo o antigo {@code BukkitScheduler})
 * e o {@code AsyncScheduler} para trabalho paralelo pesado. Ambos os schedulers
 * sao Folia-safe, oferecendo melhor protecao ao TPS.
 */
public final class PaperSchedulerAdapter implements SchedulerAdapter {
  private final JavaPlugin plugin;

  public PaperSchedulerAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public ScheduledTask runNow(Runnable runnable) {
    return new PaperScheduledTask(
        Bukkit.getServer().getGlobalRegionScheduler().run(plugin, ignored -> runnable.run())
    );
  }

  @Override
  public ScheduledTask runLater(Runnable runnable, long delayTicks) {
    return new PaperScheduledTask(
        Bukkit.getServer().getGlobalRegionScheduler().runDelayed(plugin, ignored -> runnable.run(), Math.max(1L, delayTicks))
    );
  }

  @Override
  public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    return new PaperScheduledTask(
        Bukkit.getServer().getGlobalRegionScheduler().runAtFixedRate(
            plugin,
            ignored -> runnable.run(),
            Math.max(1L, delayTicks),
            Math.max(1L, periodTicks)
        )
    );
  }

  @Override
  public void runAsync(Runnable runnable) {
    Bukkit.getServer().getAsyncScheduler().runNow(plugin, ignored -> runnable.run());
  }

  @Override
  public boolean isRegionThreaded() {
    return FoliaSupport.isFolia();
  }

  @Override
  public ScheduledTask runForPlayer(PlayerAdapter player, Runnable task, Runnable retired) {
    if (player instanceof PaperPlayerAdapter paperPlayer) {
      // EntityScheduler: runs on the region owning the player, following them across regions. Returns
      // null (→ a no-op ScheduledTask) if the player retired before it could run.
      return new PaperScheduledTask(
          paperPlayer.handle().getScheduler().run(plugin, ignored -> task.run(), retired));
    }
    return runNow(task);
  }

  @Override
  public ScheduledTask runForPlayerLater(PlayerAdapter player, Runnable task, Runnable retired, long delayTicks) {
    if (player instanceof PaperPlayerAdapter paperPlayer) {
      return new PaperScheduledTask(paperPlayer.handle().getScheduler()
          .runDelayed(plugin, ignored -> task.run(), retired, Math.max(1L, delayTicks)));
    }
    return runLater(task, delayTicks);
  }

  @Override
  public ScheduledTask runAtRegion(WorldAdapter world, int blockX, int blockZ, Runnable task) {
    if (world instanceof PaperWorldAdapter paperWorld) {
      // RegionScheduler: runs on the region owning the chunk at (blockX, blockZ) in that world.
      return new PaperScheduledTask(Bukkit.getServer().getRegionScheduler()
          .run(plugin, paperWorld.handle(), blockX >> 4, blockZ >> 4, ignored -> task.run()));
    }
    return runNow(task);
  }
}
