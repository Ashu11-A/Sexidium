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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I4: a holder whose renewal is refused freezes writes NOW and has unloaded within one
 * lease period.
 *
 * <p>There was no such path. A node that lost a world went on serving it and went on writing to
 * region files another node had open, because its own {@code renew} was guarded on {@code node_id}
 * alone and silently matched nothing. Nothing in {@code AbstractWorldControl} could even be told.</p>
 */
class WorldEvictionTest {

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

  /** A gate that grants, and hands out a fence the way the real decider does. */
  private static WorldPlacementGate granting(WorldClaim claim) {
    return new WorldPlacementGate() {
      @Override public Decision check(String worldKey) { return Decision.allow(); }
      @Override public WorldClaim lastClaim() { return claim; }
    };
  }

  @Test
  @DisplayName("a refused renewal evacuates the world and unloads it WITHOUT saving")
  void aLostLeaseEvacuatesAndUnloadsUnsaved() {
    FakeControl control = control();
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 1234L, System.currentTimeMillis() + 60_000L);
    control.setPlacementGate(granting(claim));
    assertNotNull(acquire(control, KEY));
    int before = control.evacuated.size();

    control.onLeaseLost(claim);

    assertEquals(List.of(KEY.runtimeName()), control.unloaded, "it must actually close");
    assertFalse(control.savedOnUnload.get(),
        "save=false is the whole reason this is safe: another node may already be writing these"
            + " region files, and flushing a stale chunk cache over its writes is strictly worse"
            + " than losing the seconds since the last autosave");
    assertTrue(control.evacuated.size() > before, "the players inside have to be moved out first");
  }

  @Test
  @DisplayName("on a node with no lobby world of its own, the occupants are sent OFF the node")
  void evictionOnAWorkerSendsPlayersOffNode() {
    FakeControl control = control();
    // The shape that matters: a worker. It hosts every experience and has no lobby world, and
    // evacuate() used to return immediately when lobbySpawn() was empty -- so on precisely the nodes
    // where eviction happens it moved nobody, the unload then failed because a world with players in
    // it cannot be unloaded, and the claim had already been dropped. Two writers, one world.
    control.hasLobbyWorld = false;
    List<Object> sentOffNode = new ArrayList<>();
    control.setEvacuationFallback(sentOffNode::addAll);
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 1234L, System.currentTimeMillis() + 60_000L);
    control.setPlacementGate(granting(claim));
    assertNotNull(acquire(control, KEY));
    int before = control.evacuated.size();

    control.onLeaseLost(claim);

    assertTrue(control.evacuated.size() > before,
        "the occupants still have to be looked up — there is nowhere local to put them, which is the"
            + " reason the fallback exists rather than a reason to skip the whole step");
    assertEquals(List.of(KEY.runtimeName()), control.unloaded, "and it still closes");
  }

  @Test
  @DisplayName("after eviction the world is no longer claimed, renewed or reported as open")
  void anEvictedWorldIsForgotten() {
    FakeControl control = control();
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 1234L, System.currentTimeMillis() + 60_000L);
    control.setPlacementGate(granting(claim));
    assertNotNull(acquire(control, KEY));
    assertEquals(1, control.heldClaims().size());
    assertTrue(control.openPersistentPlacementKeys().contains(KEY.key()));

    control.onLeaseLost(claim);

    assertTrue(control.heldClaims().isEmpty(), "a claim we have lost must not be renewed again");
    assertFalse(control.openPersistentPlacementKeys().contains(KEY.key()),
        "and the lease heartbeat must stop announcing it as ours");
  }

  @Test
  @DisplayName("evicting a world this node never had open is a no-op, not a crash")
  void evictingAnUnknownWorldIsHarmless() {
    FakeControl control = control();

    control.onLeaseLost(new WorldClaim(KEY, "worker-1", 7L, 99L, 0L));
    control.onLeaseLost(null);

    assertTrue(control.unloaded.isEmpty());
  }

  @Test
  @DisplayName("standalone holds no claims, so nothing can ever be evicted from under it")
  void standaloneNeverEvicts() {
    FakeControl control = control();
    // No gate installed: WorldPlacementGate.ALLOW_ALL mints no claim and answers null.
    assertNotNull(acquire(control, KEY));

    assertTrue(control.heldClaims().isEmpty(),
        "a single server owns every world it has; there is nothing to arbitrate and nothing to lose");
  }

  // ===== fake ==================================================================================

  private static final class FakeControl extends AbstractWorldControl {
    private final Path home;
    final List<String> unloaded = new ArrayList<>();
    final List<String> evacuated = new ArrayList<>();
    final Set<String> onDisk = new HashSet<>();
    final List<WorldHandle> live = new ArrayList<>();
    final AtomicBoolean savedOnUnload = new AtomicBoolean(true);

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
      onDisk.add(request.runtimeName());
      FakeHandle handle = new FakeHandle(request.runtimeName(), request.kind(),
          home.resolve(request.runtimeName().replace('/', '_')), evacuated);
      live.add(handle);
      return Optional.of(handle);
    }

    @Override protected boolean backendExistsOnDisk(WorldRequest request) {
      return onDisk.contains(request.runtimeName());
    }

    @Override protected Optional<WorldHandle> backendAdopt(WorldHandle pooled, WorldRequest request) {
      live.remove(pooled);
      onDisk.add(request.runtimeName());
      FakeHandle handle = new FakeHandle(request.runtimeName(), request.kind(),
          home.resolve(request.runtimeName().replace('/', '_')), evacuated);
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
      if (handle.kind() == WorldKind.PERSISTENT) {
        savedOnUnload.set(save);
        unloaded.add(handle.runtimeName());
      }
      live.remove(handle);
      return true;
    }

    @Override protected Optional<WorldHandle> backendLobby() { return Optional.empty(); }

    /** Workers -- the nodes that host experiences -- have no lobby world. Flip to model one. */
    boolean hasLobbyWorld = true;

    @Override public Optional<WorldPosition> lobbySpawn() {
      // A resolvable lobby spawn, so evacuate() actually walks the occupants.
      return hasLobbyWorld
          ? Optional.of(new WorldPosition("lobby", 0.5, 64, 0.5, 0f, 0f))
          : Optional.empty();
    }
  }

  private record FakeHandle(String runtimeName, WorldKind kind, Path canonicalFolder,
      List<String> evacuated) implements WorldHandle {
    @Override public com.sexidium.core.platform.WorldAdapter adapter() {
      return new FakeWorld(runtimeName, evacuated);
    }
  }

  private record FakeWorld(String name, List<String> evacuated)
      implements com.sexidium.core.platform.WorldAdapter {
    @Override public WorldPosition spawnPosition() { return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f); }

    @Override public List<PlayerAdapter> players() {
      evacuated.add(name);
      return List.of();
    }

    @Override public void dropItem(WorldPosition target, ItemStackData item) { }
    @Override public void playSound(WorldPosition target, SoundKey sound, float volume, float pitch) { }
    @Override public void setBorder(WorldBorderSpec spec) { }
    @Override public void resetBorder() { }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }
}
