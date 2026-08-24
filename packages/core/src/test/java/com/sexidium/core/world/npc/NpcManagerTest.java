package com.sexidium.core.world.npc;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.NpcAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.NoopCommandDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopEventDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopUiAdapter;
import com.sexidium.core.platform.noop.NoopWorldLeaseService;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NpcManagerTest {
  @TempDir
  Path tempDir;

  @Test
  void startLoadsConfiguredNpcsOnFirstScheduledTick() throws IOException {
    saveNpc("guide");
    RecordingScheduler scheduler = new RecordingScheduler();
    RecordingNpcAdapter npcs = new RecordingNpcAdapter();
    NpcManager manager = new NpcManager(new TestServer(tempDir, scheduler, npcs), null, null);

    manager.start();

    assertTrue(npcs.spawned.isEmpty());
    scheduler.runNext();

    assertEquals(List.of("guide"), npcs.spawned);
    assertEquals(1, npcs.despawnAllCalls);
  }

  @Test
  void stopCancelsPendingStartupLoad() throws IOException {
    saveNpc("guide");
    RecordingScheduler scheduler = new RecordingScheduler();
    RecordingNpcAdapter npcs = new RecordingNpcAdapter();
    NpcManager manager = new NpcManager(new TestServer(tempDir, scheduler, npcs), null, null);

    manager.start();
    manager.stop();
    scheduler.runNext();

    assertTrue(npcs.spawned.isEmpty());
    assertEquals(1, npcs.despawnAllCalls);
  }

  @Test
  void firstPlayerJoinRespawnsOnceAfterStartupRace() throws IOException {
    saveNpc("guide");
    RecordingScheduler scheduler = new RecordingScheduler();
    RecordingNpcAdapter npcs = new RecordingNpcAdapter();
    NpcManager manager = new NpcManager(new TestServer(tempDir, scheduler, npcs), null, null);

    manager.start();
    scheduler.runNext(); // boot-time spawn (may have lost the race against the NPC backend)

    manager.onPlayerJoin();
    assertEquals(List.of("guide", "guide"), npcs.spawned);
    assertEquals(2, npcs.despawnAllCalls);

    // Subsequent joins do not re-spawn.
    manager.onPlayerJoin();
    assertEquals(List.of("guide", "guide"), npcs.spawned);
  }

  private void saveNpc(String id) throws IOException {
    NpcDefinitionStore.save(tempDir.resolve("fakeplayers"), new NpcDefinition(id, "lobby", 1.0D, 2.0D,
        3.0D, 90.0F, 0.0F, "", "Guide", "", false, List.of("<green>Guide</green>"), ""));
  }

  private static final class RecordingNpcAdapter implements NpcAdapter {
    private final List<String> spawned = new ArrayList<>();
    private int despawnAllCalls;

    @Override
    public void spawn(NpcDefinition definition, Consumer<NpcInteraction> onClick) {
      spawned.add(definition.id());
    }

    @Override
    public void despawn(String npcId) {
    }

    @Override
    public void despawnAll() {
      despawnAllCalls++;
    }

    @Override
    public void updateHologram(String npcId, List<String> renderedLines) {
    }
  }

  private static final class RecordingScheduler implements SchedulerAdapter {
    private final Queue<PendingTask> laterTasks = new ArrayDeque<>();

    @Override
    public ScheduledTask runNow(Runnable runnable) {
      if (runnable != null) {
        runnable.run();
      }
      return () -> { };
    }

    @Override
    public ScheduledTask runLater(Runnable runnable, long delayTicks) {
      PendingTask task = new PendingTask(runnable);
      laterTasks.add(task);
      return task;
    }

    @Override
    public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
      return new PendingTask(runnable);
    }

    @Override
    public void runAsync(Runnable runnable) {
      if (runnable != null) {
        runnable.run();
      }
    }

    void runNext() {
      PendingTask task = laterTasks.poll();
      if (task != null) {
        task.run();
      }
    }
  }

  private static final class PendingTask implements ScheduledTask {
    private final Runnable runnable;
    private boolean cancelled;

    private PendingTask(Runnable runnable) {
      this.runnable = runnable;
    }

    @Override
    public void cancel() {
      cancelled = true;
    }

    void run() {
      if (!cancelled && runnable != null) {
        runnable.run();
      }
    }
  }

  private static final class TestServer implements ServerAdapter {
    private static final MessageAdapter NOOP_MESSAGES = new MessageAdapter() {
      @Override
      public void send(CommandSource source, LocalizedText text) {
      }

      @Override
      public void send(CommandSource source, String message) {
      }

      @Override
      public void raw(CommandSource source, LocalizedText text) {
      }

      @Override
      public void raw(CommandSource source, String message) {
      }

      @Override
      public void broadcast(LocalizedText text) {
      }

      @Override
      public void broadcast(String message) {
      }
    };

    private final Path dataDirectory;
    private final SchedulerAdapter scheduler;
    private final NpcAdapter npcs;
    private final ConfigurationAdapter configuration = new PropertiesConfigurationAdapter();

    private TestServer(Path dataDirectory, SchedulerAdapter scheduler, NpcAdapter npcs) {
      this.dataDirectory = dataDirectory;
      this.scheduler = scheduler;
      this.npcs = npcs;
    }

    @Override
    public String serverName() {
      return "Test";
    }

    @Override
    public PlatformType platformType() {
      return PlatformType.UNKNOWN;
    }

    @Override
    public Path dataDirectory() {
      return dataDirectory;
    }

    @Override
    public ConfigurationAdapter configuration() {
      return configuration;
    }

    @Override
    public LoggerAdapter logger() {
      return new StdoutLoggerAdapter("NpcManagerTest");
    }

    @Override
    public ResourceAdapter resources() {
      return new ClassLoaderResourceAdapter(null);
    }

    @Override
    public SchedulerAdapter scheduler() {
      return scheduler;
    }

    @Override
    public UiAdapter ui() {
      return new NoopUiAdapter();
    }

    @Override
    public MessageAdapter messages() {
      return NOOP_MESSAGES;
    }

    @Override
    public EventDispatcherAdapter events() {
      return new NoopEventDispatcherAdapter();
    }

    @Override
    public CommandDispatcherAdapter commands() {
      return new NoopCommandDispatcherAdapter();
    }

    @Override
    public WorldLeaseService worlds() {
      return new NoopWorldLeaseService();
    }

    @Override
    public CommandSource console() {
      return null;
    }

    @Override
    public Collection<PlayerAdapter> onlinePlayers() {
      return List.of();
    }

    @Override
    public Optional<PlayerAdapter> player(UUID playerId) {
      return Optional.empty();
    }

    @Override
    public Optional<PlayerAdapter> playerExact(String playerName) {
      return Optional.empty();
    }

    @Override
    public NpcAdapter npcs() {
      return npcs;
    }
  }
}
