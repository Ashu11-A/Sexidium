package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.map.SpawnPointStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The unified, platform-agnostic world control system. It owns ALL world-lifecycle policy that used to
 * be copy-pasted across the two platform lease services: the managed/leased/preserved name registries,
 * the warm temp-world pool, the disposal decision tree (delete a disposable world, unload-and-keep a
 * persistent one), player evacuation to the lobby, the experience/temp/lobby classification, and the
 * worldgen guarantees. A platform "backend" supplies only the irreducible raw operations through the
 * abstract hooks below — it contains no naming or policy logic.
 *
 * <p>The cohesive concrete helpers are split into focused collaborators in this package: {@link
 * WorldNaming} (naming rules), {@link WorldConfig} (config → settings/border), {@link WorldStorage}
 * (path layout + folder deletion) and {@link WorldNameRegistry} (managed/leased/preserved bookkeeping
 * + protected-name matching). This base class keeps the policy orchestration and delegates the rest.</p>
 *
 * <p>It implements {@link WorldLeaseService} so existing consumers ({@code GameManager},
 * {@code ExperienceService}, …) keep calling {@code serverAdapter.worlds()} unchanged; the unification
 * is entirely under that seam.</p>
 */
public abstract class AbstractWorldControl implements WorldLeaseService {
  /** Reads the lobby's admin-captured spawn points from {@code sexidium-lobby.yml} in its data folder. */
  private static final SpawnPointStore LOBBY_SPAWNS = new SpawnPointStore(SpawnPointStore.LOBBY);

  /** How many consecutive regeneration names to try before giving up — see nextExperienceGeneration. */
  private static final int MAX_GENERATION_PROBES = 1000;

  /** How many ticks apart to retry an unload that the async evacuation has not finished clearing. */
  private static final int UNLOAD_ATTEMPTS = 4;
  private static final long UNLOAD_RETRY_TICKS = 20L;

  protected final ConfigurationAdapter configuration;
  protected final LoggerAdapter logger;
  protected final WorldNaming naming;

  private final WorldConfig worldConfig;
  private final WorldNameRegistry registry;

  private final WorldPool pool = new WorldPool();
  /**
   * Atomic because a clone request is now built on the CALLING thread (it has to exist before the copy
   * can be handed to the staging thread), while the pool still builds temp requests on the world thread.
   * Two threads incrementing a plain int would eventually mint the same world name twice.
   */
  private final AtomicInteger sequence = new AtomicInteger();
  /**
   * The one background thread that copies map templates, created on first use — a server that never
   * clones a map never spawns it. Single-threaded on purpose: two concurrent 46 MB copies contend for the
   * same disk and finish later than if they had queued, and serialising them also bounds how much a
   * burst of match starts can cost.
   */
  private volatile ExecutorService cloneStaging;
  /**
   * The copy thread backups get to themselves, separate from {@link #cloneStaging}.
   *
   * <p>They used to share one single-threaded executor, on the argument that two concurrent copies of a
   * multi-hundred-megabyte tree contend for one disk and both finish later than if they had queued.
   * That trade is wrong once the cost of sharing is a MATCH START blocked behind a backup: the clone
   * queue is on the path a player waits on, and a 290 MB experience copy sits at its head for as long
   * as the disk takes. Two threads contending is strictly better than head-of-line blocking, and this
   * one runs at minimum priority because a backup is the least urgent thing this server ever does.</p>
   */
  private volatile ExecutorService backupStaging;
  private volatile boolean shuttingDown;
  /**
   * Worlds this node is closing, or failed to close. They are still OPEN here, so the lease heartbeat
   * must keep claiming them.
   *
   * <p>The window this exists for is narrow and, for an experience, unprotected by anything else: the
   * lease is dropped from the moment the name leaves the leased registry, which happens BEFORE the
   * unload, and an experience world is a dimension of the node's own level -- so the {@code
   * session.lock} that would normally refuse a second opener belongs to {@code world/}, which is
   * node-local, and does not cover the shared dimension folder at all. Between "lease dropped" and
   * "world actually closed" there would be nothing whatsoever stopping a peer from opening the same
   * region files. The lease IS the mutual exclusion; it has to outlive the close.</p>
   */
  private final Set<String> closing = java.util.concurrent.ConcurrentHashMap.newKeySet();
  /** Whether the "pool warm" line has been logged; it reports the first time every target is met. */
  private boolean warmed;

  protected AbstractWorldControl(ConfigurationAdapter configuration, LoggerAdapter logger) {
    this.configuration = configuration;
    this.logger = logger;
    this.naming = new WorldNaming(configuration);
    this.worldConfig = new WorldConfig(configuration);
    this.registry = new WorldNameRegistry(naming);
  }

  public final WorldNaming naming() {
    return naming;
  }

  // ===== platform hooks ========================================================================

  /** Runs {@code task} on the platform's world/main thread (world create/load/unload must run there). */
  protected abstract void runOnWorldThread(Runnable task);

  /**
   * Runs a task on the world thread after {@code delayTicks}.
   *
   * <p>Exists for LAZY pool replenishment. Rebuilding a warm world is a full terrain generation on the
   * world thread — the very freeze the pool exists to avoid — so doing it the instant a world is taken
   * lands that freeze on the exact tick a match is starting, which is the worst possible moment and
   * precisely when a player would notice. Deferring it puts the cost in the quiet gap afterwards.</p>
   *
   * <p>Default runs immediately, which keeps every existing backend correct.</p>
   */
  protected void runOnWorldThreadLater(Runnable task, long delayTicks) {
    runOnWorldThread(task);
  }

  /** The server's world container / home directory. */
  protected abstract Path serverHome();

  /** On-disk base folder that holds persistent experience worlds (and their co-located metadata). */
  protected abstract Path experiencesDiskRoot();

  /** On-disk folder of the lobby world. */
  protected abstract Path lobbyDiskFolder();

  /**
   * Creates or loads the requested world on the current (world) thread and applies its settings.
   * Resolves an already-loaded world first; otherwise loads its folder when present; otherwise, when
   * {@code createIfMissing}, generates it (cloning the template first for a {@link WorldKind#CLONE}).
   * Returns empty when the world does not exist and may not be created, or on any failure.
   */
  protected abstract Optional<WorldHandle> backendAcquire(WorldRequest request, boolean createIfMissing);

  /** Resolves a currently-loaded world by its canonical runtime name, or empty. */
  protected abstract Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind);

  /** Unloads {@code handle}'s world (saving when {@code save}). Returns true on success. */
  protected abstract boolean backendUnload(WorldHandle handle, boolean save);

  /**
   * Asks the platform to flush ONE open world to disk in place, without unloading it. Returns true when
   * the save was issued.
   *
   * <p>The backend half of {@link #saveExperienceNow}. It is not a barrier and must not be documented
   * as one: on Paper this is {@code World.save()}, which schedules the chunk writes and returns. See
   * {@link com.sexidium.core.platform.WorldLeaseService#saveExperienceNow} for what that does and does
   * not buy. Default false — a platform with no in-place save simply has nothing to offer here, and no
   * caller is allowed to refuse on the answer.</p>
   */
  protected boolean backendSaveWorld(WorldHandle handle) {
    return false;
  }

  /** Resolves the loaded lobby world handle, or empty when it is not loaded. */
  protected abstract Optional<WorldHandle> backendLobby();

  /**
   * Drop a third-party world-manager registration for one world. Default no-op.
   *
   * <p>Called on RELEASE, not only on delete. That distinction is the whole of F-A4: the old cleanup
   * dropped a registration only when the world's FOLDER was missing, which on shared storage is never
   * — so every node accumulated a registration for every world it had ever touched, and on the next
   * boot each of them autoloaded the whole park concurrently, before the plugin was even enabled and
   * with no placement claim in sight.</p>
   */
  protected void backendForgetRegistration(String runtimeName) {
  }

  /**
   * Drop every registration for a managed world this node does NOT hold, at boot.
   *
   * <p>Replaces a cleanup keyed on "the folder is gone", which under shared storage never fires. The
   * question that actually matters is not whether the bytes exist — they always do — but whether THIS
   * node has any business opening them.</p>
   */
  protected void backendPruneForeignRegistrations(Set<String> keysThisNodeHolds) {
  }

  /**
   * Public entry point for the boot-time prune. Must run BEFORE anything is opened.
   *
   * <p>Called by the core once the placement layer is wired, because only the placement table knows
   * which worlds are this node's — the disk cannot answer it on a shared tree, and that is precisely
   * why the check it replaces was a permanent no-op.</p>
   */
  public final void pruneForeignRegistrations(Set<String> keysThisNodeHolds) {
    try {
      backendPruneForeignRegistrations(
          keysThisNodeHolds == null ? Set.of() : Set.copyOf(keysThisNodeHolds));
    } catch (RuntimeException failed) {
      logger.warning("Could not prune foreign world registrations: " + failed.getMessage());
    }
  }

  /** Best-effort removal of any stale worldgen datapack left by an older build, so it can never break boot. */
  protected void backendRemoveStaleWorldgenDatapack() {
  }

  /** Removes orphaned disposable temp-world folders left by a crash. */
  protected void backendCleanupStaleTempWorlds() {
  }

  /**
   * Drops any world registrations an external world manager still holds for worlds whose folders no
   * longer exist. Deleting an experience removes its folders, but a third-party manager that also has it
   * on file keeps trying to autoload it on every boot and logs an error when it cannot — which is what
   * left a server booting into a wall of {@code WORLD_FOLDER_INVALID} failures for experiences its owner
   * had deleted weeks earlier. Default no-op: a platform with no such manager has nothing to reconcile.
   */
  protected void backendCleanupStaleRegistrations() {
  }

  /**
   * Whether this world's chunk data is already on disk. Lets the control layer tell "load what exists"
   * apart from "generate something new" — only the latter may be served from the warm pool. Default
   * false, which simply means a backend that cannot tell never adopts.
   */
  /**
   * Consulted before a persistent world is created or loaded. Defaults to the standalone gate, so a
   * single server behaves exactly as it always has and never touches the database for this.
   */
  private volatile WorldPlacementGate placementGate = WorldPlacementGate.ALLOW_ALL;

  public void setPlacementGate(WorldPlacementGate gate) {
    this.placementGate = gate == null ? WorldPlacementGate.ALLOW_ALL : gate;
  }

  /**
   * Invariant I3, checked in EVERY door that opens, creates, deletes or regenerates an experience
   * world. Defaults to allow-all, so a standalone server and every test are unaffected.
   */
  private volatile ExperienceDoorGuard doorGuard = ExperienceDoorGuard.ALLOW_ALL;

  public void setExperienceDoorGuard(ExperienceDoorGuard guard) {
    this.doorGuard = guard == null ? ExperienceDoorGuard.ALLOW_ALL : guard;
  }

  /** Refuses, loudly, when this node may not touch experience worlds at all. */
  private boolean mayOpenExperience(WorldKey key, String what) {
    if (key == null || doorGuard.mayOpen(key)) {
      return true;
    }
    // Three things can close this door and the message no longer names only one of them: the node
    // lacks EXPERIENCES, the node lacks the CONTENT the world needs (which is announced separately as
    // SX-CONTENT, with the exact missing codes), or the node is DRAINING for a restart. All three
    // share one consequence and it is the one worth stating: nothing is lost, the world simply stays
    // shut until a node that may open it does.
    logger.severe("Refusing to " + what + " experience world '" + key.key() + "' on this node. A node"
        + " that does not hold EXPERIENCES, cannot run the world's content, or is draining must never"
        + " open, create, delete or regenerate an experience world -- there is no session.lock on a"
        + " keyed dimension folder, so the lease is the only thing between two servers and one set of"
        + " region files. The world is not lost: it stays shut until a node that may open it does.");
    return false;
  }

  public WorldPlacementGate placementGate() {
    return placementGate;
  }

  /** Told who to send the waiting players to when the gate refuses. No-op standalone. */
  @FunctionalInterface
  public interface PlacementRouter {
    void route(String worldKey, String ownerNodeId, Collection<? extends PlayerAdapter> viewers);
  }

  private volatile PlacementRouter placementRouter = (worldKey, ownerNodeId, viewers) -> { };

  public void setPlacementRouter(PlacementRouter router) {
    this.placementRouter = router == null ? (worldKey, ownerNodeId, viewers) -> { } : router;
  }

  protected boolean backendExistsOnDisk(WorldRequest request) {
    return false;
  }

  /**
   * Hands a warm pooled world over to become {@code request}: the world is unloaded, its folder becomes
   * the requested world's folder, and it is loaded again under the requested identity. This is what turns
   * a boot-time generation into an instant start — no terrain is generated, because the terrain already
   * exists.
   *
   * <p>Returns the adopted handle, or empty when the backend cannot do it (in which case the caller
   * generates a world normally and the pooled one is returned/disposed). Runs on the world thread.</p>
   */
  protected Optional<WorldHandle> backendAdopt(WorldHandle pooled, WorldRequest request) {
    return Optional.empty();
  }

  /**
   * Where a {@link WorldKind#CLONE} request's chunk data may be copied BEFORE the world is created —
   * which must be the exact folder {@link #backendAcquire} will then load that world from.
   *
   * <p>Returning non-null opts the backend into off-thread cloning: the control layer copies the
   * template there on its own staging thread, and {@code backendAcquire} is expected to notice the
   * already-populated folder and skip its own copy — while still doing everything else it does for a
   * clone. That "everything else" is not optional: on Paper it includes reading the template's stored
   * spawn and running the land-spawn search, and the world border is centred on the resulting spawn, so
   * a backend that treated a staged folder as "a world that already existed on disk" would silently move
   * the border off the map's build.</p>
   *
   * <p>Default null: the backend does the whole clone inside {@link #backendAcquire}, on the world
   * thread, exactly as it always has.</p>
   */
  protected Path backendCloneStagingFolder(WorldRequest request) {
    return null;
  }

  /** All currently loaded disposable temp/clone worlds known to the backend. */
  protected List<WorldHandle> backendLoadedTempWorlds() {
    return List.of();
  }

  /** On-disk root whose immediate children are disposable temp-world folders. */
  protected Path backendTempDiskRoot() {
    return tempSubdir();
  }

  // ===== WorldLeaseService: lifecycle ==========================================================

  @Override
  public boolean enabled() {
    return readTempBoolean("enabled", true);
  }

  @Override
  public void start() {
    if (!enabled()) {
      return;
    }
    registry.preserveSingle(naming.lobbyRuntimeName());
    backendRemoveStaleWorldgenDatapack();
    if (readTempBoolean("cleanup-stale-on-start", true)) {
      try {
        backendCleanupStaleTempWorlds();
      } catch (RuntimeException exception) {
        logger.warning("Stale temp-world cleanup failed: " + exception.getMessage());
      }
      try {
        backendCleanupStaleRegistrations();
      } catch (RuntimeException exception) {
        logger.warning("Stale world-registration cleanup failed: " + exception.getMessage());
      }
      try {
        cleanupStaleBackupStaging();
      } catch (RuntimeException exception) {
        logger.warning("Stale backup-staging cleanup failed: " + exception.getMessage());
      }
    }
    logStagingFileStores();
    configurePool();
    warmPool();
  }

  /**
   * Report whether the warm pool and the experiences tree live on the same file store.
   *
   * <p>Adoption moves a warm world's folder onto the experiences tree with a rename. A rename across
   * two file stores is {@code EXDEV} and fails, which means every experience and every regeneration
   * silently falls back to generating terrain on the world thread — a multi-second freeze on the tick
   * a player is entering, and 39 of them in one afternoon on the live network. Which of the two it is
   * has been guessed at from log symptoms twice; this line answers it once, at boot, by name.</p>
   */
  private void logStagingFileStores() {
    try {
      Path temp = backendTempDiskRoot();
      Path experiences = experiencesDiskRoot();
      if (temp == null || experiences == null) {
        return;
      }
      // The roots themselves may not exist yet at boot; walk up to the nearest ancestor that does,
      // because a FileStore lookup on a missing path throws rather than answering.
      Path tempAnchor = existingAncestor(temp);
      Path experiencesAnchor = existingAncestor(experiences);
      if (tempAnchor == null || experiencesAnchor == null) {
        return;
      }
      var tempStore = Files.getFileStore(tempAnchor);
      var experiencesStore = Files.getFileStore(experiencesAnchor);
      boolean same = tempStore.equals(experiencesStore);
      logger.info("World staging file stores: pool=" + tempStore.name() + " (" + tempAnchor + ")"
          + ", experiences=" + experiencesStore.name() + " (" + experiencesAnchor + ")"
          + (same ? " — SAME store, adoption is a rename."
                  : " — DIFFERENT stores, adoption must copy."));
    } catch (IOException | RuntimeException unreadable) {
      logger.info("Could not compare the pool and experiences file stores: " + unreadable);
    }
  }

  private static Path existingAncestor(Path path) {
    for (Path candidate = path.toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  @Override
  public void shutdown() {
    shuttingDown = true;
    ExecutorService staging = cloneStaging;
    if (staging != null) {
      // Stop accepting copies; a copy already in flight is left to finish its file walk rather than being
      // interrupted mid-write. Its world-thread continuation checks shuttingDown and creates nothing.
      staging.shutdown();
      cloneStaging = null;
    }
    ExecutorService backups = backupStaging;
    if (backups != null) {
      backups.shutdown();
      backupStaging = null;
    }
    for (WorldHandle handle : pool.drain()) {
      dispose(handle, false);
    }
    closeClaimedWorlds();
    registry.clearLeased();
    leaseRefs.clear();
  }

  /**
   * Save, close and only then hand back every persistent world this node still holds.
   *
   * <p>Nothing closed them before. {@link #dispose} early-returns on anything that
   * {@code persistsOnRelease()}, so an experience world simply stayed loaded until Paper's own final
   * save at the very end of the stop — while the claims had already been released, seconds earlier,
   * by the network layer. That gap is a second writer: a peer sees an idle placement with a lapsed
   * lease, {@code adoptableHere} says yes (the folder is on the shared tree), and it opens the same
   * region files this process is still writing. It is the same rule the runtime close path already
   * follows in {@code releasePersistent}, and the reason the {@code closing} set exists: <b>the lease
   * has to outlive the close.</b></p>
   *
   * <p>A world that will not unload keeps its claim, deliberately. A held lease on a stopping node
   * costs one lease period of nobody being able to take the world over; a released lease on a world
   * whose region files are still open costs the world.</p>
   */
  private void closeClaimedWorlds() {
    for (String runtimeName : List.copyOf(claims.keySet())) {
      WorldClaim claim = claims.get(runtimeName);
      Optional<WorldHandle> handle = backendResolveLoaded(runtimeName, WorldKind.PERSISTENT);
      if (handle.isPresent()) {
        closing.add(runtimeName);
        releaseSiblings(handle.get());
        // save = true. This is an orderly stop, this node is the only writer, and every edit since
        // the last autosave is in the chunk cache -- the opposite of an eviction, where another node
        // may already own the files and flushing ours over its writes would be the corruption.
        if (!backendUnload(handle.get(), true)) {
          logger.severe("Could not unload persistent world '" + runtimeName + "' on shutdown. This"
              + " node KEEPS its claim on it, so no peer will open it while its region files may"
              + " still be ours; the lease expires on its own.");
          continue;
        }
        registry.removeManaged(runtimeName);
        closing.remove(runtimeName);
      }
      // Claim released only now, when the world is really closed (or was never open here).
      claims.remove(runtimeName);
      if (claim != null) {
        leaseAuthority.release(claim);
      }
    }
  }

  /**
   * Reads how many warm worlds of each shape to keep. The three dimensions are always pooled — an
   * experience needs an Overworld AND its Nether AND its End, so warming only Overworlds still left two
   * generations to pay for at start time. Void profiles are pooled too when configured, which is what
   * makes a SkyBlock-style experience (Classic Skyblock, Random Layers) stop regenerating its empty world
   * every single time.
   */
  /**
   * Re-reads the pool targets from config and tops up to match. Safe at any time: lowering a target
   * simply stops refilling — already-warm worlds are left alone and consumed normally — and raising one
   * starts the queue again. Wired into {@code /sx admin reload} so pool sizes are not restart-only.
   */
  public final void reloadPool() {
    if (!enabled() || shuttingDown) {
      return;
    }
    configurePool();
    warmPool();
  }

  private void configurePool() {
    // The historical single "pool-size" is still honoured as the Overworld target so an existing config
    // keeps meaning what it meant.
    int legacy = Math.max(0, readTempInt("pool-size", WorldPool.DEFAULT_OVERWORLD_WARM_SIZE));
    for (WorldProfile profile : WorldPool.DEFAULT_WARM) {
      int configured = readTempInt("pool." + profile.id(),
          profile.dimension() == WorldDimension.OVERWORLD ? legacy : WorldPool.DEFAULT_WARM_SIZE);
      pool.target(profile, configured);
    }
    for (WorldDimension dimension : List.of(WorldDimension.OVERWORLD, WorldDimension.NETHER)) {
      WorldProfile voidProfile = new WorldProfile(dimension, true, null);
      pool.target(voidProfile, readTempInt("pool." + voidProfile.id(), 1));
    }
  }

  @Override
  public void preserve(Collection<String> worldNames) {
    registry.preserve(worldNames);
  }

  @Override
  public int collectGarbage(Collection<String> inUseWorldNames) {
    if (!enabled() || shuttingDown) {
      return 0;
    }
    Set<String> protectedNames = registry.protectedNames(naming.lobbyRuntimeName(), inUseWorldNames, pool.all());
    int[] reclaimed = {0};
    runOnWorldThread(() -> {
      try {
        reclaimed[0] = collectGarbageOnWorldThread(protectedNames);
        if (reclaimed[0] > 0) {
          logger.info("World GC reclaimed " + reclaimed[0] + " orphaned temp world(s).");
        }
      } catch (RuntimeException exception) {
        logger.warning("Runtime temp-world GC failed: " + exception.getMessage());
      }
    });
    return reclaimed[0];
  }

  // ===== WorldLeaseService: names + paths ======================================================

  @Override
  public String lobbyName() {
    return naming.lobbyName();
  }

  @Override
  public Path worldRoot() {
    return serverHome().resolve(naming.worldsRoot());
  }

  @Override
  public Path tempSubdir() {
    return worldRoot().resolve("temp");
  }

  @Override
  public Path lobbyFolder() {
    return lobbyDiskFolder();
  }

  @Override
  public String experiencesSubdirName() {
    return naming.experiencesNamespace();
  }

  @Override
  public Path experiencesSubdir() {
    return experiencesDiskRoot();
  }

  @Override
  public Path lobbyDataFolder() {
    return lobbyDiskFolder();
  }

  @Override
  public Optional<WorldPosition> lobbySpawn() {
    Optional<WorldHandle> lobby = backendLobby();
    if (lobby.isEmpty()) {
      return Optional.empty();
    }
    String worldName = lobby.get().adapter().name();
    // Prefer an admin-captured spawn point from the lobby sidecar (/lobby setspawn|addspawn), spreading
    // joiners randomly across all configured points. Fall back to the world's own (pinned) spawn when none
    // are set, so an un-configured lobby behaves exactly as before.
    WorldPosition chosen = LOBBY_SPAWNS.load(lobbyDiskFolder(), worldName == null ? "" : worldName).random();
    if (chosen != null) {
      return Optional.of(worldName == null ? chosen : chosen.withWorldName(worldName));
    }
    return Optional.ofNullable(lobby.get().adapter().spawnPosition());
  }

  // ===== WorldLeaseService: temp/disposable worlds =============================================

  @Override
  public Optional<WorldLease> acquireReady(WorldProfile profile) {
    if (!enabled() || shuttingDown) {
      return Optional.empty();
    }
    WorldHandle handle = pool.poll(profile == null ? WorldProfile.OVERWORLD : profile);
    if (handle == null) {
      return Optional.empty();
    }
    registry.addLeased(handle.runtimeName());
    // Start replacing it immediately, so the next borrower finds a warm world too.
    warmPool();
    return Optional.of(new LeasedWorld(this, handle));
  }

  /**
   * Takes a warm world of {@code profile} out of the pool, ready to be adopted under another identity.
   * For backends that provision linked dimensions themselves: an experience's Nether and End are two more
   * full world generations, and this is how they are paid for at boot instead of at start.
   *
   * <p>The caller owns the handle — it must either adopt it (and register the result) or dispose of it.
   * The pool has FORGOTTEN it entirely, so a handle that is taken and then dropped is an orphan folder.
   * The pool immediately begins replacing it.</p>
   */
  public final Optional<WorldHandle> takePooled(WorldProfile profile) {
    if (!enabled() || shuttingDown) {
      return Optional.empty();
    }
    WorldHandle handle = pool.poll(profile);
    if (handle == null) {
      return Optional.empty();
    }
    registry.removeManaged(handle.runtimeName());
    warmPool();
    return Optional.of(handle);
  }

  /** A world request that renames {@code pooled} into the given target identity. */
  protected final WorldRequest adoptionRequest(WorldRequest target, Long seed) {
    return new WorldRequest(target.kind(), target.namespace(), target.keyPath(), target.runtimeName(),
        null, seed, target.settings());
  }

  @Override
  public void acquireOrCreate(Collection<? extends PlayerAdapter> viewers, Consumer<WorldLease> onReady, Runnable onFailure) {
    Optional<WorldLease> ready = acquireReady();
    if (ready.isPresent()) {
      onReady.accept(ready.get());
      return;
    }
    if (!enabled() || shuttingDown) {
      fail(onFailure);
      return;
    }
    runOnWorldThread(() -> {
      Optional<WorldHandle> created = backendAcquire(tempRequest(), true);
      if (created.isPresent()) {
        registerLeased(created.get());
        onReady.accept(new LeasedWorld(this, created.get()));
      } else {
        fail(onFailure);
      }
    });
  }

  @Override
  public void acquireOrCreateClone(String templateWorldName, Collection<? extends PlayerAdapter> viewers,
      Consumer<WorldLease> onReady, Runnable onFailure) {
    if (!enabled() || shuttingDown || templateWorldName == null || templateWorldName.isBlank()) {
      acquireOrCreate(viewers, onReady, onFailure);
      return;
    }
    stageThenAcquireClone(templateWorldName, viewers, onReady, onFailure, false);
  }

  @Override
  public void acquireCloneStrict(String templateWorldName, Collection<? extends PlayerAdapter> viewers,
      Consumer<WorldLease> onReady, Runnable onFailure) {
    if (!enabled() || shuttingDown || templateWorldName == null || templateWorldName.isBlank()) {
      fail(onFailure);
      return;
    }
    stageThenAcquireClone(templateWorldName, viewers, onReady, onFailure, true);
  }

  /**
   * How long the STAGING copy waits for the world root's shared lock. Generous, because it waits on the
   * staging thread: nobody is standing still, so outwaiting a whole seeding pass costs one match a
   * slightly later start instead of costing every player on the node a frozen server. That is the entire
   * point of this hoist — the same wait on the world thread had to be capped at half a second.
   *
   * <p>Bounded rather than {@link MapBundle#WAIT_FOREVER} even so: staging is a single-threaded queue, so
   * one copy blocked for ever behind a crashed lock holder would stop every later match from starting
   * too. Past the budget the copy runs unlocked but verified, exactly like the world-thread path.</p>
   */
  private static final long STAGE_LOCK_WAIT_MILLIS = 30_000L;

  /**
   * Copies the template off the world thread when the backend supports it, then acquires the world on the
   * world thread. Both clone entry points funnel through here so the staged and unstaged paths cannot
   * drift apart.
   */
  private void stageThenAcquireClone(String templateWorldName, Collection<? extends PlayerAdapter> viewers,
      Consumer<WorldLease> onReady, Runnable onFailure, boolean strict) {
    // Built here, on the caller's thread, because the copy needs to know the destination before it can be
    // handed off — which is why the sequence counter is atomic.
    WorldRequest request = cloneRequest(templateWorldName);
    Path staging = backendCloneStagingFolder(request);
    if (staging == null) {
      acquireCloned(request, templateWorldName, viewers, onReady, onFailure, strict);
      return;
    }
    try {
      stagingExecutor().execute(() -> {
        try {
          stageCloneTemplate(request, staging);
        } catch (RuntimeException exception) {
          logger.warning("Could not stage the clone of '" + templateWorldName + "' into " + staging + ": "
              + exception.getMessage());
          if (!deleteDirectory(staging)) {
            // The world thread decides "this was staged" from the folder being THERE and has no other
            // channel to be told otherwise, so a half-written folder that could not be unlinked is
            // adopted as a complete copy of the template: the match starts on a map with holes in it.
            logger.severe("The half-staged clone folder '" + staging + "' could not be removed. A world"
                + " created from it holds only part of the template '" + templateWorldName
                + "'; remove that folder by hand before the next match on this map.");
          }
        }
        acquireCloned(request, templateWorldName, viewers, onReady, onFailure, strict);
      });
    } catch (java.util.concurrent.RejectedExecutionException shuttingDownNow) {
      // Raced a shutdown between the check above and the submit. Take the world-thread path, which sees
      // the flag and fails cleanly instead of dropping the callback on the floor.
      acquireCloned(request, templateWorldName, viewers, onReady, onFailure, strict);
    }
  }

  /**
   * The world-thread half of a clone: create/load the world and hand the lease out. Unchanged from when
   * the copy lived inside it — including, deliberately, the fact that {@code onReady} is only ever called
   * from in here. A staged copy that is still running has not reached this method, so there is no window
   * in which a match could start on a half-copied map, and no extra latch is needed to say so.
   */
  private void acquireCloned(WorldRequest request, String templateWorldName,
      Collection<? extends PlayerAdapter> viewers, Consumer<WorldLease> onReady, Runnable onFailure,
      boolean strict) {
    runOnWorldThread(() -> {
      if (shuttingDown) {
        fail(onFailure);
        return;
      }
      Optional<WorldHandle> created = backendAcquire(request, true);
      if (created.isEmpty() && strict) {
        // NO vanilla fallback: the editor must never drop the admin into a generated world they would then
        // save over the real template. A failed clone is a hard error the caller reports. The admin is
        // protected by that caller's message, but the CAUSE still deserves the same diagnosis the match
        // path logs — "clone failed" alone sends the reader hunting a missing map when the real cause is a
        // concurrent map refresh.
        logger.severe(describeCloneFailure(templateWorldName));
        fail(onFailure);
        return;
      }
      // A missing/failed template degrades to a freshly generated world so the match still starts — but
      // NEVER quietly: the match then runs on random terrain while the mode still reads its team spawns
      // and base regions from the template's sidecars, so players are teleported into stone or into the
      // air, no TNT base exists, and the win condition (destroy the enemy base) can never be met. That
      // used to produce zero log lines; "the server bugged" was the only symptom anybody ever saw.
      if (created.isEmpty()) {
        reportDegradedClone(templateWorldName, viewers);
        created = backendAcquire(tempRequest(), true);
      }
      if (created.isPresent()) {
        registerLeased(created.get());
        onReady.accept(new LeasedWorld(this, created.get()));
      } else {
        fail(onFailure);
      }
    });
  }

  /**
   * OFF the world thread: copies the template's chunk data into the folder the backend will create the
   * clone world from, under the world root's SHARED lock so a map refresh (this node's or another node's)
   * cannot rewrite the template halfway through the walk.
   *
   * <p>Best effort by design. Anything that goes wrong leaves NO staged folder behind, and the world
   * thread then clones — and diagnoses — exactly as it did before this hoist existed. That is what keeps
   * a backend's failure reporting in one place instead of two that can disagree.</p>
   */
  private void stageCloneTemplate(WorldRequest request, Path staging) {
    String template = request.templateRuntimeName();
    if (template == null || template.isBlank()) {
      return;
    }
    Path source = worldRoot().resolve(template.replace('\\', '/'));
    if (!Files.isDirectory(source)) {
      return; // nothing to stage; the backend reaches the same (and only) diagnosis on the world thread
    }
    // The lock is taken on the root that really holds `source`: on the network layout the template is a
    // symlink out of this node's world root into the shared map tree, and the seeding writer locks THERE.
    WorldClone.ChunkCopyResult result = MapBundle.underSharedWorldRootLock(worldRoot(), source, logger,
        STAGE_LOCK_WAIT_MILLIS, lockHeld -> {
          WorldClone.ChunkCopyResult attempt = WorldClone.copyChunkDataChecked(source, staging);
          if (attempt.failure() == WorldClone.CloneFailure.TEMPLATE_CHANGED && !lockHeld) {
            // Read while somebody was rewriting the folder AND without the lock that would have stopped
            // them. The writer publishes by rename, so the window is microseconds wide and one retry lands
            // after it — better than falling into the generated-world fallback.
            attempt = WorldClone.copyChunkDataChecked(source, staging);
          }
          return attempt;
        });
    if (!result.ok() && !deleteDirectory(staging)) {
      // A half-written staging folder is the one outcome that must never survive: the backend adopts a
      // staged folder without re-reading the template, so leaving a torn one there would start the match
      // on a map with holes in it — the exact failure the checked copy exists to prevent. It reads
      // "staged" off the folder's existence and there is no channel to tell it otherwise, so an unlink
      // that failed has to be said out loud here or it is said nowhere at all.
      logger.severe("The staged clone of template '" + template + "' at '" + staging + "' failed"
          + " verification (" + result.failure() + ") AND could not be removed. A world created from it"
          + " holds only part of the template; remove that folder by hand.");
    }
  }

  private ExecutorService stagingExecutor() {
    ExecutorService existing = cloneStaging;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      if (cloneStaging == null) {
        cloneStaging = Executors.newSingleThreadExecutor(runnable -> {
          Thread thread = new Thread(runnable, "sexidium-clone-staging");
          thread.setDaemon(true);
          return thread;
        });
      }
      return cloneStaging;
    }
  }

  /** The backup copy thread. See {@link #backupStaging} for why it is not {@link #stagingExecutor()}. */
  private ExecutorService backupExecutor() {
    ExecutorService existing = backupStaging;
    if (existing != null) {
      return existing;
    }
    synchronized (this) {
      if (backupStaging == null) {
        backupStaging = Executors.newSingleThreadExecutor(runnable -> {
          Thread thread = new Thread(runnable, "sexidium-backup-staging");
          thread.setDaemon(true);
          thread.setPriority(Thread.MIN_PRIORITY);
          return thread;
        });
      }
      return backupStaging;
    }
  }

  /**
   * Logs, at SEVERE, that a match is about to start on a GENERATED world instead of the requested map, and
   * warns whoever is being sent there. Called from the one place that degrades silently by design.
   *
   * <p>Player-facing warning goes out on the action bar because that is the only messaging seam a
   * {@link PlayerAdapter} exposes to core; a localized chat line would need a {@code MessageAdapter}, which
   * this class is not constructed with. Deliberately unlocalized: it is a fault report, and an admin
   * reading a screenshot needs to recognise it.</p>
   */
  private void reportDegradedClone(String templateWorldName, Collection<? extends PlayerAdapter> viewers) {
    logger.severe(describeCloneFailure(templateWorldName)
        + " The match is starting on a GENERATED world: team spawns, bases and the win condition will NOT"
        + " exist, so the match cannot be completed. Stop the match, restore the map on this node (a boot"
        + " re-extracts the bundled maps), and never refresh maps while matches are running.");
    if (viewers == null) {
      return;
    }
    for (PlayerAdapter viewer : viewers) {
      if (viewer != null && viewer.online()) {
        viewer.sendActionBar("<red>Map '" + templateWorldName + "' could not be loaded — this match is broken.");
      }
    }
  }

  /** Diagnoses WHY the template could not be cloned, by inspecting the folder the clone reads from. */
  private String describeCloneFailure(String templateWorldName) {
    Path template = worldRoot().resolve(templateWorldName.replace('\\', '/'));
    WorldClone.ChunkCopyResult inspection = WorldClone.inspect(template);
    // A template that inspects CLEAN right after a failed clone means the copy itself was refused: either
    // it drifted mid-read (another JVM re-extracting the bundle, or an editor save) or IO failed. Saying
    // "no region data found" there — as the platform clone used to — sends the reader down the wrong path.
    String reason = inspection.ok()
        ? "the map folder looks complete NOW, so the copy was refused mid-flight — another process was"
            + " rewriting it (map re-extract on boot, or an editor save), or the disk failed"
        : inspection.failure() + ": " + inspection.detail();
    return "Could not clone map template '" + templateWorldName + "' (" + template + ") — " + reason + ".";
  }

  @Override
  public Optional<WorldLease> reacquireByName(String worldName) {
    if (worldName == null || worldName.isBlank()) {
      return Optional.empty();
    }
    if (naming.isLobby(worldName)) {
      return backendLobby().map(handle -> new LeasedWorld(this, handle));
    }
    // An experience must NEVER come through here. This method's create-if-missing fallback is
    // `backendAcquire(tempRequestFor(name), true)` -- for a snapshot whose world is gone it would
    // generate a brand-new world under a name a player's save answers to, on any node, with no
    // placement claim of any kind. Persistent worlds have exactly one door and it takes a WorldKey.
    if (naming.isExperience(worldName)) {
      logger.severe("Refusing to reacquire '" + worldName + "' by name: it is an experience world, and"
          + " this path may create one. Experience worlds are opened only through"
          + " acquireOrCreatePersistent, which takes a claim first.");
      return Optional.empty();
    }
    Optional<WorldHandle> loaded = backendResolveLoaded(worldName, WorldKind.TEMP);
    WorldHandle handle = loaded.orElseGet(() -> backendAcquire(tempRequestFor(worldName), true).orElse(null));
    if (handle == null) {
      return Optional.empty();
    }
    registerLeased(handle);
    return Optional.of(new LeasedWorld(this, handle));
  }

  @Override
  public void discardByName(String worldName) {
    if (worldName == null || worldName.isBlank()) {
      return;
    }
    registry.unpreserve(worldName);
    if (naming.isLobby(worldName) || naming.isExperience(worldName)) {
      return;
    }
    backendResolveLoaded(worldName, WorldKind.TEMP).ifPresent(handle -> dispose(handle, true));
  }

  // ===== WorldLeaseService: persistent experience worlds =======================================

  @Override
  public void acquireOrCreatePersistent(WorldKey key, Collection<? extends PlayerAdapter> viewers,
      WorldGeneration generation, Consumer<WorldLease> onReady, Runnable onFailure) {
    if (key == null) {
      fail(onFailure);
      return;
    }
    if (shuttingDown) {
      fail(onFailure);
      return;
    }
    // NOTE: the EXPERIENCES door guard is NOT checked here. It is checked below, after the placement
    // gate has decided who owns this world, and the order is load-bearing rather than tidy.
    //
    // Checked first, it returned before the gate ran -- and the gate is the ONLY thing that mints an
    // EXPERIENCE transfer ticket. The lobby is the one backend role without EXPERIENCES and the one
    // node the proxy sends every player to, so a player clicking Enter in the lobby was refused
    // locally, routed nowhere, and told the world had failed. The whole placement layer was
    // unreachable from the only node anyone starts on. The guard's job is to stop this node OPENING
    // an experience world, not to stop it working out which node should.
    // Ask the network who owns this world BEFORE creating anything. Without this a node that
    // does not have the folder would fall through to backendAcquire and generate a fresh, empty
    // world under the same name -- silently replacing a player's saved map.
    //
    // BEFORE the already-loaded fast path, not after it. The fast path used to sit above this and
    // serve any world this node happened to have open, claim or no claim -- so a world repointed to
    // a peer (by a reaper, or by an ordinary takeover) kept being handed out here while the table
    // said somebody else owned it, and this node went on asking to renew a row that was no longer
    // its own. A world we may not serve must not be served merely because it is already open.
    WorldRequest request = persistentRequest(key, generation);
    String placementKey = key.key();
    WorldPlacementGate.Decision decision = placementGate.check(placementKey);
    if (!decision.allowed()) {
      logger.info("'" + placementKey + "' is homed on node '" + decision.ownerNodeId() + "'"
          + (decision.busy() ? " and currently loaded there" : "")
          + "; routing " + viewers.size() + " player(s) there instead of opening it here.");
      // The SAME key the placement table is keyed by travels in the handoff. It used to be routed by
      // the caller's spelling instead, because the two were genuinely different strings and the
      // destination looked the experience up by the owner-scoped world_name it had stored. There is
      // one spelling now, so the distinction -- and the class of bug it produced -- is gone.
      placementRouter.route(placementKey, decision.ownerNodeId(), viewers);
      fail(onFailure);
      return;
    }

    // The claim held. Remember it: every guarded write this node makes about the world from here on
    // carries it, and losing it is how this node learns it has been evicted (see onLeaseLost).
    WorldClaim claim = placementGate.lastClaim();
    if (claim != null) {
      claims.put(request.runtimeName(), claim);
    }

    // I3, enforced here rather than at the top of the method (see the note above). Reaching this
    // point means the planner picked THIS node -- which for a node without EXPERIENCES only happens
    // when no capable node was alive to pick, and PlacementPlanner deliberately answers "me" there
    // rather than inventing a host. Hand the claim straight back: creating the folder on the one node
    // that must not have it is worse than telling the player nobody can host it right now.
    if (!mayOpenExperience(key, "open")) {
      rollback(claim, request);
      fail(onFailure);
      return;
    }

    // An already-open world is served without re-opening anything.
    Optional<WorldHandle> loaded = backendResolveLoaded(request.runtimeName(), WorldKind.PERSISTENT);
    if (loaded.isPresent()) {
      registerLeased(loaded.get());
      onReady.accept(new LeasedWorld(this, loaded.get()));
      return;
    }

    // ===== I5: a world is NEVER created for an experience that already exists ==================
    //
    // "No folder on disk" used to mean "generate one", implicitly, as a fall-through. That is the
    // single most expensive default in the system: on a shared tree a node that cannot see a folder
    // for any reason -- a stale name, a slow mount, a lineage pointer that drifted -- would generate
    // a fresh empty world under a name a player's save answers to, and the warm pool made it instant
    // and silent. So creation is now something a caller has to ASK for and something this method has
    // to AGREE to, separately.
    boolean onDisk = backendExistsOnDisk(request);
    CreatePolicy policy = onDisk ? CreatePolicy.LOAD_ONLY : CreatePolicy.CREATE_IF_MISSING;
    if (!onDisk) {
      // Absent HERE. Before anything is created, prove no other generation of this run exists: a
      // reset renames the world, and whatever holds a stale name would otherwise be handed a
      // brand-new world while the real save sits in the next folder along.
      String newerGeneration = newerGenerationOnDisk(placementKey);
      if (newerGeneration != null) {
        logger.severe("Refusing to create experience world '" + request.runtimeName() + "': its folder"
            + " is gone, but generation '" + newerGeneration + "' of the same run IS on disk. Whoever"
            + " asked is holding a stale world key (check experiences.world_key). Nothing was created"
            + " and nobody was moved.");
        rollback(claim, request);
        fail(onFailure);
        return;
      }
      if (!mayCreate.test(key)) {
        // The last gate before terrain is generated. A caller that is RESUMING an experience has no
        // business creating one, and the difference used to be invisible at this point.
        logger.severe("Refusing to create experience world '" + request.runtimeName() + "': its folder"
            + " does not exist and this request is not allowed to create one. Nothing was created.");
        rollback(claim, request);
        fail(onFailure);
        return;
      }
      logger.info("Creating experience world '" + request.runtimeName()
          + "' (no folder anywhere for it, and no later generation of the same run).");
    }

    runOnWorldThread(() -> {
      Optional<WorldHandle> handle = policy == CreatePolicy.CREATE_IF_MISSING
          ? adoptForNewWorld(request)
          : Optional.empty();
      if (handle.isEmpty()) {
        // The ONE place a persistent world is created, and the flag is now derived from an explicit
        // policy rather than being a hard-coded `true`.
        handle = backendAcquire(request, policy == CreatePolicy.CREATE_IF_MISSING);
      }
      if (handle.isPresent()) {
        registerLeased(handle.get());
        confirmOpen(request.runtimeName());
        onReady.accept(new LeasedWorld(this, handle.get()));
      } else {
        // ROLLBACK. Without it the row stayed RESERVED with a real epoch and a live lease for a world
        // that does not exist, so every later entry was routed to a node with no folder -- which then
        // generated a fresh one there. There was no un-claim operation at all.
        rollback(claim, request);
        fail(onFailure);
      }
    });
  }

  /**
   * Whether a request may CREATE the world it names, as opposed to only loading an existing one.
   *
   * <p>Defaults to yes, so a standalone server and every existing caller behave exactly as before.
   * The seam exists so a resume path can say no: "the folder is missing" and "make me a world" are
   * two different requests, and conflating them is how an empty world gets generated over a save.</p>
   */
  private volatile java.util.function.Predicate<WorldKey> mayCreate = key -> true;

  public void setCreatePolicy(java.util.function.Predicate<WorldKey> mayCreate) {
    this.mayCreate = mayCreate == null ? key -> true : mayCreate;
  }

  /**
   * The claim backing each world this node has open, keyed by runtime name.
   *
   * <p>The fence lives here rather than on the handle because a handle outlives a claim: a world can
   * be evicted while it is still open, and that is exactly the case this exists to detect.</p>
   */
  private final java.util.Map<String, WorldClaim> claims =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** The claim on an open world, or null. */
  public WorldClaim claimFor(String runtimeName) {
    return claims.get(runtimeName);
  }

  private void confirmOpen(String runtimeName) {
    WorldClaim claim = claims.get(runtimeName);
    if (claim != null) {
      leaseAuthority.confirmOpen(claim);
    }
  }

  /** Give a claim back for a world that was never opened. */
  private void rollback(WorldClaim claim, WorldRequest request) {
    if (claim == null) {
      return;
    }
    claims.remove(request.runtimeName());
    if (leaseAuthority.unclaim(claim)) {
      logger.info("Released the claim on '" + request.runtimeName()
          + "': nothing was ever opened for it.");
    }
  }

  /**
   * The authority this node renews its claims against. No-op by default, so standalone is untouched.
   */
  private volatile com.sexidium.core.world.WorldLeaseAuthority leaseAuthority = NO_AUTHORITY;

  public void setLeaseAuthority(com.sexidium.core.world.WorldLeaseAuthority authority) {
    this.leaseAuthority = authority == null ? NO_AUTHORITY : authority;
  }

  /**
   * Invariant I4: a holder whose renewal is refused freezes writes NOW and unloads within one lease.
   *
   * <p>There was no such path at all. A node that lost a world went on serving it, and went on
   * writing to region files another node had open — because its own renew was guarded on node id
   * alone and silently matched nothing. Called by the lease heartbeat when {@code renew} answers
   * false.</p>
   *
   * <p>{@code save = false} is deliberate and is the whole reason this is safe. Another node may
   * already be writing those region files; flushing a stale chunk cache over its writes is strictly
   * worse than losing the seconds since the last autosave.</p>
   */
  public void onLeaseLost(WorldClaim claim) {
    if (claim == null) {
      return;
    }
    String runtimeName = claim.key().runtimeName(naming.experiencesNamespace());
    claims.remove(runtimeName);
    Optional<WorldHandle> handle = backendResolveLoaded(runtimeName, WorldKind.PERSISTENT);
    if (handle.isEmpty()) {
      return;
    }
    logger.severe("Evicting '" + runtimeName + "': this node no longer holds its claim. Players are"
        + " being moved out and the world is being unloaded WITHOUT saving, because another node may"
        + " already be writing these region files.");
    runOnWorldThread(() -> {
      // `closing` first: it is what stops the acquire path re-serving a world we no longer own while
      // the teleports settle. An experience is THREE worlds, so the siblings fall first -- they are
      // created outside the registry and outside the lease, and leaving a Nether open while a peer
      // opens the same experience is the same corruption by a quieter route.
      closing.add(runtimeName);
      releaseSiblings(handle.get());
      evacuate(handle.get());
      registry.removeLeased(runtimeName);
      registry.removeManaged(runtimeName);
      leaseRefs.remove(runtimeName);
      unloadEvicted(handle.get(), UNLOAD_ATTEMPTS);
    });
  }

  /**
   * Close one persistent world on purpose and hand its claim back — the drain's path.
   *
   * <p>The drain's only way to hand a world over was to END ITS MATCH, and a world does not have to
   * have one: {@code openPersistentPlacementKeys()} also reports everything in {@code closing}, which
   * is documented as "closing, <em>or failed to close</em>". One failed unload therefore pinned
   * {@code worlds_left} at 1 for ever — no match to end, no other handover path — and the drain
   * STALLED at its deadline on a node that was otherwise perfectly healthy, taking the whole roll
   * with it.</p>
   *
   * <p>Saves on the way out, unlike {@link #onLeaseLost}: this node still holds the claim and is the
   * only writer, so the chunk cache is ours to flush. A world that will not close keeps its claim and
   * answers false, and the drain simply tries again on its next tick.</p>
   *
   * @return true when there is nothing left for the drain to do with this key
   */
  public boolean handOverPersistent(WorldKey key) {
    if (key == null) {
      return false;
    }
    String runtimeName = persistentRequest(key).runtimeName();
    Optional<WorldHandle> handle = backendResolveLoaded(runtimeName, WorldKind.PERSISTENT);
    if (handle.isEmpty()) {
      // Not open here at all. Either it never was, or it is a `closing` entry whose world is long
      // gone -- a stuck marker that nothing else removes, and which the drain would otherwise wait
      // out its whole deadline behind.
      closing.remove(runtimeName);
      registry.removeLeased(runtimeName);
      leaseRefs.remove(runtimeName);
      releaseClaim(runtimeName);
      return true;
    }
    boolean[] handedOver = {false};
    runOnWorldThread(() -> {
      closing.add(runtimeName);
      releaseSiblings(handle.get());
      evacuate(handle.get());
      if (!backendUnload(handle.get(), true)) {
        // Players are teleported out asynchronously, so the first attempt commonly fails with them
        // still inside. Saying so once per drain tick is enough: the next tick retries.
        logger.warning("Could not yet close '" + runtimeName + "' for the drain; this node keeps its"
            + " claim and will try again on the next tick.");
        return;
      }
      registry.removeLeased(runtimeName);
      registry.removeManaged(runtimeName);
      leaseRefs.remove(runtimeName);
      backendForgetRegistration(runtimeName);
      closing.remove(runtimeName);
      releaseClaim(runtimeName);
      handedOver[0] = true;
    });
    return handedOver[0];
  }

  /** Give back the claim on a world that is really closed. No-op when there was none (standalone). */
  private void releaseClaim(String runtimeName) {
    WorldClaim claim = claims.remove(runtimeName);
    if (claim != null) {
      leaseAuthority.release(claim);
    }
  }

  /**
   * Unload a world this node has been evicted from, retrying while the evacuation settles.
   *
   * <p>Mirrors {@link #releasePersistent(WorldHandle, int)} and differs in the two ways that matter.
   * It never saves — another node may already be writing these region files. And it never stops
   * because the world was re-leased: on the release path a fresh lease means somebody legitimately
   * took the world back, while here it means somebody is being served a world we do not own.</p>
   *
   * <p>A single unattempted unload was the bug: {@code unloadWorld} returns false while a player is
   * still inside, evacuation teleports asynchronously, and the result was discarded — so the evicted
   * node kept the region files open, with its claim already dropped, while the new holder wrote
   * them.</p>
   */
  private void unloadEvicted(WorldHandle handle, int attemptsLeft) {
    if (shuttingDown) {
      return;
    }
    String runtimeName = handle.runtimeName();
    if (backendUnload(handle, false)) {
      closing.remove(runtimeName);
      // A world we have been evicted from must not stay on this node's autoload list: the next boot
      // would open it again, before the plugin exists and with no claim of any kind.
      backendForgetRegistration(runtimeName);
      logger.info("Evicted world '" + runtimeName + "' is unloaded; this node is no longer holding"
          + " its region files open.");
      return;
    }
    if (attemptsLeft <= 1) {
      logger.severe("EVICTED from '" + runtimeName + "' but could not unload it after "
          + UNLOAD_ATTEMPTS + " attempts. This node may still be holding its region files open while"
          + " another node writes them. Get everyone out of that world and restart this node.");
      return;
    }
    runOnWorldThreadLater(() -> unloadEvicted(handle, attemptsLeft - 1), UNLOAD_RETRY_TICKS);
  }

  /**
   * Where occupants go when this node has no lobby world of its own to put them in.
   *
   * <p>No-op by default, so standalone and every test are untouched: a standalone server always has
   * a lobby, and this only fires when it does not.</p>
   */
  private volatile java.util.function.Consumer<java.util.Collection<PlayerAdapter>> evacuationFallback =
      players -> { };

  public void setEvacuationFallback(
      java.util.function.Consumer<java.util.Collection<PlayerAdapter>> fallback) {
    this.evacuationFallback = fallback == null ? players -> { } : fallback;
  }

  /** Every claim this node currently holds, for the lease heartbeat. */
  public java.util.Collection<WorldClaim> heldClaims() {
    return java.util.List.copyOf(claims.values());
  }

  private static final com.sexidium.core.world.WorldLeaseAuthority NO_AUTHORITY =
      new com.sexidium.core.world.WorldLeaseAuthority() {
        @Override public com.sexidium.core.world.ClaimOutcome claim(WorldKey key, String ownerUuid) {
          return new com.sexidium.core.world.ClaimOutcome.Unavailable("standalone");
        }
        @Override public boolean renew(WorldClaim claim, int players) { return true; }
        @Override public boolean confirmOpen(WorldClaim claim) { return true; }
        @Override public boolean release(WorldClaim claim) { return true; }
        @Override public boolean unclaim(WorldClaim claim) { return true; }
        @Override public WorldKey allocateNextGeneration(String experienceId, WorldKey current) {
          return current == null ? null : current.nextGeneration();
        }
        @Override public Optional<ExperienceLocator.Placement> locate(WorldKey key) {
          return Optional.empty();
        }
        @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }
        @Override public List<ExperienceLocator.Placement> heldBy(String nodeId) { return List.of(); }
      };

  /**
   * Serves a brand-new world from the warm pool when one of the right shape is ready. Only ever used for
   * a world that does not exist yet — an experience being played again must load its own saved world, not
   * be handed a fresh one — and the pooled world is put back to work rather than discarded if the backend
   * cannot adopt it. Returns empty when nothing warm fits, and the caller generates as before.
   */
  private Optional<WorldHandle> adoptForNewWorld(WorldRequest request) {
    if (!enabled() || shuttingDown || backendExistsOnDisk(request)) {
      return Optional.empty();
    }
    WorldProfile profile = WorldProfile.of(request.settings());
    Optional<WorldHandle> pooled = takePooled(profile);
    if (pooled.isEmpty()) {
      return Optional.empty();
    }
    Optional<WorldHandle> adopted;
    try {
      adopted = backendAdopt(pooled.get(), request);
    } catch (RuntimeException exception) {
      logger.warning("Could not adopt a pooled " + profile + " world for '" + request.runtimeName()
          + "': " + exception.getMessage());
      adopted = Optional.empty();
    }
    if (adopted.isEmpty()) {
      // The warm world was taken but never used; it must not be leaked as an orphan folder.
      dispose(pooled.get(), true);
      return Optional.empty();
    }
    logger.info("Served '" + request.runtimeName() + "' from the warm " + profile
        + " pool (no terrain generated).");
    return adopted;
  }

  // reacquirePersistent used to live here. It loaded an experience world with NO placement check of
  // any kind -- not the gate, not the capability -- and it was reached from PendingMatchStore.rehydrate
  // on every single join, off match rows every node imported from every other node. That is the whole
  // of finding A1: the lobby restarts, reads worker-2's live match row, indexes worker-2's players as
  // pending, and the next reconnect opens the region files worker-2 has open. There is nothing to
  // replace it with, because experience_players plus ExperienceStateStore already carry everything a
  // returning player needs and both go through the gated entry path.

  // ===== experience backup: whole-folder copy under a new key ==================================

  /** Suffix of the folder a backup is copied into before being renamed onto its final path. */
  private static final String BACKUP_STAGING_SUFFIX = ".incoming-";

  /**
   * How many times ONE dimension folder is copied-and-verified before a drifting source is given up on.
   *
   * <p>This is the ONLY retry in the copy path, and it is deliberately here rather than around the
   * whole verb: a second loop at the service layer would multiply with this one (three attempts each,
   * nine copies of a 290 MB tree), and it would re-run the parts that already succeeded — a source key
   * lookup, a disk-space measurement, two dimensions that copied cleanly — to fix a drift in the third.
   * The retry belongs where the drift is detected.</p>
   *
   * <p>Bounded, and small, because the failure it retries is not always transient. A world nobody is in
   * settles on the first or second attempt; a world under active play may legitimately never settle,
   * and a retry loop that waits for it would hold a backup thread and a staging tree for as long as the
   * session lasts. Giving up and reporting FAILED is the correct end of that story — the owner reads
   * "try again when it is quieter", which is true.</p>
   */
  static final int COPY_ATTEMPTS = 3;

  @Override
  public boolean experienceWorldLoaded(WorldKey key) {
    if (key == null) {
      return false;
    }
    for (String suffix : keyAndSiblingSuffixes()) {
      String runtimeName = naming.experienceRuntimeName(key.key() + suffix);
      // `closing` as well as "loaded": a world whose players have just left is unloading, its chunk
      // writes have not landed, and it is exactly the world an impatient owner asks to back up.
      if (closing.contains(runtimeName)
          || backendResolveLoaded(runtimeName, WorldKind.PERSISTENT).isPresent()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean saveExperienceNow(WorldKey key) {
    if (key == null) {
      return false;
    }
    boolean issued = false;
    for (String suffix : keyAndSiblingSuffixes()) {
      String runtimeName = naming.experienceRuntimeName(key.key() + suffix);
      Optional<WorldHandle> handle = backendResolveLoaded(runtimeName, WorldKind.PERSISTENT);
      if (handle.isEmpty()) {
        // Not open here. A dimension nobody has entered has nothing in memory to lose, and a world
        // held by ANOTHER node cannot be flushed from here at all -- which is one of the reasons the
        // copy that follows still has to be verified rather than trusted.
        continue;
      }
      try {
        issued |= backendSaveWorld(handle.get());
      } catch (RuntimeException refused) {
        // A save that blew up is not a reason to refuse the copy: the copy is verified either way, and
        // turning "the flush did not happen" into "you cannot have a backup" would be a strictly worse
        // trade than copying whatever is on disk and letting the bracket judge it.
        logger.warning("Could not flush '" + runtimeName + "' before copying it: " + refused
            + ". The copy still runs, and is still verified against the folder it reads.");
      }
    }
    return issued;
  }

  @Override
  public boolean experienceKeyFree(WorldKey key) {
    return key != null && isFreeExperienceKey(key.key());
  }

  @Override
  public boolean experienceFolderPresent(WorldKey key) {
    // The OVERWORLD folder alone. A linked sibling is optional by design — an experience that has never
    // had a Nether has no `_nether` folder, and demanding one would refuse a restore of a perfectly
    // good world for a dimension nobody ever entered.
    //
    // PRESENCE, and nothing else. On the live deployment this directory is reached through a symlink
    // into shared storage — same device, same inode, from all three Paper nodes — so this is true on
    // every node for every experience and can never distinguish "I hold this" from "somebody else
    // does". Whoever needs the second question asks the placement table; see WorldLeaseService.
    return key != null && Files.isDirectory(experienceFolderFor(key.key()));
  }

  /**
   * Free bytes we insist on leaving behind after a copy, over and above the copy itself.
   *
   * <p>A world tree filled to the last byte is a server that cannot write its own logs. This is the
   * cushion, not a guess at the copy size — that is measured.</p>
   */
  private static final long COPY_FREE_SPACE_MARGIN_BYTES = 512L * 1024L * 1024L;

  @Override
  public boolean roomToCopyExperience(WorldKey source) {
    if (source == null) {
      return true;
    }
    long needed = 0L;
    for (String suffix : keyAndSiblingSuffixes()) {
      Path folder = experienceFolderFor(source.key() + suffix);
      if (Files.isDirectory(folder)) {
        needed += treeSize(folder);
      }
    }
    if (needed <= 0L) {
      // Nothing measurable to copy — either it is not here or the walk failed. Either way this is not
      // the gate that should refuse it; the copy itself answers honestly a moment later.
      return true;
    }
    try {
      Path root = experiencesDiskRoot().toAbsolutePath();
      while (root != null && !Files.exists(root)) {
        root = root.getParent();
      }
      if (root == null) {
        return true;
      }
      long usable = Files.getFileStore(root).getUsableSpace();
      if (usable <= 0L) {
        return true; // the file store would not say; do not invent a refusal
      }
      boolean fits = usable >= needed + COPY_FREE_SPACE_MARGIN_BYTES;
      if (!fits) {
        logger.severe("Refusing to copy experience world '" + source.key() + "': it needs about "
            + (needed / (1024L * 1024L)) + " MB plus headroom, and only "
            + (usable / (1024L * 1024L)) + " MB is free on this node. Nothing was copied.");
      }
      return fits;
    } catch (java.io.IOException | RuntimeException unreadable) {
      // Same rule as everywhere else here: a measurement we could not take is not a refusal.
      return true;
    }
  }

  /** Bytes under a folder, best effort. Zero when it cannot be walked — never an exception. */
  private static long treeSize(Path folder) {
    try (java.util.stream.Stream<Path> walk = Files.walk(folder)) {
      return walk.filter(Files::isRegularFile).mapToLong(path -> {
        try {
          return Files.size(path);
        } catch (java.io.IOException unreadable) {
          return 0L;
        }
      }).sum();
    } catch (java.io.IOException | RuntimeException unreadable) {
      return 0L;
    }
  }

  @Override
  public void copyExperienceWorld(WorldKey source, WorldKey destination,
      Consumer<Path> beforePublish, Consumer<Boolean> onDone) {
    Consumer<Boolean> tell = onDone == null ? done -> { } : onDone;
    if (source == null || destination == null || source.equals(destination) || !enabled() || shuttingDown) {
      tell.accept(false);
      return;
    }
    if (!mayOpenExperience(source, "copy")) {
      tell.accept(false);
      return;
    }
    try {
      // Its OWN thread, not the clone staging queue. A backup must never be the reason a match start
      // waits — and sharing one single-threaded executor made it exactly that, for as long as the disk
      // took to walk a 290 MB tree. See backupStaging.
      backupExecutor().execute(() -> {
        boolean copied = !shuttingDown && copyExperienceFolders(source, destination, beforePublish);
        if (copied && shuttingDown) {
          // Raced a stop after the folders landed. The row is written by the world-thread continuation
          // below, which will not run — so these folders would be an orphan nothing names.
          // Not read: the answer is false either way, and what could not be removed is reported inside.
          discardExperienceFolders(destination);
          copied = false;
        }
        boolean landed = copied;
        runOnWorldThread(() -> {
          if (shuttingDown && landed) {
            // Not read, for the same reason as above: the caller is being told false regardless, and a
            // folder that would not go is logged where it was found.
            discardExperienceFolders(destination);
            tell.accept(false);
            return;
          }
          tell.accept(landed);
        });
      });
    } catch (java.util.concurrent.RejectedExecutionException stoppingNow) {
      // Raced a shutdown between the flag check and the submit. Nothing was written.
      tell.accept(false);
    }
  }

  /**
   * OFF the world thread: copies each dimension folder of {@code source} into a staging sibling of its
   * destination, verifies it, lets the caller rewrite what names the source, then publishes by rename.
   *
   * <p>All-or-nothing. The moment anything is refused, every staging folder AND every folder already
   * published is removed, so the outcome is never "two of the three dimensions". A missing sibling is
   * not a failure — an experience that has never had a Nether has no {@code _nether} folder, and
   * inventing one would be inventing terrain.</p>
   */
  private boolean copyExperienceFolders(WorldKey source, WorldKey destination, Consumer<Path> beforePublish) {
    List<Path> staged = new ArrayList<>();
    List<Path> published = new ArrayList<>();
    boolean anything = false;
    // Set at the ONE point this returns true. Nothing else may stand in for it -- see the finally.
    boolean success = false;
    try {
      for (String suffix : keyAndSiblingSuffixes()) {
        Path from = experienceFolderFor(source.key() + suffix);
        if (!Files.isDirectory(from)) {
          if (suffix.isEmpty()) {
            // The OVERWORLD, and a backup with no overworld is nothing. A missing SIBLING is not a
            // failure -- an experience that has never had a Nether has no `_nether` folder, and
            // inventing one would be inventing terrain -- but this `continue` used to apply to the
            // overworld too, so a source whose main folder was absent while a stale sibling was not
            // published that sibling alone and recorded it as a complete copy of the world.
            logger.severe("Refusing to copy experience world '" + source.key() + "': its overworld"
                + " folder '" + from + "' is not there, and a copy with no overworld is not a copy of"
                + " that world. Nothing was kept.");
            return false;
          }
          continue;
        }
        if (!suffix.isEmpty()) {
          // A LINKED dimension whose folder exists but holds no chunk data is skipped, not refused.
          // Such a folder is what a crash right after the dimension was created leaves behind — the
          // directory landed, no region ever did. It carries no terrain, and this codebase already
          // agrees it is not there: backendExistsOnDisk and isFreeExperienceKey both test for
          // `region/`, and a missing sibling is explicitly not a failure two lines above. Refusing it
          // would make the whole experience permanently un-backup-able through no fault of the owner:
          // every attempt would fail identically, for ever, with nothing they could do about it.
          // The overworld (suffix "") is NOT covered by this — a backup with no overworld is nothing.
          WorldClone.ChunkCopyResult look = WorldClone.inspect(from);
          if (look.failure() == WorldClone.CloneFailure.TEMPLATE_MISSING
              || look.failure() == WorldClone.CloneFailure.NO_REGION_DATA
              || look.failure() == WorldClone.CloneFailure.EMPTY_REGION_DATA) {
            logger.warning("Skipping '" + source.key() + suffix + "' while copying experience world '"
                + source.key() + "' — " + look.failure() + ": " + look.detail()
                + ". It holds no terrain, so the copy simply has no " + suffix + " dimension.");
            continue;
          }
        }
        // No lock file. There used to be one here — `MapBundle.underSharedWorldRootLock` on the
        // experiences root — described as "the belt to the braces", and it was not: every caller in
        // that tree takes the lock in SHARED mode, POSIX read locks do not exclude each other, and the
        // only exclusive taker (bundled-map seeding) never runs against the experiences root at all.
        // So it excluded nothing, while writing an operator-visible `.map-bundle.lock` into the shared
        // experiences tree that implied it did. A lock that promises exclusion it cannot provide is
        // worse than no lock, because it is what the next reader trusts instead of looking.
        //
        // What actually protects this copy is the before/after inventory bracket inside
        // copyWorldFolderChecked, which FAILS the copy when the source changed underneath the read
        // instead of publishing a torn replica -- plus the placement lease, which means at most one
        // node can be that writer. It is no longer backed by a loaded-world refusal upstream: copies
        // are allowed to run against a world people are inside (see worlds.experiences.allow-live-copy),
        // which is precisely why the bracket and the bounded retry below carry the whole weight.
        Path to = experienceFolderFor(destination.key() + suffix);
        Path staging = stagingFolderFor(to);
        staged.add(staging);
        if (!deleteDirectory(staging)) {
          logger.severe("Could not clear the staging folder '" + staging + "' before copying '"
              + source.key() + suffix + "'. Nothing was kept.");
          return false;
        }
        WorldClone.ChunkCopyResult result = null;
        for (int attempt = 1; attempt <= COPY_ATTEMPTS; attempt++) {
          if (attempt > 1) {
            // Clear staging first: the previous attempt's leftovers are no longer the source's
            // contents (TEMPLATE_CHANGED means files may have DISAPPEARED), and the copy only
            // overwrites while the verification only checks expected files — neither can see an extra
            // one, so a deleted region file or player save would be resurrected here.
            //
            // And a clearance that FAILED is not a reason to try again, it is a reason to stop. The
            // next attempt would copy over the leftovers with REPLACE_EXISTING and the verification
            // walks only the SOURCE's inventory (WorldClone.firstMismatch), so the extra files would
            // never be seen: a MERGED folder published as a verified exact replica.
            if (!deleteDirectory(staging)) {
              logger.severe("Could not clear the staging folder '" + staging + "' between attempts at"
                  + " copying '" + source.key() + suffix + "'. Retrying would copy over what is left"
                  + " there and publish the mixture as a verified replica, so nothing was kept.");
              return false;
            }
          }
          result = copyOneWorldFolder(from, staging);
          if (result.failure() != WorldClone.CloneFailure.TEMPLATE_CHANGED) {
            break;
          }
          logger.info("The source of '" + source.key() + suffix + "' moved while attempt " + attempt
              + " of " + COPY_ATTEMPTS + " was reading it: " + result.detail()
              + (attempt < COPY_ATTEMPTS ? ". Copying it again." : ""));
        }
        if (!result.ok()) {
          logger.severe("Could not copy experience world '" + source.key() + suffix + "' to '"
              + destination.key() + suffix + "' — " + result.failure() + ": " + result.detail()
              + ". Nothing was kept.");
          return false;
        }
        if (beforePublish != null) {
          // While the folder is still invisible: anything inside it that names the SOURCE world is
          // rewritten here, before a single reader can resolve the destination key.
          beforePublish.accept(staging);
        }
        if (!publishStagedFolder(staging, to)) {
          return false;
        }
        published.add(to);
        anything = true;
      }
      if (!anything) {
        logger.severe("Refusing to record a copy of experience world '" + source.key()
            + "': it has no folder on this node, so there was nothing to copy.");
      }
      success = anything;
      return anything;
    } catch (RuntimeException failed) {
      logger.severe("Copying experience world '" + source.key() + "' to '" + destination.key()
          + "' failed: " + failed);
      return false;
    } finally {
      // A staged folder that was published is gone (renamed); deleting the rest is unconditional.
      for (Path staging : staged) {
        if (!deleteDirectory(staging)) {
          logger.severe("Could not remove the staging folder '" + staging + "' left by the copy of '"
              + source.key() + "'. It is an orphan on disk that nothing references; remove it by hand.");
        }
      }
      if (!success) {
        // A failure is a failure however it left, and the flag is the only thing that knows. This
        // used to infer it from `published.size() != staged.size()`, which is balanced on exactly the
        // path that matters: a RuntimeException thrown at the TOP of the next iteration (an
        // InvalidPathException out of experienceFolderFor, anything out of Files.isDirectory or
        // WorldClone.inspect) lands after published.add and before the next staged.add, so the sizes
        // agreed, every already-published dimension survived, and the caller -- which reads false as
        // "nothing was kept" and cleans up nothing -- left ~290 MB of folders on the shared
        // experiences tree that no registry row names and no sweep collects.
        for (Path folder : published) {
          if (!deleteDirectory(folder)) {
            // The caller reads false as "nothing was kept" and cleans up nothing, so a rollback that
            // could not finish has to say so HERE or it is said nowhere at all.
            logger.severe("Rolling back the copy of experience world '" + source.key()
                + "' could not remove the already-published folder '" + folder + "'. It is an orphan"
                + " on disk that no registry row names; remove it by hand.");
          }
        }
      }
    }
  }

  /**
   * One verified whole-folder copy. Production is exactly {@link WorldClone#copyWorldFolderChecked},
   * and nothing else may be substituted for it here.
   *
   * <p>It is its own method purely so a test can drive the outcome the disk cannot be made to produce
   * on demand: a source that keeps changing under the read. Real drift is a race with another thread
   * inside a live server, so the only alternatives are a timing-dependent test (which would be flaky in
   * exactly the direction that hides a regression) or no test at all for the bound on the retries.</p>
   */
  protected WorldClone.ChunkCopyResult copyOneWorldFolder(Path from, Path staging) {
    return WorldClone.copyWorldFolderChecked(from, staging);
  }

  /** The overworld key plus every linked-dimension suffix, in the order a copy must walk them. */
  private List<String> keyAndSiblingSuffixes() {
    List<String> suffixes = new ArrayList<>();
    suffixes.add("");
    suffixes.addAll(siblingKeySuffixes());
    return suffixes;
  }

  /**
   * A unique staging path BESIDE {@code target}, never in the system temp dir: staging has to live on
   * the same filesystem or the publish stops being a rename and becomes a second full copy, which
   * reopens the window it exists to close. Same shape as the bundled-map extractor's.
   */
  private static Path stagingFolderFor(Path target) {
    Path parent = target.getParent();
    String name = target.getFileName() + BACKUP_STAGING_SUFFIX
        + Long.toHexString(System.nanoTime());
    return parent == null ? target.resolveSibling(name) : parent.resolve(name);
  }

  /**
   * How old a staging folder must be before a boot is allowed to delete it.
   *
   * <p>The guard exists so a boot never deletes a PEER NODE's in-flight copy: on the networked
   * deployment {@code experiencesDiskRoot()} is a symlink into a tree every node shares, so a folder
   * that looks abandoned from here may be a backup another worker is writing into right now. Six hours
   * is far longer than any copy takes and far shorter than "never", which is what it is today.</p>
   */
  private static final long STALE_STAGING_AGE_MILLIS = 6L * 60L * 60L * 1000L;

  /**
   * Deletes backup staging folders left behind by a JVM that died mid-copy.
   *
   * <p>Nothing else collects them: the temp-world GC only scans {@link #backendTempDiskRoot()}, while
   * these live beside the experiences they were being copied into. A kill/power loss therefore leaves a
   * directory that can be hundreds of megabytes AND that the placement reconciler will happily adopt as
   * a permanent row on non-shared storage — a row nothing ever drops afterwards, because its folder is
   * still there. Boot is the one moment this node knows none of ITS OWN copies are in flight.</p>
   */
  private void cleanupStaleBackupStaging() {
    Path root = experiencesDiskRoot();
    if (root == null || !Files.isDirectory(root)) {
      return;
    }
    List<Path> candidates = new ArrayList<>();
    try (var children = Files.list(root)) {
      children.filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().contains(BACKUP_STAGING_SUFFIX))
          .forEach(candidates::add);
    } catch (IOException unreadable) {
      logger.warning("Could not list '" + root + "' for stale backup staging folders: " + unreadable);
      return;
    }
    long cutoff = System.currentTimeMillis() - STALE_STAGING_AGE_MILLIS;
    for (Path candidate : candidates) {
      // The NEWEST mtime among the folder and its immediate children, not the folder's own: a
      // directory's mtime only moves when entries are added or removed directly under it, so a copy
      // busy writing region files inside `<staging>/region/` leaves the top folder looking untouched.
      // One level is enough to tell "being written" from "abandoned" and keeps the check O(children)
      // on a tree that may hold thousands of region files.
      if (newestMillis(candidate) > cutoff) {
        continue;
      }
      if (deleteDirectory(candidate)) {
        logger.warning("Removed the stale backup staging folder '" + candidate
            + "': it is an experience copy that a restart interrupted, and nothing else collects it.");
      } else {
        // "Removed" over a folder that is still there is worse than silence: this sweep is the only
        // thing that ever looks at these paths, so the line printed here is the only chance anybody has
        // of learning that hundreds of megabytes are still sitting on the shared tree.
        logger.severe("Could not remove the stale backup staging folder '" + candidate
            + "': it is an experience copy that a restart interrupted, nothing else collects it, and it"
            + " will still be here after the next boot; remove it by hand.");
      }
    }
  }

  /** Latest last-modified time of {@code folder} or any of its immediate children, in millis. */
  private static long newestMillis(Path folder) {
    long newest = 0L;
    try {
      newest = Files.getLastModifiedTime(folder).toMillis();
      try (var children = Files.list(folder)) {
        for (Path child : children.toList()) {
          try {
            newest = Math.max(newest, Files.getLastModifiedTime(child).toMillis());
          } catch (IOException vanished) {
            // Raced a writer or a delete; treat it as no information rather than as "old".
            newest = Math.max(newest, System.currentTimeMillis());
          }
        }
      }
    } catch (IOException unreadable) {
      // Unreadable is never a licence to delete.
      return System.currentTimeMillis();
    }
    return newest;
  }

  /** Moves a fully-written, verified staging folder onto its (absent) final path. */
  private boolean publishStagedFolder(Path staging, Path target) {
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try {
        Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return true;
      } catch (IOException | UnsupportedOperationException notAtomic) {
        Files.move(staging, target);
        return true;
      }
    } catch (IOException notARename) {
      // Staging is a sibling, so this should be a plain rename — but FileStore equality lies across two
      // bind mounts of one device, and a rename that "must" work has failed with EXDEV here before.
      logger.info("Could not rename " + staging + " -> " + target + " (" + notARename
          + "); copying instead.");
    }
    if (!WorldClone.copy(staging, target)) {
      logger.severe("Could not publish the experience copy staged at '" + staging + "' as '" + target
          + "'. Nothing was kept.");
      if (!deleteDirectory(target)) {
        // WorldClone.copy leaves whatever it managed to write, and this path never reaches
        // `published.add(to)` — so the rollback in copyExperienceFolders' finally does not know this
        // folder exists. A partial copy that survives here is an orphan no registry row names and no
        // sweep collects, on the exact path the orphans on the live tree came from.
        logger.severe("And the partly-copied folder '" + target + "' could not be removed. It is an"
            + " orphan that nothing names and nothing else collects; remove it by hand.");
      }
      return false;
    }
    // Not read: `staging` is in the caller's `staged` list, and the finally there deletes it and reports
    // what it could not remove. Failing a publish that WORKED over a leftover copy of it would answer
    // the wrong question.
    deleteDirectory(staging);
    return true;
  }

  /**
   * Removes every dimension folder of a copy that must not survive.
   *
   * <p>Reports what it could not remove, because it only ever runs when the row that would have named
   * these folders is NOT going to be written: whatever survives here is an orphan on the shared tree
   * that nothing references, nothing sweeps and nobody can see — around 290 MB per copy.</p>
   *
   * @return true when none of them are left
   */
  private boolean discardExperienceFolders(WorldKey key) {
    boolean gone = true;
    for (String suffix : keyAndSiblingSuffixes()) {
      Path folder = experienceFolderFor(key.key() + suffix);
      if (!deleteDirectory(folder)) {
        gone = false;
        logger.severe("Could not remove '" + folder + "', left by the copy of experience world '"
            + key.key() + "' that a shutdown cancelled. Nothing will name it and nothing else collects"
            + " it; remove it by hand.");
      }
    }
    return gone;
  }

  @Override
  public boolean deletePersistent(WorldKey worldKey) {
    if (worldKey == null || !mayOpenExperience(worldKey, "delete")) {
      return false;
    }
    String key = worldKey.key();
    WorldRequest request = persistentRequest(worldKey);
    registry.unpreserve(request.runtimeName());
    Path chunkFolder = null;
    Optional<WorldHandle> loaded = backendResolveLoaded(request.runtimeName(), WorldKind.PERSISTENT);
    if (loaded.isPresent()) {
      WorldHandle handle = loaded.get();
      chunkFolder = handle.canonicalFolder();
      evacuate(handle);
      if (!backendUnload(handle, false)) {
        // Deleting the folder anyway is what produced the worst bug this code has had: the world stays
        // LOADED with no folder behind it, and the acquire path's "already loaded" branch then hands that
        // ghost back as a freshly created world. A world we could not unload is left completely alone —
        // it is still playable, which is a recoverable outcome, unlike a ghost.
        logger.severe("Refusing to delete experience world '" + handle.runtimeName()
            + "': it could not be unloaded (are players still inside?). Nothing was removed.");
        return false;
      }
      registry.removeManaged(handle.runtimeName());
      registry.removeLeased(handle.runtimeName());
      leaseRefs.remove(handle.runtimeName());
    }
    // BOTH answers are read. `ExperienceService.applyDelete` drops the registry row when this method
    // says true, so a folder that outlived a `true` becomes an orphan the owner can no longer name or
    // see -- which is precisely what deleteDirectory started reporting for. A read-only mount, a region
    // file another process still holds, or an EACCES anywhere under the experience tree used to reach
    // the caller as "deleted", and the folder stayed on the shared disk with not one line in the log.
    boolean removed = true;
    if (chunkFolder != null && !deleteDirectory(chunkFolder)) {
      removed = false;
      logger.severe("Deleted experience world '" + key + "' from the registry's point of view, but its"
          + " world folder '" + chunkFolder + "' is still on disk. The world is unloaded, so nothing is"
          + " using it: remove it by hand.");
    }
    Path experienceFolder = experienceFolderFor(key);
    if (!deleteDirectory(experienceFolder)) {
      removed = false;
      logger.severe("Deleted experience world '" + key + "' from the registry's point of view, but its"
          + " experience folder '" + experienceFolder + "' is still on disk. The world is unloaded, so"
          + " nothing is using it: remove it by hand.");
    }

    // HAND THE CLAIM BACK. Without this the claim stayed in `claims` after the folder was gone, so
    // the heartbeat kept issuing a fenced renew that matched on (world_key, node_id, node_epoch,
    // fence) and rewrote the row to LOADED with a live lease -- for a world that no longer exists.
    // forget() refuses a LOADED row, rehome() refuses it, adoption refuses it: the name is reserved
    // on this node for the life of the process. That is exactly the pathology that left a world
    // stuck for two days on the live network, reintroduced through a different door -- and it fires
    // once per Death Resets regeneration, which deletes its predecessor every time.
    WorldClaim claim = claims.remove(request.runtimeName());
    if (claim != null) {
      // release() puts the row IDLE with no lease, which is what makes it collectable. Dropping the
      // row itself is the reconciler's job -- it already deletes rows with no folder and no
      // experience referring to them, and it is the one component allowed to decide that.
      leaseAuthority.release(claim);
    }
    // The claim goes back whatever the disk did -- the world IS unloaded, and a name this node no
    // longer serves must not stay reserved on it. The DELETE, though, is only a delete when the folders
    // are gone: the caller keeps its row when this is false, so the world stays named by something.
    return removed;
  }

  @Override
  public WorldKey nextExperienceGeneration(WorldKey worldKey) {
    if (worldKey == null || !mayOpenExperience(worldKey, "regenerate")) {
      return null;
    }
    // ONE round trip when there is an authority to ask: SELECT MAX(generation) over an indexed
    // lineage plus a guarded insert, with the primary key settling a race between two allocators.
    // The probe loop below is what a standalone server still uses, and what this replaces in
    // production: up to 1000 candidate names, each doing a Files.exists plus a backend disk test per
    // linked dimension -- up to 3000 stats against a NETWORK filesystem, on the main thread, on the
    // tick a player died. That is the freeze the warm pool exists to prevent, paid at the worst
    // possible moment.
    com.sexidium.core.world.WorldLeaseAuthority authority = leaseAuthority;
    if (authority != NO_AUTHORITY) {
      WorldKey allocated = authority.allocateNextGeneration(null, worldKey);
      if (allocated != null) {
        return allocated;
      }
      logger.warning("The placement authority could not allocate a successor for '" + worldKey
          + "'; falling back to probing the filesystem.");
    }
    // No "scope prefix" to re-apply any more. The successor used to have to be spelled the same way
    // the caller spelled the predecessor, because the placement table was keyed by whatever string it
    // was handed -- so re-wrapping a bare key in the experiences namespace produced a key in a
    // DIFFERENT key space from its own predecessor, the placement lookup missed, and the planner homed
    // the successor on another node while the run sat on this disk. There is one key space now.
    //
    // Bounded rather than unbounded: if a thousand consecutive generations are all somehow taken,
    // something is very wrong and spinning the world thread looking for the next one makes it worse.
    WorldKey candidate = worldKey;
    for (int probe = 0; probe < MAX_GENERATION_PROBES; probe++) {
      candidate = candidate.nextGeneration();
      if (isFreeExperienceKey(candidate.key())) {
        return candidate;
      }
    }
    logger.severe("Could not find a free regeneration name for experience world '" + worldKey + "'.");
    return null;
  }

  /**
   * The newest generation of this run that is on disk and newer than {@code key}, or null.
   *
   * <p>Lists the parent directory once rather than probing names in sequence: a reset deletes the
   * generation it replaced, so the sequence has holes and a probe of {@code key + 1} would stop at the
   * first gap and miss a run that is ten generations ahead.</p>
   */
  private String newerGenerationOnDisk(String key) {
    String base = WorldNaming.baseExperienceKey(key);
    String baseLeaf = WorldNaming.lastSegment(base);
    int current = WorldNaming.generationOf(key);
    Path parent = experienceFolderFor(base).getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      return null;
    }
    String newest = null;
    int newestGeneration = current;
    try (var children = Files.list(parent)) {
      for (Path child : children.toList()) {
        String name = child.getFileName() == null ? "" : child.getFileName().toString();
        if (!WorldNaming.baseExperienceKey(name).equals(baseLeaf)) {
          continue;
        }
        int generation = WorldNaming.generationOf(name);
        // region/ rather than level.dat: a keyed dimension folder never has one.
        if (generation > newestGeneration && Files.isDirectory(child.resolve("region"))) {
          newestGeneration = generation;
          newest = name;
        }
      }
    } catch (IOException unreadable) {
      // Failing to read is not evidence of absence; let the normal path continue.
      return null;
    }
    return newest;
  }

  /**
   * Whether a candidate experience key is unused — on disk, in memory, and for every linked dimension it
   * would claim. A teardown that crashed half way leaves a folder behind; this is what makes the next
   * regeneration skip it instead of trying to move a pooled world on top of it.
   */
  private boolean isFreeExperienceKey(String key) {
    if (Files.exists(experienceFolderFor(key))) {
      return false;
    }
    List<String> suffixes = new ArrayList<>();
    suffixes.add("");
    suffixes.addAll(siblingKeySuffixes());
    for (String suffix : suffixes) {
      WorldRequest request = persistentRequest(WorldKey.parse(key + suffix));
      if (backendExistsOnDisk(request)
          || backendResolveLoaded(request.runtimeName(), WorldKind.PERSISTENT).isPresent()) {
        return false;
      }
    }
    return true;
  }

  /**
   * The key suffixes this backend appends for an experience's linked dimensions, so a name-availability
   * check can account for the worlds a request will implicitly claim. Empty by default: a backend with no
   * linked dimensions claims only the name it was asked for.
   */
  protected List<String> siblingKeySuffixes() {
    return List.of();
  }

  // ===== lease close routing ===================================================================

  /** Called by {@link LeasedWorld#close()} — applies the kind-specific disposal policy. */
  void onLeaseClosed(WorldHandle handle) {
    if (handle == null) {
      return;
    }
    if (!releaseLeaseRef(handle.runtimeName())) {
      // Somebody else still holds this world. Dropping the leased name here would also drop it from
      // the lease heartbeat, publishing "nobody is serving this" about a world with players in it.
      return;
    }
    registry.removeLeased(handle.runtimeName());
    if (shuttingDown) {
      return;
    }
    // The lobby is never torn down by a lease close — releasing a lobby lease just drops the reference.
    if (handle.kind() == WorldKind.LOBBY) {
      return;
    }
    if (handle.kind() == WorldKind.PERSISTENT) {
      releasePersistent(handle);
    } else {
      dispose(handle, true);
      warmPool();
    }
  }

  // A "recycle(handle, profile)" that returned a released world to the pool used to live here. It was
  // never wired to anything and must not be: a used world carries the previous run's terrain edits, spawn
  // point and clock, so handing it to the next borrower hands them somebody else's builds. A released
  // world is always disposed and a clean one generated in its place.

  @Override
  public boolean saveTemplateAndDispose(WorldLease lease, String templateWorldName) {
    if (!(lease instanceof LeasedWorld leased) || leased.handle() == null) {
      if (lease != null) {
        lease.close();
      }
      return false;
    }
    WorldHandle handle = leased.handle();
    registry.removeLeased(handle.runtimeName());
    // Capture the on-disk folder while the world is still loaded, then unload WITH save so every edited
    // chunk is flushed to its region files (and chunk I/O has halted) before we read them — an in-place
    // save() returns before the async writes land, which is why edits "weren't saving".
    Path source = handle.canonicalFolder();
    evacuate(handle);
    boolean unloaded = backendUnload(handle, true);
    registry.removeManaged(handle.runtimeName());
    boolean copied = false;
    if (!unloaded) {
      logger.warning("Could not unload edit world '" + handle.runtimeName() + "' to save template '"
          + templateWorldName + "'.");
    } else if (source != null && templateWorldName != null && !templateWorldName.isBlank()) {
      Path target = worldRoot().resolve(templateWorldName.replace('\\', '/'));
      WorldClone.ChunkCopyResult result = WorldClone.copyChunkDataChecked(source, target);
      copied = result.ok();
      if (copied) {
        logger.info("Saved edited world into template '" + templateWorldName + "' (" + source + " -> "
            + target + "): " + result.detail() + ".");
      } else {
        // This is the master copy of the map, and a refused/half-done write here poisons every future
        // match of it on every node. It used to log INFO ("had no region") whatever went wrong, including
        // a partial copy — which is why a broken template could ship unnoticed.
        boolean touched = result.failure() == WorldClone.CloneFailure.IO_ERROR
            || result.failure() == WorldClone.CloneFailure.INCOMPLETE_COPY
            || result.failure() == WorldClone.CloneFailure.TEMPLATE_CHANGED;
        logger.severe("Did NOT save the edited world into template '" + templateWorldName + "' (" + source
            + " -> " + target + ") — " + result.failure() + ": " + result.detail() + "."
            + (touched
                ? " The template may now be HALF-WRITTEN: verify it before the next match, or restore it"
                    + " from the bundled map."
                : " The template was left untouched."));
      }
    }
    if (unloaded && readTempBoolean("delete-on-release", true) && source != null) {
      // Not read: `source` is a temp world folder under the temp disk root, which the temp-world GC
      // scans on every boot and every sweep. A folder left here is collected later, not orphaned.
      deleteDirectory(source);
    }
    warmPool();
    return copied;
  }

  // ===== internals =============================================================================

  private void releasePersistent(WorldHandle handle) {
    // An experience is THREE worlds. Releasing only the overworld left its Nether and End OPEN on this
    // node, and they are created outside the registry and outside the placement lease -- so nothing
    // covered them and nothing closed them. With shared world storage that is a corruption path, not
    // an untidiness: a peer takes the now-idle experience over, runs the same sibling creation, and
    // two servers write the same region files. Siblings fall FIRST, so anyone standing in one is put
    // back in the overworld and swept out with everybody else by the evacuate below.
    // Claimed until it is really gone, not until we decide to close it. See `closing`.
    closing.add(handle.runtimeName());
    releaseSiblings(handle);
    evacuate(handle);
    // Evacuation teleports ASYNCHRONOUSLY, so on this tick the players are still in the world and the
    // unload fails -- which is the literal source of every "Could not unload persistent world" line in
    // the logs. A world left loaded holds its region files open, and under shared storage that means no
    // other node may ever take it over. Retry while the teleports settle.
    releasePersistent(handle, UNLOAD_ATTEMPTS);
  }

  private void releasePersistent(WorldHandle handle, int attemptsLeft) {
    if (shuttingDown) {
      return;
    }
    // Somebody took the world again while the retries were pending -- a friend clicking the same
    // experience resolves the still-loaded world and gets a fresh lease without going near the
    // placement gate. Unloading now would pull the world out from under a player mid-teleport, which
    // is worse than the leak this retry exists to fix. The re-lease IS the answer: stop.
    if (registry.isLeased(handle.runtimeName())) {
      return;
    }
    if (backendUnload(handle, true)) {
      registry.removeManaged(handle.runtimeName());
      closing.remove(handle.runtimeName());
      // UNREGISTER ON RELEASE, not only on delete. Registering on open and forgetting only on delete
      // is what let every node accumulate the whole park on its autoload list -- and an autoload runs
      // before the plugin is enabled, so nothing is there to stop it opening a peer's live world.
      backendForgetRegistration(handle.runtimeName());
      WorldClaim claim = claims.remove(handle.runtimeName());
      if (claim != null) {
        // Hand the claim back at the same moment: a released world is one any node may take.
        leaseAuthority.release(claim);
      }
      return;
    }
    if (attemptsLeft <= 1) {
      // Keep RENEWING the lease for a world we could not close. The lease means "this node has it
      // open", and it does -- with its region files open. Letting the lease lapse here would tell a
      // peer the world is free and invite a second writer, which is the exact corruption the lease is
      // for. Louder than a warning for that reason.
      logger.severe("Could not unload persistent world '" + handle.runtimeName() + "' after "
          + UNLOAD_ATTEMPTS + " attempts. It stays loaded here and this node keeps holding its lease,"
          + " so no peer will open it -- but it will not be released until this node restarts.");
      return;
    }
    runOnWorldThreadLater(() -> releasePersistent(handle, attemptsLeft - 1), UNLOAD_RETRY_TICKS);
  }

  /**
   * Unloads — never deletes — the linked dimensions of a persistent world being released.
   *
   * <p>Default no-op: a backend without linked dimensions has nothing to close.</p>
   */
  protected void releaseSiblings(WorldHandle handle) {
  }

  private boolean dispose(WorldHandle handle, boolean deleteFolder) {
    if (handle == null) {
      return false;
    }
    String name = handle.runtimeName();
    if (handle.kind().persistsOnRelease() || naming.isLobby(name) || registry.isPreserved(name)) {
      return false;
    }
    evacuate(handle);
    if (!backendUnload(handle, false)) {
      logger.warning("Could not unload temporary world '" + name + "'.");
      return false;
    }
    registry.removeManaged(name);
    registry.removeLeased(name);
    if (deleteFolder && readTempBoolean("delete-on-release", true)) {
      // Not read, same as saveTemplateAndDispose: a temp world's folder that will not go today is
      // collected by the temp-world GC on the next boot, and nothing writes over that path meanwhile.
      deleteDirectory(handle.canonicalFolder());
    }
    return true;
  }

  /** Teleports every player still inside {@code handle}'s world to the lobby spawn before unload. */
  private void evacuate(WorldHandle handle) {
    WorldPosition lobby = lobbySpawn().orElse(null);
    List<PlayerAdapter> occupants;
    try {
      occupants = handle.adapter().players();
    } catch (RuntimeException exception) {
      return;
    }
    if (occupants == null) {
      return;
    }
    if (lobby == null) {
      // No lobby world HERE -- which is the normal state of a worker, i.e. of every node that hosts
      // experiences. This used to return immediately, so on the nodes where evacuation actually
      // matters it moved nobody: the players stayed in the world, the unload then failed because a
      // world with players in it cannot be unloaded, and the claim had already been dropped. Send
      // them off the node instead; the handler is wired to the transfer dispatcher.
      evacuationFallback.accept(List.copyOf(occupants));
      return;
    }
    for (PlayerAdapter occupant : occupants) {
      if (occupant != null && occupant.online()) {
        try {
          // The lobby is never hardcore, and a client is only ever told what a world is when it is sent
          // one — so an evacuation is also the one chance to take a hardcore world's hearts back off it.
          com.sexidium.core.game.EntryPolicy.leaveHardcoreWorld(occupant, lobby);
          occupant.teleport(lobby);
        } catch (RuntimeException ignored) {
          // Best effort: a player who can't be moved is left to the world's own unload handling.
        }
      }
    }
  }

  /**
   * How many live {@link LeasedWorld} handles point at each open world.
   *
   * <p>Every caller gets its OWN {@code LeasedWorld} over the same {@code WorldHandle}, and closing
   * one used to run the full disposal policy — so a second entrant into a live experience, or a
   * reconnect that rehydrated a second match in one world, meant whichever of them left first
   * unloaded the world under the other. A lease is a reference; the world closes when the last one
   * does.</p>
   */
  private final java.util.Map<String, Integer> leaseRefs =
      new java.util.concurrent.ConcurrentHashMap<>();

  private void registerLeased(WorldHandle handle) {
    leaseRefs.merge(handle.runtimeName(), 1, Integer::sum);
    registry.registerLeased(handle.runtimeName());
  }

  /** Drops one reference. True when that was the LAST one and the world may now be disposed of. */
  private boolean releaseLeaseRef(String runtimeName) {
    int[] remaining = {0};
    leaseRefs.computeIfPresent(runtimeName, (name, count) -> {
      remaining[0] = count - 1;
      return remaining[0] <= 0 ? null : remaining[0];
    });
    return remaining[0] <= 0;
  }

  /**
   * The placement keys of every PERSISTENT experience world this node currently has open.
   *
   * <p>Feeds the lease heartbeat. Until this existed nothing renewed a world's lease: it was written
   * once at claim time and expired 20 s later while the world stayed open for hours. That was
   * survivable only because a world's home was also the only disk holding its folder, so an expired
   * lease could not be acted on. It stops being survivable the moment the folders are shared and a
   * peer may take an idle world over — an unrenewed lease would then read as "nobody is serving this"
   * about a world with players in it, and two servers would open the same region files.</p>
   */
  public java.util.Set<String> openPersistentPlacementKeys() {
    java.util.Set<String> keys = new java.util.LinkedHashSet<>();
    java.util.Set<String> open = new java.util.LinkedHashSet<>(registry.leasedNames());
    // Plus anything still closing or stuck open: still ours, still must not be taken over.
    open.addAll(closing);
    for (String runtimeName : open) {
      if (naming.isExperience(runtimeName)) {
        WorldKey.fromRuntime(runtimeName, naming.experiencesNamespace())
            .ifPresent(key -> keys.add(key.key()));
      }
    }
    return keys;
  }

  private int collectGarbageOnWorldThread(Set<String> protectedNames) {
    int reclaimed = 0;
    Set<Path> loadedFolders = new HashSet<>();
    for (WorldHandle handle : backendLoadedTempWorlds()) {
      if (handle == null) {
        continue;
      }
      Path folder = WorldStorage.normalizePath(handle.canonicalFolder());
      if (folder != null) {
        loadedFolders.add(folder);
      }
      if (registry.isProtected(handle.runtimeName(), protectedNames) || isReady(handle.runtimeName())
          || registry.isLeased(handle.runtimeName()) || registry.isPreserved(handle.runtimeName())) {
        continue;
      }
      if (dispose(handle, true)) {
        if (folder != null) {
          loadedFolders.remove(folder);
        }
        if (folder == null || !Files.exists(folder)) {
          reclaimed++;
        }
      }
    }

    Path root = backendTempDiskRoot();
    if (root == null || !Files.isDirectory(root)) {
      return reclaimed;
    }
    try (var children = Files.list(root)) {
      for (Path folder : children.toList()) {
        if (!Files.isDirectory(folder)) {
          continue;
        }
        String folderName = folder.getFileName() == null ? "" : folder.getFileName().toString();
        if (!folderName.startsWith(naming.tempPrefix())) {
          continue;
        }
        if (loadedFolders.contains(WorldStorage.normalizePath(folder))
            || registry.isProtected(folderName, protectedNames)
            || registry.isPreserved(folderName)) {
          continue;
        }
        if (WorldStorage.deleteDirectoryIfExists(folder)) {
          reclaimed++;
        }
      }
    } catch (IOException exception) {
      logger.warning("Could not scan temp-world GC root '" + root + "': " + exception.getMessage());
    }
    return reclaimed;
  }

  private boolean isReady(String worldName) {
    return pool.contains(worldName);
  }

  /**
   * Tops every pooled profile back up to its target, one world at a time. Each creation is dispatched to
   * the world thread and schedules the next only when it finishes, so warming nine worlds at boot is a
   * queue of single generations rather than nine at once — the pool exists to remove stalls, not to
   * create one of its own.
   */
  private void warmPool() {
    if (!enabled() || shuttingDown) {
      return;
    }
    for (WorldProfile profile : pool.pooledProfiles()) {
      if (pool.shortfall(profile) > 0) {
        warmOne(profile);
        return; // one at a time; the completion callback comes back for the rest
      }
    }
    if (!warmed) {
      warmed = true;
      logger.info("World pool warm: " + pool.describe() + ".");
    }
  }

  /** How many consecutive create failures a shape gets before the pool stops asking for it. */
  private static final int WARM_FAILURE_LIMIT = 3;

  /** Consecutive warm-create failures per profile id; cleared by the first success. */
  private final java.util.Map<String, Integer> warmFailures = new java.util.concurrent.ConcurrentHashMap<>();

  private void warmOne(WorldProfile profile) {
    pool.beginCreate(profile);
    // Deferred, not immediate. Replenishment is triggered the moment a world is TAKEN — which is the
    // moment a match is starting — so running the replacement generation right then puts a world-thread
    // stall on exactly the tick the player is joining. Once the pool is warm the delay is the whole point
    // of it being lazy; during the boot warm-up it is a small pause between generations that keeps the
    // server responsive while it fills.
    runOnWorldThreadLater(() -> {
      try {
        Optional<WorldHandle> created = backendAcquire(tempRequest(profile), true);
        if (created.isPresent() && !shuttingDown) {
          warmFailures.remove(profile.id());
          registry.addManaged(created.get().runtimeName());
          pool.offer(profile, created.get());
        } else if (created.isPresent()) {
          dispose(created.get(), true);
        } else {
          // A profile the backend cannot build (an older platform, an unsupported preset) must not be
          // retried for ever — stop asking for it rather than spinning on the world thread. But a single
          // failure is not proof of that: a create can also fail for a passing reason (disk pressure, a
          // world manager mid-reload), and disabling on the first one silently left a server with no warm
          // worlds of that shape for the rest of its uptime, so every later match paid full generation
          // cost with nothing in the log to explain it. Three strikes, then give up and say so.
          int strikes = warmFailures.merge(profile.id(), 1, Integer::sum);
          if (strikes >= WARM_FAILURE_LIMIT) {
            pool.target(profile, 0);
            logger.warning("Disabling the '" + profile + "' world pool after " + strikes
                + " failed attempts: the platform could not create one.");
          } else {
            logger.warning("Could not create a warm '" + profile + "' world (attempt " + strikes
                + " of " + WARM_FAILURE_LIMIT + "); will retry.");
          }
        }
      } finally {
        pool.endCreate(profile);
        if (!shuttingDown) {
          warmPool();
        }
      }
    }, replenishDelayTicks());
  }

  /**
   * How long to wait before building a replacement warm world. Configurable because the right value is a
   * judgement about a particular server: long enough that the generation never shares a tick with the
   * match start that consumed the world, short enough that the pool is full again before the next one.
   */
  private long replenishDelayTicks() {
    return Math.max(0L, readTempInt("pool.replenish-delay-ticks", 100));
  }

  /** What is warm right now, per profile — for the boot log and admin diagnostics. */
  public final String describePool() {
    return pool.describe();
  }

  // ----- request builders ----------------------------------------------------------------------

  private WorldRequest tempRequest() {
    return tempRequest(WorldProfile.OVERWORLD);
  }

  private WorldRequest tempRequest(WorldProfile profile) {
    String shortKey = naming.nextTempShortKey(timestampMillis(), sequence.getAndIncrement());
    return tempRequestFor(naming.tempRuntimeName(shortKey), profile);
  }

  private WorldRequest tempRequestFor(String runtimeName) {
    return tempRequestFor(runtimeName, WorldProfile.OVERWORLD);
  }

  private WorldRequest tempRequestFor(String runtimeName, WorldProfile profile) {
    String shortKey = WorldNaming.lastSegment(runtimeName);
    return new WorldRequest(WorldKind.TEMP, naming.tempNamespace(), shortKey,
        naming.tempRuntimeName(shortKey), null, randomSeed(),
        tempSettings().withGeneration(profile.generation()));
  }

  private WorldRequest cloneRequest(String templateWorldName) {
    String shortKey = naming.nextTempShortKey(timestampMillis(), sequence.getAndIncrement());
    return new WorldRequest(WorldKind.CLONE, naming.tempNamespace(), shortKey,
        naming.tempRuntimeName(shortKey), templateWorldName, null, tempSettings());
  }

  private WorldRequest persistentRequest(WorldKey worldKey) {
    return persistentRequest(worldKey, WorldGeneration.DEFAULT);
  }

  private WorldRequest persistentRequest(WorldKey worldKey, WorldGeneration generation) {
    String key = worldKey.key();
    // The generation request (void flags + vanilla terrain preset) is folded into the world settings, so
    // the backend receives one fully-described request and needs no generation logic of its own.
    WorldSettings settings = persistentSettings().withGeneration(generation);
    return new WorldRequest(WorldKind.PERSISTENT, naming.experiencesNamespace(), key,
        naming.experienceRuntimeName(key), null, null, settings, worldKey);
  }

  private WorldSettings tempSettings() {
    return worldConfig.tempSettings();
  }

  /** Settings a persistent (experience) world is created/loaded with. */
  protected final WorldSettings persistentSettings() {
    return worldConfig.persistentSettings();
  }

  /**
   * The raw key PATH inside the experiences namespace, for a backend addressing a keyed dimension.
   *
   * <p>Deliberately not a {@link WorldKey}, and deliberately {@code protected}: a backend has to be
   * able to address the {@code _nether} and {@code _end} siblings, which are not worlds of their own
   * and have no key. Everything above the backend seam speaks {@code WorldKey} — this is the one
   * place a string key path is still the right currency, and keeping it off {@code WorldLeaseService}
   * is what stops it becoming a second spelling again.</p>
   */
  protected final String experienceKeyPathOf(String runtimeName) {
    return runtimeName == null || runtimeName.isBlank() ? null : naming.experienceKeyOf(runtimeName);
  }

  /** On-disk folder for an experience key. */
  protected Path experienceFolderFor(String key) {
    return new WorldStorage(experiencesDiskRoot()).experienceFolderFor(key);
  }

  // ----- config helpers (worlds.temp.* with legacy temporary-worlds.* fallback) ----------------

  protected final String readTempString(String key, String defaultValue) {
    return worldConfig.readString(key, defaultValue);
  }

  protected final boolean readTempBoolean(String key, boolean defaultValue) {
    return worldConfig.readBoolean(key, defaultValue);
  }

  protected final int readTempInt(String key, int defaultValue) {
    return worldConfig.readInt(key, defaultValue);
  }

  protected final double readTempDouble(String key, double defaultValue) {
    return worldConfig.readDouble(key, defaultValue);
  }

  /**
   * Recursively deletes a folder, best-effort. Protected so backends can reuse it.
   *
   * <p>Every caller in this class and in the backends now either reads the answer or says in a comment
   * why it may ignore it — the two rules being that a caller about to WRITE over the path and a caller
   * ROLLING something back must read it, and that "may ignore" only holds where another sweep collects
   * what is left (the temp-world GC) or another caller already reports it.</p>
   *
   * <p>An instance method rather than a static one so a test can drive the outcome the disk cannot be
   * made to produce on demand — an unlink that fails is a permission or filesystem state a test cannot
   * arrange from outside, and it cannot arrange it at all as root, which is what the deploy image runs
   * as. Same reason {@link #copyOneWorldFolder} is its own method.</p>
   *
   * @return true when nothing is left at {@code rootPath}. Callers that are about to write over the
   *     path, or that are rolling something back, MUST read it — see {@link WorldStorage#deleteDirectory}.
   */
  protected boolean deleteDirectory(Path rootPath) {
    return WorldStorage.deleteDirectory(rootPath);
  }

  private static void fail(Runnable onFailure) {
    if (onFailure != null) {
      onFailure.run();
    }
  }

  private Long randomSeed() {
    return java.util.concurrent.ThreadLocalRandom.current().nextLong();
  }

  private long timestampMillis() {
    return System.currentTimeMillis();
  }
}
