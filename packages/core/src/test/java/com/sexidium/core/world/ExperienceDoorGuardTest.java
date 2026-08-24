package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I3: a node without {@code EXPERIENCES} may never open, create, delete or regenerate an
 * experience world.
 *
 * <p>The capability existed and guarded exactly one thing — where a world would be PLANNED. Every door
 * that actually opens one was unguarded, and the lobby (which deliberately lacks the capability) could
 * reach three of them: the already-loaded fast path, {@code reacquirePersistent} off an imported match
 * row, and {@code deletePersistent} from its own manage menu. There is no {@code session.lock} on a
 * keyed dimension folder, so each of those is two JVMs writing one set of region files.</p>
 */
class ExperienceDoorGuardTest {

  private static final WorldKey KEY = WorldKey.parse("death_resets_ab12cd34");

  @TempDir
  Path tempDir;

  private FakeControl control() {
    FakeControl control =
        new FakeControl(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("test"), tempDir);
    control.start();
    return control;
  }

  private WorldLease acquire(FakeControl control, WorldKey key) {
    AtomicReference<WorldLease> result = new AtomicReference<>();
    control.acquireOrCreatePersistent(key, List.of(), WorldGeneration.DEFAULT, result::set, () -> { });
    return result.get();
  }

  @Test
  @DisplayName("the default guard allows everything, so standalone is untouched")
  void theDefaultAllowsEverything() {
    FakeControl control = control();

    assertNotNull(acquire(control, KEY), "a standalone server holds every capability by definition");
    assertNotNull(control.nextExperienceGeneration(KEY));
    assertTrue(control.deletePersistent(KEY));
  }

  @Test
  @DisplayName("a refusing guard closes the acquire door, and creates NOTHING")
  void aRefusingGuardClosesAcquire() {
    FakeControl control = control();
    control.setExperienceDoorGuard(ExperienceDoorGuard.DENY_ALL);
    AtomicBoolean failed = new AtomicBoolean();

    control.acquireOrCreatePersistent(KEY, List.of(), WorldGeneration.DEFAULT,
        lease -> { throw new AssertionError("a lobby must never be handed an experience world"); },
        () -> failed.set(true));

    assertTrue(failed.get(), "the caller has to be told, or the player waits forever");
    assertFalse(control.onDisk.contains(KEY.runtimeName()), "nothing may reach the disk");
    assertTrue(control.adopted.isEmpty());
  }

  @Test
  @DisplayName("a node that may not open experiences still ROUTES players to the node that can")
  void aRefusingGuardStillRoutes() {
    // The regression this exists for: the guard used to be the first statement in
    // acquireOrCreatePersistent, so it returned before the placement gate ran -- and the gate is the
    // only thing that mints an EXPERIENCE transfer ticket. The lobby is the one backend role without
    // EXPERIENCES and the one node every player lands on, so clicking Enter refused locally and
    // routed NOWHERE. The feature was dead on the deployed topology while 1750 tests passed, because
    // every test of the guard asserted the refusal and none asserted the routing.
    FakeControl control = control();
    control.setExperienceDoorGuard(ExperienceDoorGuard.DENY_ALL);
    control.setPlacementGate(key -> WorldPlacementGate.Decision.ownedBy("worker-1"));
    List<String> routedTo = new ArrayList<>();
    control.setPlacementRouter((worldKey, owner, viewers) -> routedTo.add(owner));
    AtomicBoolean failed = new AtomicBoolean();

    control.acquireOrCreatePersistent(KEY, List.of(), WorldGeneration.DEFAULT,
        lease -> { throw new AssertionError("a lobby must never be handed an experience world"); },
        () -> failed.set(true));

    assertEquals(List.of("worker-1"), routedTo,
        "refusing to OPEN a world is not a reason to refuse to say where it lives");
    assertTrue(failed.get(), "the local acquire still fails — the player is going elsewhere");
    assertFalse(control.onDisk.contains(KEY.runtimeName()), "and nothing is created here");
  }

  @Test
  @DisplayName("a refusing guard closes the delete door")
  void aRefusingGuardClosesDelete() {
    FakeControl control = control();
    assertNotNull(acquire(control, KEY));
    control.setExperienceDoorGuard(ExperienceDoorGuard.DENY_ALL);

    assertFalse(control.deletePersistent(KEY),
        "the owner-delete path runs from the lobby's manage menu, where the world is not loaded — so"
            + " its only guard was a LOCAL loaded-world lookup that answers empty about a live world"
            + " on worker-2");
    assertTrue(control.onDisk.contains(KEY.runtimeName()), "the folder is shared; it must survive");
  }

  @Test
  @DisplayName("a refusing guard closes the regeneration door")
  void aRefusingGuardClosesRegeneration() {
    FakeControl control = control();
    control.setExperienceDoorGuard(ExperienceDoorGuard.DENY_ALL);

    assertNull(control.nextExperienceGeneration(KEY),
        "allocating a successor name is the first half of creating a world");
  }

  @Test
  @DisplayName("the already-loaded fast path re-asserts the claim instead of serving blind")
  void theFastPathReAssertsTheClaim() {
    FakeControl control = control();
    assertNotNull(acquire(control, KEY), "this node opens it while it still holds the claim");

    // A peer takes it over: a reaper cleared the lease, or an ordinary takeover repointed the row.
    // The world is STILL OPEN here, which is exactly the state the old fast path served from.
    control.setPlacementGate(key -> WorldPlacementGate.Decision.busyOn("worker-2"));
    AtomicBoolean failed = new AtomicBoolean();
    List<String> routedTo = new ArrayList<>();
    control.setPlacementRouter((worldKey, owner, viewers) -> routedTo.add(owner));

    control.acquireOrCreatePersistent(KEY, List.of(), WorldGeneration.DEFAULT,
        lease -> { throw new AssertionError("a world we no longer own must not be served"); },
        () -> failed.set(true));

    assertTrue(failed.get());
    assertEquals(List.of("worker-2"), routedTo, "and the players go where the table says the world is");
  }

  @Test
  @DisplayName("a lease is a REFERENCE: closing one does not unload the world under another")
  void leasesAreReferenceCounted() {
    FakeControl control = control();
    WorldLease first = acquire(control, KEY);
    WorldLease second = acquire(control, KEY);
    assertNotNull(first);
    assertNotNull(second);

    first.close();

    // Every caller gets its own LeasedWorld over the SAME handle, and closing one used to run the
    // full disposal policy — so whoever left first unloaded the world under everybody else.
    assertTrue(control.unloaded.isEmpty(), "the world is still in use: " + control.unloaded);

    second.close();
    assertEquals(List.of(KEY.runtimeName()), control.unloaded, "the last one out closes it");
  }

  @Test
  @DisplayName("reacquireByName refuses an experience rather than CREATING one")
  void reacquireByNameRefusesAnExperience() {
    FakeControl control = control();

    // Its create-if-missing fallback would generate a brand-new world under a name a player's save
    // answers to, on any node, with no placement claim of any kind.
    assertTrue(control.reacquireByName("experiences/" + KEY.key()).isEmpty());
    assertTrue(control.reacquireByName(KEY.flatLabel()).isEmpty());
    assertFalse(control.onDisk.contains(KEY.runtimeName()), "and it must not have created anything");
  }

  // ===== fake ==================================================================================

  /** A real {@link AbstractWorldControl} whose backend records what it was asked to do. */
  private static final class FakeControl extends AbstractWorldControl {
    private final Path home;
    final List<String> generated = new ArrayList<>();
    final List<String> adopted = new ArrayList<>();
    final List<String> unloaded = new ArrayList<>();
    final Set<String> onDisk = new HashSet<>();
    final List<WorldHandle> live = new ArrayList<>();

    private FakeControl(ConfigurationAdapter configuration, LoggerAdapter logger, Path home) {
      super(configuration, logger);
      this.home = home;
    }

    @Override protected void runOnWorldThread(Runnable task) { task.run(); }
    @Override protected Path serverHome() { return home; }
    @Override protected Path experiencesDiskRoot() { return home.resolve("experiences"); }
    @Override protected Path lobbyDiskFolder() { return home.resolve("lobby"); }

    @Override protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean create) {
      if (!create && !onDisk.contains(request.runtimeName())) {
        return Optional.empty();
      }
      if (create) {
        generated.add(request.runtimeName());
        onDisk.add(request.runtimeName());
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
      live.remove(handle);
      unloaded.add(handle.runtimeName());
      return true;
    }

    @Override protected Optional<WorldHandle> backendLobby() { return Optional.empty(); }
  }

  private record FakeHandle(String runtimeName, WorldKind kind, Path canonicalFolder)
      implements WorldHandle {
    @Override public com.sexidium.core.platform.WorldAdapter adapter() { return new FakeWorld(runtimeName); }
  }

  private record FakeWorld(String name) implements com.sexidium.core.platform.WorldAdapter {
    @Override public WorldPosition spawnPosition() { return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition target, ItemStackData item) { }
    @Override public void playSound(WorldPosition target, SoundKey sound, float volume, float pitch) { }
    @Override public void setBorder(WorldBorderSpec spec) { }
    @Override public void resetBorder() { }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }
}
