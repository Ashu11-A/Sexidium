package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.ScheduledTask;

public final class NoopScheduledTask implements ScheduledTask {
  public static final NoopScheduledTask INSTANCE = new NoopScheduledTask();

  private NoopScheduledTask() {
  }

  @Override
  public void cancel() {
  }
}
