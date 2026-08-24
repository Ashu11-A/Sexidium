package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.SchedulerAdapter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DirectSchedulerAdapter implements SchedulerAdapter {
  private final ExecutorService executorService = Executors.newCachedThreadPool(runnable -> {
    Thread workerThread = new Thread(runnable, "Sexidium-Direct-Async");
    workerThread.setDaemon(true);
    return workerThread;
  });

  @Override
  public ScheduledTask runNow(Runnable runnable) {
    if (runnable != null) {
      runnable.run();
    }
    return NoopScheduledTask.INSTANCE;
  }

  @Override
  public ScheduledTask runLater(Runnable runnable, long delayTicks) {
    if (runnable != null) {
      runnable.run();
    }
    return NoopScheduledTask.INSTANCE;
  }

  @Override
  public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    return NoopScheduledTask.INSTANCE;
  }

  @Override
  public void runAsync(Runnable runnable) {
    if (runnable != null) {
      executorService.execute(runnable);
    }
  }
}
