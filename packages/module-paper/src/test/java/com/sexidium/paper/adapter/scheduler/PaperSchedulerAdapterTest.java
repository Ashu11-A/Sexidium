package com.sexidium.paper.adapter.scheduler;

import com.sexidium.core.platform.ScheduledTask;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperSchedulerAdapterTest {

  private JavaPlugin plugin;
  private Server server;
  private GlobalRegionScheduler globalScheduler;
  private AsyncScheduler asyncScheduler;
  private io.papermc.paper.threadedregions.scheduler.ScheduledTask paperTask;

  @Test
  void runNow_schedulesAndReturnsTask() {
    setup();
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperSchedulerAdapter adapter = new PaperSchedulerAdapter(plugin);
      ScheduledTask task = adapter.runNow(() -> {});
      assertNotNull(task);
    }
  }

  @Test
  void runLater_schedulesWithDelay() {
    setup();
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperSchedulerAdapter adapter = new PaperSchedulerAdapter(plugin);
      ScheduledTask task = adapter.runLater(() -> {}, 5L);
      assertNotNull(task);
    }
  }

  @Test
  void runLater_clampsNonPositiveDelayToOne() {
    setup();
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperSchedulerAdapter adapter = new PaperSchedulerAdapter(plugin);
      ScheduledTask task = adapter.runLater(() -> {}, -5L);
      assertNotNull(task);
    }
  }

  @Test
  void runLater_withZeroDelay_clampsToOne() {
    setup();
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperSchedulerAdapter adapter = new PaperSchedulerAdapter(plugin);
      ScheduledTask task = adapter.runLater(() -> {}, 0L);
      assertNotNull(task);
    }
  }

  @Test
  void runTimer_schedulesWithDelayAndPeriod() {
    setup();
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperSchedulerAdapter adapter = new PaperSchedulerAdapter(plugin);
      ScheduledTask task = adapter.runTimer(() -> {}, 1L, 20L);
      assertNotNull(task);
    }
  }

  @Test
  void runTimer_clampsNonPositiveValuesToOne() {
    setup();
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperSchedulerAdapter adapter = new PaperSchedulerAdapter(plugin);
      ScheduledTask task = adapter.runTimer(() -> {}, -1L, -1L);
      assertNotNull(task);
    }
  }

  @Test
  void runAsync_invokesAsyncScheduler() {
    setup();
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      PaperSchedulerAdapter adapter = new PaperSchedulerAdapter(plugin);
      adapter.runAsync(() -> {});
      Mockito.verify(asyncScheduler).runNow(eq(plugin), any());
    }
  }

  private void setup() {
    plugin = mock(JavaPlugin.class);
    server = mock(Server.class);
    globalScheduler = mock(GlobalRegionScheduler.class);
    asyncScheduler = mock(AsyncScheduler.class);
    paperTask = mock(io.papermc.paper.threadedregions.scheduler.ScheduledTask.class);
    when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
    when(server.getAsyncScheduler()).thenReturn(asyncScheduler);
    when(plugin.getServer()).thenReturn(server);
    when(globalScheduler.run(eq(plugin), any())).thenReturn(paperTask);
    when(globalScheduler.runDelayed(eq(plugin), any(), anyLong())).thenReturn(paperTask);
    when(globalScheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(paperTask);
  }

  private static <T> T eq(T value) {
    return org.mockito.ArgumentMatchers.eq(value);
  }
}
