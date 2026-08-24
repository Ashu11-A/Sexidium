package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.ItemStackData;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regenerating an experience's world — which Death Resets does on every death, by building the
 * replacement ALONGSIDE the world it replaces rather than on top of it.
 *
 * <p>The two cases that matter most are both failure-shaped, and both come from the bug this design
 * replaced: a world that could not be unloaded must never have its folder deleted (that leaves a loaded
 * ghost the acquire path will hand back as "fresh"), and a name left behind by a crashed teardown must
 * never be reused.</p>
 */
class WorldResetTest {
  /** The canonical key. There is exactly one spelling of a world now; see {@code WorldKeyTest}. */
  private static final WorldKey KEY = WorldKey.parse("diamond_hunt_ab12cd34");
  /** ...and the runtime name derived from it, which is what the backend records. */
  private static final String WORLD = "experiences/diamond_hunt_ab12cd34";

  @TempDir
  Path tempDir;

  @Test
  void aRegenerationGetsTheNextGenerationName() {
    FakeControl control = control();
    control.start();
    assertNotNull(acquire(control, KEY));

    assertEquals(KEY.nextGeneration(), control.nextExperienceGeneration(KEY));
  }

  // The four "experienceIdentityKey" tests that used to live here are gone with the method. They
  // asserted that nine different spellings of one world collapse to one identity — a guarantee that
  // only had to exist because nine spellings existed. WorldKeyTest reproduces every one of them
  // (including the separations: two maps with different 8-hex ids, and a map whose own name ends in
  // "_r") against the single spelling that replaced them.

  /**
   * The placement layer must be asked about the CANONICAL key, never the caller's spelling.
   *
   * <p>Three layers named the same world three ways: the launcher by owner, the reset by namespace,
   * and the disk by neither. Each spelling was written into world_placements verbatim, so one world
   * held rows on two nodes and the boot scan matched neither — which is how a live save came to be
   * reported as "no world folder is on this disk".</p>
   */
  @Test
  void thePlacementGateIsAskedByCanonicalKey() {
    FakeControl control = control();
    control.start();
    java.util.List<String> asked = new java.util.ArrayList<>();
    control.setPlacementGate(key -> {
      asked.add(key);
      return com.sexidium.core.world.WorldPlacementGate.Decision.allow();
    });

    acquire(control, WorldKey.parse("death_resets_aa280b96"));

    assertEquals(java.util.List.of("death_resets_aa280b96"), asked,
        "the gate is asked about the canonical key and nothing else");
  }

  /**
   * The successor stays in its predecessor's key space — the same requirement, now structural.
   *
   * <p>It used to be a string problem: the launcher named a world by owner, the successor came back
   * namespaced, the two were different key spaces, the placement lookup missed, the planner homed the
   * successor on another node and the reset failed with "the new world could not be prepared" while
   * the run sat on this disk. A {@link WorldKey} cannot express that mistake: a successor is the same
   * base at the next generation, and {@code sameRun} is how anything asks.</p>
   */
  @Test
  void theSuccessorStaysInItsPredecessorsKeySpace() {
    FakeControl control = control();
    control.start();
    WorldKey predecessor = WorldKey.parse("death_resets_aa280b96");

    WorldKey successor = control.nextExperienceGeneration(predecessor);

    assertTrue(predecessor.sameRun(successor), "a regeneration is the same run, one generation on");
    assertEquals("death_resets_aa280b96_r1", successor.key());
  }

  /** The experience's 8-hex id can never look like a generation marker, so a first reset is always _r1. */
  @Test
  void generationsCountUpFromWhicheverOneWeAreOn() {
    FakeControl control = control();
    control.start();

    assertEquals("diamond_hunt_ab12cd34_r1", control.nextExperienceGeneration(KEY).key());
    assertEquals("diamond_hunt_ab12cd34_r4",
        control.nextExperienceGeneration(WorldKey.parse("diamond_hunt_ab12cd34_r3")).key());
  }

  /**
   * The whole point of the redesign: the new world exists while the old one is still loaded and still has
   * players in it. If these two could not coexist, everybody would have to be moved out first — which is
   * exactly the sequence that broke.
   */
  @Test
  void theNewWorldIsServedFromThePoolWhileTheOldOneIsStillAlive() {
    FakeControl control = control();
    control.start();
    assertNotNull(acquire(control, KEY));
    control.adopted.clear();

    WorldKey next = control.nextExperienceGeneration(KEY);
    WorldLease fresh = acquire(control, next);

    assertNotNull(fresh);
    assertEquals(next.runtimeName(), fresh.world().name());
    assertEquals(List.of(next.runtimeName()), control.adopted,
        "the replacement is a folder move, not a generation");
    assertTrue(control.onDisk.contains(WORLD), "the old world is untouched until everyone has left it");
    assertTrue(control.backendResolveLoaded(WORLD, WorldKind.PERSISTENT).isPresent(),
        "…and is still loaded, with its players in it");
  }

  /** A folder left behind by a teardown that crashed must be skipped, never moved on top of. */
  @Test
  void aGenerationNameLeftByACrashedTeardownIsSkipped() {
    FakeControl control = control();
    control.start();
    control.onDisk.add(WORLD + "_r1");

    assertEquals("diamond_hunt_ab12cd34_r2", control.nextExperienceGeneration(KEY).key());
  }

  /** A backend whose experiences carry linked dimensions claims those names too. */
  @Test
  void aNameWhoseLinkedDimensionIsTakenIsAlsoSkipped() {
    FakeControl control = control();
    control.siblingSuffixes = List.of("_nether", "_end");
    control.start();
    control.onDisk.add(WORLD + "_r1_nether");

    assertEquals("diamond_hunt_ab12cd34_r2", control.nextExperienceGeneration(KEY).key());
  }

  /**
   * The bug that produced the original failure, pinned. Deleting the folder of a world that is still
   * loaded leaves a ghost: loaded, playable-looking, backed by nothing — and the acquire path's
   * already-loaded branch hands it straight back as a freshly created world.
   */
  @Test
  void aWorldThatCouldNotBeUnloadedIsLeftCompletelyAlone() {
    FakeControl control = control();
    control.unloadWorks = false;
    control.start();
    assertNotNull(acquire(control, KEY));

    assertFalse(control.deletePersistent(KEY), "the caller must be told the world is still there");
    assertTrue(control.onDisk.contains(WORLD), "and its folder must not have been removed");
    assertTrue(control.backendResolveLoaded(WORLD, WorldKind.PERSISTENT).isPresent());
  }

  @Test
  void deletingAWorldThatUnloadedCleanlyReportsSuccess() {
    FakeControl control = control();
    control.start();
    assertNotNull(acquire(control, KEY));

    assertTrue(control.deletePersistent(KEY));
    assertFalse(control.onDisk.contains(WORLD));
  }

  @Test
  void anEmptyPoolStillProducesAWorld_justBySlowlyGeneratingOne() {
    FakeControl control = control();
    control.poolDisabled = true; // a server that has exhausted its warm worlds
    control.start();

    WorldLease fresh = acquire(control, KEY.nextGeneration());

    assertNotNull(fresh, "a regeneration must never fail merely because nothing was warm");
    assertTrue(control.adopted.isEmpty());
    assertTrue(control.generated.contains(WORLD + "_r1"), "it falls back to real generation");
  }

  /**
   * The point of the whole feature: a regenerated world must be a DIFFERENT world, not the same terrain
   * built again. That rests on one invariant — a persistent world request never carries a seed, so the
   * platform picks a fresh random one. If a future change ever derived the seed from the experience's
   * name or id (tempting, for reproducibility), every "new" world would be identical to the last.
   */
  @Test
  void aRegeneratedWorldIsNeverSeededFromItsOwnName() {
    FakeControl control = control();
    control.poolDisabled = true; // force the generation path, where the seed is chosen
    control.start();
    control.requestedSeeds.clear();

    assertNotNull(acquire(control, KEY.nextGeneration()));

    assertFalse(control.requestedSeeds.isEmpty(), "the regeneration must have generated a world");
    for (Long seed : control.requestedSeeds) {
      assertNull(seed, "a persistent world must ask for a RANDOM seed, never one derived from its name");
    }
  }

  @Test
  void aNullKeyIsRefusedRatherThanActedOn() {
    FakeControl control = control();
    control.start();

    assertNull(control.nextExperienceGeneration(null));
    assertFalse(control.deletePersistent(null));
    assertTrue(control.unloaded.isEmpty());
  }

  private WorldLease acquire(FakeControl control, WorldKey key) {
    AtomicReference<WorldLease> result = new AtomicReference<>();
    control.acquireOrCreatePersistent(key, List.of(), WorldGeneration.DEFAULT, result::set, () -> { });
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
    /** Seeds asked for on the generation path; null means "platform, pick a random one". */
    final List<Long> requestedSeeds = new ArrayList<>();
    final List<WorldHandle> live = new ArrayList<>();
    boolean poolDisabled;
    /** False simulates a platform refusing to unload an occupied world — the case the guard is for. */
    boolean unloadWorks = true;
    List<String> siblingSuffixes = List.of();

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

    @Override protected List<String> siblingKeySuffixes() {
      return siblingSuffixes;
    }

    @Override protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean createIfMissing) {
      if (poolDisabled && request.kind() == WorldKind.TEMP) {
        return Optional.empty();
      }
      if (!createIfMissing && !onDisk.contains(request.runtimeName())) {
        return Optional.empty();
      }
      if (createIfMissing) {
        generated.add(request.runtimeName());
        onDisk.add(request.runtimeName());
        if (request.kind() == WorldKind.PERSISTENT) {
          requestedSeeds.add(request.seed());
        }
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
      if (!unloadWorks) {
        return false;
      }
      unloaded.add(handle.runtimeName());
      live.remove(handle);
      onDisk.remove(handle.runtimeName());
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
