package com.sexidium.paper.adapter.scheduler;

import com.sexidium.core.platform.ScheduledTask;

/**
 * Envolve um agendamento nativo do Paper (GlobalRegion/Async). Exposto
 * atraves da interface de plataforma {@link ScheduledTask} para que o
 * core consiga cancelar tarefas de forma padronizada.
 */
public final class PaperScheduledTask implements ScheduledTask {
  private final io.papermc.paper.threadedregions.scheduler.ScheduledTask handle;

  public PaperScheduledTask(io.papermc.paper.threadedregions.scheduler.ScheduledTask handle) {
    this.handle = handle;
  }

  @Override
  public void cancel() {
    if (handle != null && !handle.isCancelled()) {
      handle.cancel();
    }
  }
}
