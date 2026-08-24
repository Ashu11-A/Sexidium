package com.sexidium.core.platform.noop;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DirectSchedulerAdapterTest {

  @Test
  void runNow_executesImmediately() {
    AtomicBoolean ran = new AtomicBoolean();
    new DirectSchedulerAdapter().runNow(() -> ran.set(true));
    assertTrue(ran.get());
  }

  @Test
  void runNow_withNull_doesNotThrow() {
    assertDoesNotThrow(() -> new DirectSchedulerAdapter().runNow(null));
  }

  @Test
  void runNow_returnsNoopTask() {
    assertSame(NoopScheduledTask.INSTANCE, new DirectSchedulerAdapter().runNow(() -> {}));
  }

  @Test
  void runLater_executesImmediately() {
    AtomicBoolean ran = new AtomicBoolean();
    new DirectSchedulerAdapter().runLater(() -> ran.set(true), 100L);
    assertTrue(ran.get());
  }

  @Test
  void runLater_withNull_doesNotThrow() {
    assertDoesNotThrow(() -> new DirectSchedulerAdapter().runLater(null, 10L));
  }

  @Test
  void runLater_returnsNoopTask() {
    assertSame(NoopScheduledTask.INSTANCE, new DirectSchedulerAdapter().runLater(() -> {}, 1L));
  }

  @Test
  void runTimer_returnsNoopTask_withoutRunning() {
    AtomicBoolean ran = new AtomicBoolean();
    var task = new DirectSchedulerAdapter().runTimer(() -> ran.set(true), 0L, 20L);
    assertSame(NoopScheduledTask.INSTANCE, task);
    assertFalse(ran.get());
  }

  @Test
  void runAsync_executesOnBackground() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    new DirectSchedulerAdapter().runAsync(latch::countDown);
    assertTrue(latch.await(5, TimeUnit.SECONDS));
  }

  @Test
  void runAsync_withNull_doesNotThrow() {
    assertDoesNotThrow(() -> new DirectSchedulerAdapter().runAsync(null));
  }

  @Test
  void noopScheduledTask_cancel_doesNotThrow() {
    assertDoesNotThrow(() -> NoopScheduledTask.INSTANCE.cancel());
  }

  @Test
  void noopScheduledTask_isSingleton() {
    assertSame(NoopScheduledTask.INSTANCE, NoopScheduledTask.INSTANCE);
  }
}
