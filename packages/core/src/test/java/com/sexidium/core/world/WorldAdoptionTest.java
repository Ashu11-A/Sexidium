package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldTerrain;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the reason the pool exists: starting an experience must NOT generate a world when a warm one of
 * the right shape is ready. Generating one is a multi-second stall on the server thread felt by every
 * player online, and an experience needs three worlds, so it used to be three stalls back to back.
 */
class WorldAdoptionTest {
  @TempDir
  Path tempDir;

  @Test
  void aNewExperienceIsServedFromThePoolInsteadOfGeneratingAWorld() {
    FakeControl control = control();
    control.start();
    assertTrue(control.generated.size() >= 3, "boot warms the pool: " + control.generated);
    int generatedAtBoot = control.generated.size();

    WorldLease lease = acquireExperience(control, "diamond_hunt_ab12");

    assertNotNull(lease, "the experience must start");
    assertEquals(1, control.adopted.size(), "it must be served by adopting a warm world");
    assertEquals("experiences/diamond_hunt_ab12", control.adopted.get(0));
    // The only extra generation is the pool replacing what it handed over — off the critical path.
    assertEquals(generatedAtBoot + 1, control.generated.size(),
        "starting must not generate a world for the player to wait on");
  }

  @Test
  void anExistingExperienceLoadsItsOwnWorldAndIsNeverHandedAFreshOne() {
    FakeControl control = control();
    control.start();
    control.onDisk.add("experiences/diamond_hunt_ab12");

    WorldLease lease = acquireExperience(control, "diamond_hunt_ab12");

    assertNotNull(lease);
    assertTrue(control.adopted.isEmpty(), "a world that already exists must be LOADED, never replaced");
  }

  @Test
  void anEmptyPoolFallsBackToGeneratingRatherThanFailing() {
    FakeControl control = control();
    control.poolDisabled = true; // nothing warm, as on a server that has just exhausted the pool
    control.start();

    WorldLease lease = acquireExperience(control, "diamond_hunt_ab12");

    assertNotNull(lease, "a start must never fail just because nothing was warm");
    assertTrue(control.adopted.isEmpty());
    assertTrue(control.generated.contains("experiences/diamond_hunt_ab12"));
  }

  @Test
  void aWarmWorldTakenButNotAdoptedIsDisposedRatherThanLeaked() {
    FakeControl control = control();
    control.adoptionWorks = false;
    control.start();

    WorldLease lease = acquireExperience(control, "diamond_hunt_ab12");

    assertNotNull(lease, "the start still succeeds by generating");
    assertTrue(control.generated.contains("experiences/diamond_hunt_ab12"));
    assertFalse(control.unloaded.isEmpty(), "the warm world it could not use must be disposed of");
  }

  @Test
  void aWarmWorldIsNeverAlsoReclaimedByTheGarbageCollector() {
    FakeControl control = control();
    control.start();
    int warm = control.generated.size();

    int reclaimed = control.collectGarbage(List.of());

    assertEquals(0, reclaimed, "the GC must not eat the pool it is meant to protect");
    assertEquals(warm, control.generated.size());
  }

  /**
   * The pool is borrowable by ANY subsystem, not just the world layer's own adoption path — and a borrow
   * immediately starts generating a replacement, which is what keeps the cost off the critical path.
   */
  @Test
  void borrowingByProfileIsPublicAndTriggersAReplacement() {
    FakeControl control = control();
    control.start();
    int generatedAtBoot = control.generated.size();

    Optional<WorldLease> nether = control.acquireReady(WorldProfile.NETHER);

    assertTrue(nether.isPresent(), "a warm Nether must be borrowable by profile");
    assertEquals(generatedAtBoot + 1, control.generated.size(),
        "taking one must immediately start replacing it");
  }

  /** A shape the pool does not warm is simply not available; the caller generates as normal. */
  @Test
  void anUnwarmedShapeIsNeverServedFromThePool() {
    FakeControl control = control();
    control.start();

    assertTrue(control.acquireReady(
        new WorldProfile(WorldDimension.OVERWORLD, false, WorldTerrain.AMPLIFIED)).isEmpty());
  }

  private WorldLease acquireExperience(FakeControl control, String worldName) {
    AtomicReference<WorldLease> result = new AtomicReference<>();
    control.acquireOrCreatePersistent(
        com.sexidium.core.world.WorldKey.parse(worldName), List.of(), WorldGeneration.DEFAULT,
        result::set, () -> { });
    return result.get();
  }

  private FakeControl control() {
    return new FakeControl(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("test"), tempDir);
  }

  /** A control whose backend records what it was asked to do instead of touching a server. */
  private static final class FakeControl extends AbstractWorldControl {
    private final Path home;
    final List<String> generated = new ArrayList<>();
    final List<String> adopted = new ArrayList<>();
    final List<String> unloaded = new ArrayList<>();
    final Set<String> onDisk = new HashSet<>();
    final List<WorldHandle> live = new ArrayList<>();
    boolean adoptionWorks = true;
    boolean poolDisabled;

    private FakeControl(ConfigurationAdapter configuration, LoggerAdapter logger, Path home) {
      super(configuration, logger);
      this.home = home;
    }

    @Override protected void runOnWorldThread(Runnable task) {
      task.run();
    }

    @Override protected Path serverHome() {
      return home;
    }

    @Override protected Path experiencesDiskRoot() {
      return home.resolve("experiences");
    }

    @Override protected Path lobbyDiskFolder() {
      return home.resolve("lobby");
    }

    @Override protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean createIfMissing) {
      if (poolDisabled && request.kind() == WorldKind.TEMP) {
        return Optional.empty(); // the pool cannot be filled on this "server"
      }
      if (!createIfMissing && !onDisk.contains(request.runtimeName())) {
        return Optional.empty();
      }
      if (createIfMissing) {
        generated.add(request.runtimeName());
      }
      FakeHandle handle = new FakeHandle(request.runtimeName(), request.kind(),
          home.resolve(request.runtimeName().replace('/', '_')));
      live.add(handle);
      return Optional.of(handle);
    }

    @Override protected boolean backendExistsOnDisk(WorldRequest request) {
      return onDisk.contains(request.runtimeName());
    }

    @Override protected Optional<WorldHandle> backendAdopt(WorldHandle pooled, WorldRequest request) {
      if (!adoptionWorks) {
        return Optional.empty();
      }
      live.remove(pooled);
      adopted.add(request.runtimeName());
      onDisk.add(request.runtimeName());
      FakeHandle handle = new FakeHandle(request.runtimeName(), request.kind(),
          home.resolve(request.runtimeName().replace('/', '_')));
      live.add(handle);
      return Optional.of(handle);
    }

    @Override protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
      for (WorldHandle handle : live) {
        if (WorldNaming.sameWorld(handle.runtimeName(), runtimeName)) {
          return Optional.of(handle);
        }
      }
      return Optional.empty();
    }

    @Override protected boolean backendUnload(WorldHandle handle, boolean save) {
      unloaded.add(handle.runtimeName());
      live.remove(handle);
      return true;
    }

    @Override protected Optional<WorldHandle> backendLobby() {
      return Optional.empty();
    }

    @Override protected List<WorldHandle> backendLoadedTempWorlds() {
      List<WorldHandle> temps = new ArrayList<>();
      for (WorldHandle handle : live) {
        if (handle.kind() == WorldKind.TEMP) {
          temps.add(handle);
        }
      }
      return temps;
    }

    @Override protected Path backendTempDiskRoot() {
      return home.resolve("temp");
    }
  }

  private record FakeHandle(String runtimeName, WorldKind kind, Path canonicalFolder) implements WorldHandle {
    @Override public WorldAdapter adapter() {
      return new FakeWorldAdapter(runtimeName);
    }
  }

  private record FakeWorldAdapter(String name) implements WorldAdapter {
    @Override public WorldPosition spawnPosition() {
      return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f);
    }

    @Override public List<PlayerAdapter> players() {
      return List.of();
    }

    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {
    }

    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {
    }

    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {
    }

    @Override public void resetBorder() {
    }

    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {
    }
  }
}
