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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant I5, and the rollback that makes it survivable.
 *
 * <p><b>I5 — a world is never created for an experience that already exists.</b> "No folder on disk ⇒
 * generate one" used to be an implicit fall-through with the create flag hard-coded {@code true}, so
 * every caller that merely wanted to RESUME an experience carried the authority to manufacture a
 * brand-new one. On a shared tree that is the most expensive default in the system: a node that
 * cannot see a folder for any reason generates an empty world under a name a player's save answers
 * to, and the warm pool makes it instant and silent.</p>
 *
 * <p><b>The rollback.</b> The gate stamped a row with a real epoch and a live lease BEFORE anything
 * was created, and if creation then failed nothing released it — the row stayed materialised-looking
 * for a world that did not exist, so every later entry was routed to a node with no folder, which
 * then generated a fresh one there. There was no un-claim operation at all.</p>
 */
class AcquireRollbackTest {

  private static final WorldKey KEY = WorldKey.parse("death_resets_ab12cd34");

  @TempDir
  Path tempDir;

  private FakeControl control() {
    FakeControl control =
        new FakeControl(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("test"), tempDir);
    control.start();
    return control;
  }

  /** A gate that grants and mints a fence, plus a recording authority behind it. */
  private static WorldPlacementGate granting(WorldClaim claim) {
    return new WorldPlacementGate() {
      @Override public Decision check(String worldKey) { return Decision.allow(); }
      @Override public WorldClaim lastClaim() { return claim; }
    };
  }

  private record Outcome(boolean ready, boolean failed) { }

  private Outcome acquire(FakeControl control, WorldKey key) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean failed = new AtomicBoolean();
    control.acquireOrCreatePersistent(key, List.of(), WorldGeneration.DEFAULT,
        lease -> ready.set(true), () -> failed.set(true));
    return new Outcome(ready.get(), failed.get());
  }

  // ===== I5 ====================================================================================

  @Test
  @DisplayName("LOAD_ONLY: a resume never manufactures a world whose folder is missing")
  void aResumeNeverCreates() {
    FakeControl control = control();
    control.setCreatePolicy(key -> false); // "I am resuming, not creating"

    Outcome outcome = acquire(control, KEY);

    assertTrue(outcome.failed(), "the caller has to be told, so it can say something truthful");
    assertFalse(outcome.ready());
    assertTrue(control.created.isEmpty(),
        "generating an empty world under a name a player's save answers to is the failure this"
            + " whole invariant exists to prevent");
    assertTrue(control.adopted.isEmpty(), "and the warm pool makes it INSTANT, which is worse");
  }

  @Test
  @DisplayName("LOAD_ONLY still LOADS a world that is really there")
  void aResumeLoadsWhatExists() {
    FakeControl control = control();
    control.onDisk.add(KEY.runtimeName());
    control.setCreatePolicy(key -> false);

    Outcome outcome = acquire(control, KEY);

    assertTrue(outcome.ready(), "refusing to create must never become refusing to open");
    assertTrue(control.created.isEmpty(), "it was loaded, not generated");
  }

  @Test
  @DisplayName("a later generation of the same run blocks creation, however the policy is set")
  void aLaterGenerationBlocksCreation() throws Exception {
    FakeControl control = control();
    // _r1 exists; somebody is holding the stale generation-0 key. Creating here would hand them a
    // brand-new world while their save sits in the next folder along.
    java.nio.file.Files.createDirectories(
        control.experiencesDiskRoot().resolve(KEY.nextGeneration().key()).resolve("region"));

    Outcome outcome = acquire(control, KEY);

    assertTrue(outcome.failed());
    assertTrue(control.created.isEmpty());
    assertTrue(control.adopted.isEmpty());
  }

  @Test
  @DisplayName("CREATE_IF_MISSING is the default, so standalone and every existing caller are unchanged")
  void creationIsStillTheDefault() {
    FakeControl control = control();

    assertTrue(acquire(control, KEY).ready());
    assertFalse(control.created.isEmpty() && control.adopted.isEmpty(),
        "a genuinely new experience still gets a world");
  }

  // ===== the rollback ==========================================================================

  @Test
  @DisplayName("a failed creation gives the claim BACK instead of leaving a ghost home")
  void aFailedCreationUnclaims() {
    FakeControl control = control();
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 4242L, System.currentTimeMillis() + 60_000L);
    control.setPlacementGate(granting(claim));
    RecordingAuthority authority = new RecordingAuthority();
    control.setLeaseAuthority(authority);
    control.creationWorks = false;

    Outcome outcome = acquire(control, KEY);

    assertTrue(outcome.failed());
    assertEquals(List.of(4242L), authority.unclaimed,
        "without this the row stays RESERVED with a live lease for a world that does not exist, and"
            + " every later entry is routed to a node with no folder");
    assertTrue(control.heldClaims().isEmpty(), "and this node stops renewing it");
  }

  @Test
  @DisplayName("a refused creation unclaims too — nothing was ever opened")
  void aRefusedCreationUnclaims() {
    FakeControl control = control();
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 4242L, System.currentTimeMillis() + 60_000L);
    control.setPlacementGate(granting(claim));
    RecordingAuthority authority = new RecordingAuthority();
    control.setLeaseAuthority(authority);
    control.setCreatePolicy(key -> false);

    assertTrue(acquire(control, KEY).failed());

    assertEquals(List.of(4242L), authority.unclaimed);
  }

  @Test
  @DisplayName("a SUCCESSFUL open confirms the claim rather than leaving it RESERVED")
  void aSuccessfulOpenConfirms() {
    FakeControl control = control();
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 4242L, System.currentTimeMillis() + 60_000L);
    control.setPlacementGate(granting(claim));
    RecordingAuthority authority = new RecordingAuthority();
    control.setLeaseAuthority(authority);

    assertTrue(acquire(control, KEY).ready());

    assertEquals(List.of(4242L), authority.confirmed,
        "confirmLoaded had no callers at all; a world that opened stayed RESERVED forever");
    assertTrue(authority.unclaimed.isEmpty());
    assertEquals(1, control.heldClaims().size(), "and the claim is renewed from here on");
  }

  @Test
  @DisplayName("deleting a world hands its claim back instead of renewing it forever")
  void deleteReleasesTheClaim() {
    FakeControl control = control();
    WorldClaim claim = new WorldClaim(KEY, "worker-1", 7L, 4242L, System.currentTimeMillis() + 60_000L);
    control.setPlacementGate(granting(claim));
    RecordingAuthority authority = new RecordingAuthority();
    control.setLeaseAuthority(authority);
    assertTrue(acquire(control, KEY).ready());
    assertEquals(1, control.heldClaims().size());

    assertTrue(control.deletePersistent(KEY));

    // Without this the claim stayed in the map after the folder was gone, so the heartbeat kept
    // issuing a fenced renew that matched and rewrote the row to LOADED with a live lease -- for a
    // world that no longer exists. forget() refuses a LOADED row, rehome() refuses it, adoption
    // refuses it, so the name was reserved on this node for the life of the process. Death Resets
    // deletes its predecessor on every single regeneration.
    assertTrue(control.heldClaims().isEmpty(), "a deleted world must not still be renewed");
    assertEquals(List.of(4242L), authority.released,
        "and the row has to be told, or no peer can ever take the name back");
  }

  /** Records which fences were confirmed, released and given back. */
  private static final class RecordingAuthority implements WorldLeaseAuthority {
    final List<Long> confirmed = new ArrayList<>();
    final List<Long> unclaimed = new ArrayList<>();
    final List<Long> released = new ArrayList<>();

    @Override public ClaimOutcome claim(WorldKey key, String ownerUuid) {
      return new ClaimOutcome.Unavailable("not used");
    }
    @Override public boolean renew(WorldClaim claim, int players) { return true; }
    @Override public boolean confirmOpen(WorldClaim claim) { return confirmed.add(claim.fence()); }
    @Override public boolean release(WorldClaim claim) { return released.add(claim.fence()); }
    @Override public boolean unclaim(WorldClaim claim) { return unclaimed.add(claim.fence()); }
    @Override public WorldKey allocateNextGeneration(String experienceId, WorldKey current) {
      return current.nextGeneration();
    }
    @Override public Optional<ExperienceLocator.Placement> locate(WorldKey key) { return Optional.empty(); }
    @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }
    @Override public List<ExperienceLocator.Placement> heldBy(String nodeId) { return List.of(); }
  }

  // ===== fake ==================================================================================

  private static final class FakeControl extends AbstractWorldControl {
    private final Path home;
    final List<String> created = new ArrayList<>();
    final List<String> adopted = new ArrayList<>();
    final Set<String> onDisk = new HashSet<>();
    final List<WorldHandle> live = new ArrayList<>();
    boolean creationWorks = true;

    private FakeControl(ConfigurationAdapter configuration, LoggerAdapter logger, Path home) {
      super(configuration, logger);
      this.home = home;
    }

    @Override protected void runOnWorldThread(Runnable task) { task.run(); }
    @Override protected Path serverHome() { return home; }
    @Override public Path experiencesDiskRoot() { return home.resolve("experiences"); }
    @Override protected Path lobbyDiskFolder() { return home.resolve("lobby"); }

    @Override protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean create) {
      if (request.kind() == WorldKind.PERSISTENT) {
        if (!create) {
          return onDisk.contains(request.runtimeName())
              ? Optional.of(handle(request)) : Optional.empty();
        }
        if (!creationWorks) {
          return Optional.empty();
        }
        created.add(request.runtimeName());
        onDisk.add(request.runtimeName());
        return Optional.of(handle(request));
      }
      if (!create) {
        return Optional.empty();
      }
      return Optional.of(handle(request));
    }

    private WorldHandle handle(WorldRequest request) {
      FakeHandle handle = new FakeHandle(request.runtimeName(), request.kind(),
          home.resolve(request.runtimeName().replace('/', '_')));
      live.add(handle);
      return handle;
    }

    @Override protected boolean backendExistsOnDisk(WorldRequest request) {
      return onDisk.contains(request.runtimeName());
    }

    @Override protected Optional<WorldHandle> backendAdopt(WorldHandle pooled, WorldRequest request) {
      if (!creationWorks) {
        return Optional.empty();
      }
      live.remove(pooled);
      adopted.add(request.runtimeName());
      onDisk.add(request.runtimeName());
      return Optional.of(handle(request));
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
