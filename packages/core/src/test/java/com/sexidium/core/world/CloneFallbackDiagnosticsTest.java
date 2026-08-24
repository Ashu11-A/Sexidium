package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The silent-degradation guard: when a map template cannot be cloned, {@code acquireOrCreateClone} still
 * starts the match on a generated world (so the server does not deadlock), but it must now say so at
 * SEVERE and warn the players being sent there. Before this, the fallback produced zero output and the
 * only symptom was an unfinishable TNT-War match on random terrain.
 */
class CloneFallbackDiagnosticsTest {
  @Test
  void degradedCloneLogsSevereNamingTheTemplateAndWarnsPlayers(@TempDir Path root) throws IOException {
    // The map folder is simply not there: the "never provisioned / moved aside by a refresh" case.
    RecordingLogger logger = new RecordingLogger();
    FakeControl control = new FakeControl(logger, root);
    CapturingPlayer player = new CapturingPlayer();

    control.acquireOrCreateClone("tntwar/tnt-wars", List.of(player), lease -> {
    }, () -> {
    });

    String severe = logger.severe.get();
    assertFalse(severe == null, "the silent vanilla fallback must log at SEVERE");
    assertTrue(severe.contains("tntwar/tnt-wars"), "the log must name the template: " + severe);
    assertTrue(severe.contains("TEMPLATE_MISSING"), "the log must state the real cause: " + severe);
    assertTrue(severe.contains("GENERATED world"), "the log must say the match is degraded: " + severe);
    assertTrue(player.actionBar.get() != null && player.actionBar.get().contains("tntwar/tnt-wars"),
        "players sent into the broken match must be told");
  }

  @Test
  void partialTemplateDegradesLoudlyInsteadOfCloningBlind(@TempDir Path root) throws IOException {
    // Half-extracted map: folder + region/ exist but hold no chunk file. This is the case that used to
    // clone "successfully" and hand the mode a world with no arena at the map's coordinates.
    Files.createDirectories(root.resolve("worlds/tntwar/tnt-wars/region"));
    RecordingLogger logger = new RecordingLogger();
    FakeControl control = new FakeControl(logger, root);

    control.acquireOrCreateClone("tntwar/tnt-wars", List.of(), lease -> {
    }, () -> {
    });

    String severe = logger.severe.get();
    assertFalse(severe == null, "a partial template must not degrade in silence");
    assertTrue(severe.contains("EMPTY_REGION_DATA"), "the log must name the partial-extract cause: " + severe);
  }

  @Test
  void completeTemplateThatFailedToCopyIsReportedAsAConcurrentRewriteNotAMissingMap(@TempDir Path root)
      throws IOException {
    // The folder inspects clean AFTER the failure => the copy itself was refused mid-flight. The old
    // message ("no region data found") sent whoever read it hunting for a missing map instead.
    Path template = root.resolve("worlds/tntwar/tnt-wars/region");
    Files.createDirectories(template);
    Files.writeString(template.resolve("r.0.0.mca"), "chunks", StandardCharsets.UTF_8);
    RecordingLogger logger = new RecordingLogger();
    FakeControl control = new FakeControl(logger, root);

    control.acquireOrCreateClone("tntwar/tnt-wars", List.of(), lease -> {
    }, () -> {
    });

    String severe = logger.severe.get();
    assertFalse(severe == null);
    assertTrue(severe.contains("rewriting it"), "the message must point at the concurrent writer: " + severe);
    assertFalse(severe.contains("no region data found"), "the misleading wording must be gone: " + severe);
  }

  @Test
  void aWorkingCloneStaysQuiet(@TempDir Path root) {
    RecordingLogger logger = new RecordingLogger();
    FakeControl control = new FakeControl(logger, root);
    control.cloneWorks = true;
    AtomicReference<WorldLease> served = new AtomicReference<>();

    control.acquireOrCreateClone("tntwar/tnt-wars", List.of(), served::set, () -> {
    });

    assertFalse(served.get() == null, "the normal path must still serve the clone");
    assertEquals(null, logger.severe.get(), "a healthy clone must not log at SEVERE");
  }

  // ===== fakes ================================================================================

  private static final class RecordingLogger implements LoggerAdapter {
    private final AtomicReference<String> severe = new AtomicReference<>();

    @Override public void info(String message) {}
    @Override public void warning(String message) {}
    @Override public void severe(String message) { severe.set(message); }
    @Override public void warning(String message, Throwable throwable) {}
    @Override public void severe(String message, Throwable throwable) { severe.set(message); }
  }

  /** A control whose backend refuses CLONE requests (the failure under test) but can create temp worlds. */
  private static final class FakeControl extends AbstractWorldControl {
    private final Path serverHome;
    private boolean cloneWorks;

    private FakeControl(LoggerAdapter logger, Path serverHome) {
      super(configuration(), logger);
      this.serverHome = serverHome;
    }

    private static ConfigurationAdapter configuration() {
      return new PropertiesConfigurationAdapter();
    }

    @Override protected void runOnWorldThread(Runnable task) { task.run(); }

    @Override protected Path serverHome() { return serverHome; }

    @Override protected Path experiencesDiskRoot() { return serverHome.resolve("experiences"); }

    @Override protected Path lobbyDiskFolder() { return serverHome.resolve("lobby"); }

    @Override
    protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean createIfMissing) {
      if (request.kind() == WorldKind.CLONE && !cloneWorks) {
        return Optional.empty();
      }
      return Optional.of(new FakeHandle(request.runtimeName(), serverHome.resolve(request.runtimeName())));
    }

    @Override
    protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
      return Optional.empty();
    }

    @Override protected boolean backendUnload(WorldHandle handle, boolean save) { return true; }

    @Override protected Optional<WorldHandle> backendLobby() { return Optional.empty(); }

    @Override protected List<WorldHandle> backendLoadedTempWorlds() { return new ArrayList<>(); }

    @Override protected Path backendTempDiskRoot() { return serverHome.resolve("temp"); }
  }

  private record FakeHandle(String runtimeName, Path folder) implements WorldHandle {
    @Override public WorldKind kind() { return WorldKind.TEMP; }

    @Override public WorldAdapter adapter() { return new FakeWorld(runtimeName); }

    @Override public Path canonicalFolder() { return folder; }
  }

  private record FakeWorld(String name) implements WorldAdapter {
    @Override public WorldPosition spawnPosition() { return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f); }

    @Override public List<PlayerAdapter> players() { return List.of(); }

    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}

    @Override public void playSound(WorldPosition target, SoundKey soundKey, float volume, float pitch) {}

    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}

    @Override public void resetBorder() {}

    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }

  private static final class CapturingPlayer implements PlayerAdapter {
    private final AtomicReference<String> actionBar = new AtomicReference<>();

    @Override public UUID uniqueId() { return UUID.nameUUIDFromBytes("viewer".getBytes(StandardCharsets.UTF_8)); }
    @Override public String name() { return "Viewer"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return false; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) { actionBar.set(miniMessage); }
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
  }
}
