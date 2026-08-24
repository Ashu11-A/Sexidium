package com.sexidium.core.decor;
import com.sexidium.core.decor.DecorTypes.*;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.world.npc.NpcDefinition;
import com.sexidium.core.world.npc.NpcDefinitionStore;
import com.sexidium.core.world.npc.NpcManager;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.DecorAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DecorManagerTest {
  @TempDir
  Path tempDir;

  @Test
  void minigameNpcGetsNoDecorByDefault() {
    // Default config: podiums + portals both off → the NPC shows only its FancyHolograms text nameplate.
    Harness harness = new Harness(tempDir);
    assertTrue(harness.decor.buildNpcProps(minigame("arena", "race")).isEmpty());
  }

  @Test
  void podiumsEnabledGivesSpinningItemOnly() {
    Harness harness = new Harness(tempDir);
    harness.config.set("ui.decor.podiums.enabled", true);

    List<DecorProp> props = harness.decor.buildNpcProps(minigame("arena", "race"));

    // Only the spinning item — no pedestal block (the pedestal is tied to portals, which is off).
    assertEquals(1, props.size());
    DecorProp podium = props.get(0);
    assertEquals("podium_arena", podium.id());
    assertEquals(DecorKind.ITEM_DISPLAY, podium.kind());
    assertEquals(MenuArt.modeModel("race"), podium.itemModelId());
    assertEquals(DecorPalette.baseItem("race"), podium.baseItem());
    assertTrue(podium.animation().spin());
  }

  @Test
  void portalsEnabledGivesGlowingPedestalOneBlockBelowFeet() {
    Harness harness = new Harness(tempDir);
    harness.config.set("ui.decor.portals.enabled", true);

    List<DecorProp> props = harness.decor.buildNpcProps(minigame("arena", "combat"));

    // Only the pedestal — podiums is off.
    assertEquals(1, props.size());
    DecorProp pedestal = props.get(0);
    assertEquals("pedestal_arena", pedestal.id());
    assertEquals(DecorKind.BLOCK_DISPLAY, pedestal.kind());
    // Placed one block below the NPC's feet (y=64.0) so the body stands ON it, not buried inside it.
    assertEquals(63.0D, pedestal.y());
    assertTrue(pedestal.glowing());
    assertEquals(Integer.valueOf(DecorPalette.glowArgb("combat")), pedestal.glowArgb());
  }

  @Test
  void manualNpcGetsNoDecor() {
    Harness harness = new Harness(tempDir);
    harness.config.set("ui.decor.podiums.enabled", true);
    harness.config.set("ui.decor.portals.enabled", true);
    assertTrue(harness.decor.buildNpcProps(minigame("greeter", "")).isEmpty());
  }

  @Test
  void disabledMasterSpawnsNothingButStillSweepsOnce() {
    Harness harness = new Harness(tempDir);
    harness.config.set("ui.decor.enabled", false);

    harness.decor.rebuildAndSpawn();

    assertTrue(harness.recording.spawned.isEmpty());
    assertEquals(1, harness.recording.despawnAllCalls);
  }

  @Test
  void rebuildSpawnsOnlyMinigameNpcProps() throws IOException {
    Harness harness = new Harness(tempDir);
    harness.config.set("ui.decor.podiums.enabled", true);
    harness.config.set("ui.decor.portals.enabled", true);
    save(minigame("arena", "race"));
    save(minigame("greeter", "")); // manual NPC — no podium
    harness.npcs.reloadAndSpawn();

    harness.decor.rebuildAndSpawn();

    assertEquals(List.of("podium_arena", "pedestal_arena"), harness.recording.spawned);
  }

  @Test
  void startDefersBuildToScheduledTick() throws IOException {
    Harness harness = new Harness(tempDir);
    harness.config.set("lobby.npcs.enabled", false); // take the direct deferred-build path
    harness.config.set("ui.decor.podiums.enabled", true);
    harness.config.set("ui.decor.portals.enabled", true);
    save(minigame("arena", "race"));
    harness.npcs.reloadAndSpawn();

    harness.decor.start();
    assertTrue(harness.recording.spawned.isEmpty());

    harness.scheduler.runNext();
    assertEquals(List.of("podium_arena", "pedestal_arena"), harness.recording.spawned);
  }

  @Test
  void stopCancelsPendingBuildAndDespawnsAll() throws IOException {
    Harness harness = new Harness(tempDir);
    harness.config.set("lobby.npcs.enabled", false);
    save(minigame("arena", "race"));
    harness.npcs.reloadAndSpawn();

    harness.decor.start();
    harness.decor.stop();
    harness.scheduler.runNext();

    assertTrue(harness.recording.spawned.isEmpty());
    assertEquals(1, harness.recording.despawnAllCalls);
  }

  private void save(NpcDefinition definition) throws IOException {
    NpcDefinitionStore.save(tempDir.resolve("fakeplayers"), definition);
  }

  /** A minigame-bound NPC carries a manual hologram so {@code reloadAndSpawn} never touches the (null) GameManager. */
  private static NpcDefinition minigame(String id, String mode) {
    return new NpcDefinition(id, "lobby", 10.0D, 64.0D, 20.0D, 0.0F, 0.0F, "", id, "",
        false, List.of("<green>" + id + "</green>"), mode);
  }

  /** Bundles the pieces a DecorManager test needs: a settable config, a recording decor adapter, an NpcManager. */
  private static final class Harness {
    private final PropertiesConfigurationAdapter config = new PropertiesConfigurationAdapter();
    private final RecordingScheduler scheduler = new RecordingScheduler();
    private final RecordingDecorAdapter recording = new RecordingDecorAdapter();
    private final NpcManager npcs;
    private final DecorManager decor;

    private Harness(Path dataDirectory) {
      TestServer server = new TestServer(dataDirectory, config, scheduler, recording);
      this.npcs = new NpcManager(server, null, null);
      this.decor = new DecorManager(server, npcs);
    }
  }

  private static final class RecordingDecorAdapter implements DecorAdapter {
    private final List<String> spawned = new ArrayList<>();
    private int despawnAllCalls;

    @Override
    public void spawn(DecorProp prop) {
      spawned.add(prop.id());
    }

    @Override
    public void despawn(String propId) {
    }

    @Override
    public void despawnAll() {
      despawnAllCalls++;
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
    private final ConfigurationAdapter configuration;
    private final SchedulerAdapter scheduler;
    private final DecorAdapter decor;

    private TestServer(Path dataDirectory, ConfigurationAdapter configuration, SchedulerAdapter scheduler, DecorAdapter decor) {
      this.dataDirectory = dataDirectory;
      this.configuration = configuration;
      this.scheduler = scheduler;
      this.decor = decor;
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
      return new StdoutLoggerAdapter("DecorManagerTest");
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
    public DecorAdapter decor() {
      return decor;
    }
  }
}
