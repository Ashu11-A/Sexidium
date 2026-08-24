package com.sexidium.velocity.adapter;

import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.SchedulerAdapter;
import com.velocitypowered.api.proxy.ProxyServer;

import java.time.Duration;

/**
 * Core's scheduler contract over Velocity's.
 *
 * <p>Core speaks in TICKS because it was written for a Minecraft server; Velocity has no tick loop
 * and schedules in wall time. 50ms per tick is the conversion, which is exact for anything core
 * schedules on the proxy (heartbeats, sweeps) and meaningless for anything gameplay-shaped -- none of
 * which runs here.</p>
 */
public final class VelocitySchedulerAdapter implements SchedulerAdapter {

  private static final long MILLIS_PER_TICK = 50L;

  private final ProxyServer proxy;
  private final Object plugin;

  public VelocitySchedulerAdapter(ProxyServer proxy, Object plugin) {
    this.proxy = proxy;
    this.plugin = plugin;
  }

  private static ScheduledTask wrap(com.velocitypowered.api.scheduler.ScheduledTask task) {
    return new ScheduledTask() {
      @Override
      public void cancel() {
        task.cancel();
      }
    };
  }

  @Override
  public ScheduledTask runNow(Runnable runnable) {
    return wrap(proxy.getScheduler().buildTask(plugin, runnable).schedule());
  }

  @Override
  public ScheduledTask runLater(Runnable runnable, long delayTicks) {
    return wrap(proxy.getScheduler()
        .buildTask(plugin, runnable)
        .delay(Duration.ofMillis(Math.max(0L, delayTicks) * MILLIS_PER_TICK))
        .schedule());
  }

  @Override
  public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    return wrap(proxy.getScheduler()
        .buildTask(plugin, runnable)
        .delay(Duration.ofMillis(Math.max(0L, delayTicks) * MILLIS_PER_TICK))
        .repeat(Duration.ofMillis(Math.max(1L, periodTicks) * MILLIS_PER_TICK))
        .schedule());
  }

  @Override
  public void runAsync(Runnable runnable) {
    // Every Velocity task already runs off the netty event loop, so "async" is the only mode.
    proxy.getScheduler().buildTask(plugin, runnable).schedule();
  }
}
