package com.sexidium.paper.adapter.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperScheduledTaskTest {

  @Test
  void cancel_invokesHandleCancel() {
    io.papermc.paper.threadedregions.scheduler.ScheduledTask handle =
        mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
    when(handle.isCancelled()).thenReturn(false);
    PaperScheduledTask task = new PaperScheduledTask(handle);
    task.cancel();
    verify(handle).cancel();
  }

  @Test
  void cancel_skipsWhenAlreadyCancelled() {
    io.papermc.paper.threadedregions.scheduler.ScheduledTask handle =
        mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
    when(handle.isCancelled()).thenReturn(true);
    PaperScheduledTask task = new PaperScheduledTask(handle);
    task.cancel();
    verify(handle, never()).cancel();
  }

  @Test
  void cancel_withNullHandle_doesNotThrow() {
    PaperScheduledTask task = new PaperScheduledTask(null);
    assertDoesNotThrow(task::cancel);
  }
}
