package com.sexidium.paper.adapter.world;

import com.sexidium.core.Branding;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.model.WorldTerrain;
import com.sexidium.core.world.AbstractWorldControl;
import com.sexidium.core.world.MapBundle;
import com.sexidium.core.world.WorldClone;
import com.sexidium.core.world.WorldGeneration;
import com.sexidium.core.world.WorldHandle;
import com.sexidium.core.world.WorldKind;
import com.sexidium.core.world.WorldNaming;
import com.sexidium.core.world.WorldProfile;
import com.sexidium.core.world.WorldRequest;
import com.sexidium.core.world.WorldSettings;
import io.papermc.paper.math.Position;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The Paper backend of the unified {@link AbstractWorldControl}. It supplies the irreducible
 * platform operations; all naming, the pool, the registries and the disposal policy live in the core
 * control layer.
 *
 * <p>Worlds are created/loaded with native, <em>keyed</em> {@link WorldCreator}s so the lobby and
 * experiences land at exactly {@code world/dimensions/<namespace>/<key>} on MC 26.1+:
 * {@code minecraft:lobby} &rarr; {@code world/dimensions/minecraft/lobby} and
 * {@code experiences:<nick>/<map>_<id>} &rarr; {@code world/dimensions/experiences/<nick>/<map>_<id>}.
 * Multiverse-Core (latest v5) is bound on top via {@link MultiverseBridge} and each world is imported
 * into it for {@code /mv} tooling, but native keyed creation does the actual loading (reliable on the
 * 26.1+ dimension storage where MV's own loader has historically failed).</p>
 */
public final class PaperWorldControl extends AbstractWorldControl {
  private final JavaPlugin plugin;
  private final Server server;
  private final MultiverseBridge multiverse;
  private final ConfigurationAdapter configuration;
  // Spawn read from a template's level.dat by cloneTemplate, consumed by the immediately-following
  // backendAcquire to re-pin the cloned world's spawn. Safe as a plain field: clone acquisition runs
  // single-threaded on the world thread (cloneTemplate → createWorld → apply, all in one call).
  private int[] pendingCloneSpawn;

  public PaperWorldControl(JavaPlugin plugin, ConfigurationAdapter configuration, LoggerAdapter logger) {
    super(configuration, logger);
    this.plugin = plugin;
    this.server = plugin.getServer();
    this.configuration = configuration;
    this.multiverse = server == null ? null : MultiverseBridge.tryBind(server.getPluginManager(), logger);
  }

  // ===== thread + paths ========================================================================

  @Override
  protected void runOnWorldThread(Runnable task) {
    server.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
  }

  @Override
  protected void runOnWorldThreadLater(Runnable task, long delayTicks) {
    if (delayTicks <= 0) {
      runOnWorldThread(task);
      return;
    }
    server.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), delayTicks);
  }

  @Override
  protected Path serverHome() {
    return server.getWorldContainer().toPath();
  }

  /** The {@code world/dimensions} root that holds every namespaced dimension folder on MC 26.1+. */
  private Path dimensionsRoot() {
    List<World> worlds = server.getWorlds();
    if (worlds != null && !worlds.isEmpty() && worlds.get(0).getWorldFolder() != null) {
      Path primary = worlds.get(0).getWorldFolder().toPath();
      Path parent = primary.getParent();
      if (parent != null && segmentEquals(parent, "minecraft")
          && parent.getParent() != null && segmentEquals(parent.getParent(), "dimensions")) {
        return parent.getParent();
      }
      // Classic storage: the primary world folder is the container/<world>; dimensions sit beneath it.
      return primary.resolve("dimensions");
    }
    return serverHome().resolve("world").resolve("dimensions");
  }

  @Override
  protected Path experiencesDiskRoot() {
    return dimensionsRoot().resolve(naming.experiencesNamespace());
  }

  @Override
  protected Path lobbyDiskFolder() {
    return dimensionsRoot().resolve(naming.lobbyNamespace()).resolve(naming.lobbyName());
  }

  private Path dimensionFolder(WorldRequest request) {
    Path folder = dimensionsRoot().resolve(request.namespace());
    for (String segment : request.keyPath().replace('\\', '/').split("/")) {
      if (!segment.isBlank()) {
        folder = folder.resolve(segment);
      }
    }
    return folder;
  }

  // ===== acquire / resolve / unload ============================================================

  @Override
  protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean createIfMissing) {
    try {
      NamespacedKey key = keyFor(request);
      if (key == null) {
        logger.warning("Could not acquire world '" + request.runtimeName() + "': invalid dimension key.");
        return Optional.empty();
      }
      World existing = resolveLoadedWorld(key, request);
      if (existing != null) {
        applySettings(existing, request);
        ensureMetadata(request);
        ensureExperienceSiblings(existing, request);
        return Optional.of(new PaperWorldHandle(existing, request.kind(), request.runtimeName()));
      }
      Path folder = dimensionFolder(request);
      // A CLONE whose chunk data the control layer already copied here, off the world thread, is NOT "a
      // world that was already on disk". Everything below still has to run the clone branch: it is what
      // reads the template's own spawn, and what leaves onDisk false so pinLandSpawn runs — and the world
      // border is centred on whichever spawn those two settle on. Treating a staged folder as pre-existing
      // would silently move a 750-block border off a map whose build sits far from the origin.
      boolean staged = request.kind() == WorldKind.CLONE && Files.isDirectory(folder.resolve("region"));
      boolean onDisk = !staged
          && (Files.isDirectory(folder.resolve("region")) || Files.exists(folder.resolve("level.dat")));
      if (!onDisk && !createIfMissing) {
        return Optional.empty();
      }
      if (request.kind() == WorldKind.CLONE && !onDisk && !cloneTemplate(request, folder, staged)) {
        return Optional.empty();
      }
      // Create from the KEY alone: Paper derives the world name from the key and rejects a
      // WorldCreator whose explicit name does not match that identity ("mismatched name and key
      // identities"). We track worlds by key + our own canonical runtime name, so the Bukkit-assigned
      // name is irrelevant here.
      WorldCreator creator = WorldCreator.ofKey(key);
      boolean cloneWorld = request.kind() == WorldKind.CLONE;
      boolean voidWorld = request.settings() != null && request.settings().voidWorld();
      configureGeneration(creator, request);
      creator.seed(request.seed() != null ? request.seed() : ThreadLocalRandom.current().nextLong());
      // Born hardcore: the client is told a world is hardcore when the world is sent to it, so the flag
      // has to exist on the world before anyone is teleported in — applying it to a world people are
      // already standing in leaves them with ordinary hearts.
      creator.hardcore(request.settings() != null && request.settings().hardcore());
      // Never pre-generate / keep the spawn area resident: createWorld's "Preparing spawn area" loop
      // stalls the server thread (10s+ per world) and we manage chunk loading ourselves — players are
      // teleported to the arena/map and chunks stream in on demand.
      creator.keepSpawnLoaded(TriState.FALSE);
      if (request.kind() != WorldKind.PERSISTENT || voidWorld || onDisk) {
        // Temp/clone arenas have no meaningful natural spawn (a map is pasted in, or players are
        // teleported), so pin one: this makes createWorld skip the chunk-generating safe-spawn search
        // (PlayerSpawnFinder) that is the other half of the startup stall. A VOID experience likewise has
        // no natural ground to spawn on — pin (0,64,0), which is exactly where the SkyBlock challenge
        // builds its starting island. A world already ON DISK is pinned too: its terrain exists, so the
        // search would only re-derive what SafeSpawn resolves per player anyway (which is what places
        // every arrival), and paying for it is the stall an adopted pool world exists to avoid. Only a
        // brand-new persistent world keeps the natural surface spawn.
        creator.forcedSpawnPosition(Position.block(0, 64, 0), 0.0F, 0.0F);
      }
      World world = server.createWorld(creator);
      if (world == null) {
        return Optional.empty();
      }
      // Restore the template's own spawn (lost when its level.dat was stripped for dimension storage) so
      // players/editors drop onto the built map instead of 0,0.
      if (cloneWorld && pendingCloneSpawn != null) {
        world.setSpawnLocation(pendingCloneSpawn[0], pendingCloneSpawn[1], pendingCloneSpawn[2]);
        pendingCloneSpawn = null;
      }
      // NB: do NOT read the surface height (getHighestBlockYAt) here — it forces a synchronous full
      // chunk generation of the spawn chunk on the region thread, stalling the server for many seconds
      // per world and defeating the keepSpawnLoaded(FALSE)/forcedSpawnPosition above. Players teleport in
      // and chunks stream on demand. This binds pinLandSpawn below too: it picks the spawn COLUMN here
      // and resolves that column's height on an async chunk load, never on this thread.
      logger.info("[" + Branding.label(configuration) + "] Created world key=" + key + " name='" + world.getName()
          + "' nameResolves=" + (server.getWorld(world.getName()) != null));
      if (!onDisk) {
        pinLandSpawn(world, request);
      }
      applySettings(world, request);
      ensureMetadata(request);
      if (multiverse != null) {
        multiverse.importWorld(world);
      }
      ensureExperienceSiblings(world, request);
      return Optional.of(new PaperWorldHandle(world, request.kind(), request.runtimeName()));
    } catch (RuntimeException exception) {
      logger.warning("Could not acquire world '" + request.runtimeName() + "': " + exception.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Points a creator at the right terrain for a request: which dimension it is, whether it is void, and
   * which vanilla preset shapes it. Split out because the world POOL creates Nether and End worlds in
   * their own right — before it existed every managed world was an Overworld, and the environment could
   * simply be hard-coded here.
   */
  private void configureGeneration(WorldCreator creator, WorldRequest request) {
    var settings = request.settings();
    World.Environment environment = environmentOf(settings == null ? null : settings.dimension());
    if (settings != null && settings.voidWorld()) {
      // A SkyBlock-style experience (Random Layers) wants NO natural terrain: bind an empty chunk
      // generator with its own single-biome provider so the world is pure void yet still a valid
      // dimension (which is what otherwise fails createWorld for a non-minecraft key). The experience
      // builds its starting island and layers into this void itself.
      creator.environment(environment).type(WorldType.NORMAL).generateStructures(false);
      creator.generator(new VoidChunkGenerator(voidBiome(environment)));
      return;
    }
    // A world with a NON-minecraft namespace key has no datapack-defined dimension, so Paper gives it
    // an EMPTY (void) chunk generator by default — createWorld can then FAIL (no dimension type) or drop
    // players through the floor. So ALWAYS inherit the matching vanilla dimension's generation settings,
    // even for a CLONE: the clone's own region files (the built arena) still load on top, and a valid
    // generator is what makes createWorld succeed instead of returning null (which used to trigger the
    // vanilla fallback that overwrote edited maps). Void surroundings are sacrificed for the map loading.
    World template = server.getWorld(NamespacedKey.minecraft(vanillaKeyOf(environment)));
    if (template == null) {
      template = server.getWorld(NamespacedKey.minecraft("overworld"));
    }
    if (template == null && !server.getWorlds().isEmpty()) {
      template = server.getWorlds().get(0);
    }
    if (template != null) {
      creator.copy(template);
    }
    // The owner's chosen vanilla preset (Superflat / Large Biomes / Amplified) applied AFTER copy(),
    // so it overrides whatever type the template world happened to use.
    creator.environment(environment).type(nativeWorldType(settings)).generateStructures(true);
  }

  /**
   * Moves a freshly generated world's spawn onto habitable land, the way vanilla picks where a new world
   * starts.
   *
   * <p>We pin the spawn at creation to (0, 64, 0) to skip Paper's own spawn search, because that search
   * generates chunks on the server thread and is half of what made creating a world a multi-second stall.
   * The cost of that shortcut was landing on whatever happens to be at (0, 0) in a random seed — very
   * often open ocean, which is exactly how players ended up spawning in the middle of the sea.</p>
   *
   * <p>So the spawn is chosen here instead, from the world's BIOME SOURCE, which costs no chunk
   * generation and can therefore look thousands of blocks out — far enough to actually leave an ocean. For
   * a pooled world this runs at boot with nobody waiting, so by the time a player is handed the world its
   * spawn is already on land.</p>
   *
   * <p>In TWO steps, and that split is the whole point. Picking the column is free; learning the ground
   * height there is not, because it generates that column's chunk. Doing both here used to contradict the
   * NB in {@code backendAcquire} four lines above the call — the server thread parked in
   * {@code ServerChunkCache.syncLoad} waiting on a carvers→features→full pass, once per pooled world, and
   * a watchdog dump plus a dead node is what that eventually bought. So step two is handed to
   * {@link #resolveSpawnHeight}, which waits on an ASYNC chunk load.</p>
   */
  private void pinLandSpawn(World world, WorldRequest request) {
    var settings = request.settings();
    if (settings != null && settings.voidWorld()) {
      return; // a void world's island is built at the pinned origin on purpose
    }
    if (world.getEnvironment() != World.Environment.NORMAL || !landSpawnEnabled()) {
      return;
    }
    int[] column = new PaperWorldAdapter(world).locateLandColumn(world.getName(), landSpawnRadius());
    if (column == null) {
      return; // nothing suitable in range — keep the pinned origin rather than guessing
    }
    resolveSpawnHeight(world, column[0], column[1]);
  }

  /**
   * Finishes a land spawn once the column's chunk is loaded, without ever blocking the server thread.
   *
   * <p>The world is usable throughout: until this lands, its spawn is the pinned origin — exactly the
   * fallback a world with no habitable biome in range keeps for good. So the worst case of the wait is
   * today's already-accepted behaviour, and the best case is the same land spawn as before at no cost to
   * the tick. Pooled worlds are warmed with nobody in them, so this always settles long before a player
   * is handed one.</p>
   */
  private void resolveSpawnHeight(World world, int blockX, int blockZ) {
    NamespacedKey key = world.getKey();
    try {
      world.getChunkAtAsync(blockX >> 4, blockZ >> 4, true).whenComplete((chunk, error) -> {
        if (error != null || chunk == null) {
          return;
        }
        runOnWorldThread(() -> {
          // A pooled world can be taken, finished and disposed while its chunk was still generating, and
          // the server can be shutting down. Setting a spawn on an unloaded world throws, and the spawn
          // would belong to nothing anyway, so drop the answer rather than "repair" a dead world.
          if (server.getWorld(key) != world) {
            return;
          }
          // The chunk is loaded now, so this is a heightmap lookup rather than a generation.
          int surface = world.getHighestBlockYAt(blockX, blockZ);
          world.setSpawnLocation(blockX, surface + 1, blockZ);
          logger.info("[" + Branding.label(configuration) + "] Spawn for '" + key + "' placed on land at "
              + blockX + ", " + (surface + 1) + ", " + blockZ + ".");
        });
      });
    } catch (RuntimeException exception) {
      // No async chunk API on this platform. Keep the pinned origin: falling back to the blocking probe
      // would reintroduce precisely the stall this method exists to remove.
      logger.warning("[" + Branding.label(configuration) + "] Could not resolve a land spawn for '" + key
          + "': " + exception.getMessage());
    }
  }

  private boolean landSpawnEnabled() {
    return configuration.getBoolean("worlds.temp.land-spawn.enabled", true);
  }

  private int landSpawnRadius() {
    return Math.max(64, configuration.getInt("worlds.temp.land-spawn.search-radius", 6400));
  }

  /** The vanilla dimension key whose generator settings a managed world of this environment inherits. */
  private static String vanillaKeyOf(World.Environment environment) {
    return switch (environment) {
      case NETHER -> "the_nether";
      case THE_END -> "the_end";
      default -> "overworld";
    };
  }

  /** The single biome an empty (void) world of this environment is filled with. */
  private static org.bukkit.block.Biome voidBiome(World.Environment environment) {
    return switch (environment) {
      case NETHER -> org.bukkit.block.Biome.NETHER_WASTES;
      case THE_END -> org.bukkit.block.Biome.THE_END;
      default -> org.bukkit.block.Biome.PLAINS;
    };
  }

  @Override
  protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
    if (kind == WorldKind.LOBBY || naming.isLobby(runtimeName)) {
      return backendLobby();
    }
    String namespace;
    String keyPath;
    String canonical;
    if (kind == WorldKind.PERSISTENT || naming.isExperience(runtimeName)) {
      keyPath = experienceKeyPathOf(runtimeName);
      namespace = naming.experiencesNamespace();
      canonical = naming.experienceRuntimeName(keyPath);
    } else {
      keyPath = WorldNaming.lastSegment(runtimeName);
      namespace = naming.tempNamespace();
      canonical = naming.tempRuntimeName(keyPath);
    }
    NamespacedKey key = namespacedKey(namespace, keyPath);
    World world = key == null ? null : server.getWorld(key);
    if (world == null) {
      world = server.getWorld(keyPath.replace('/', '_'));
    }
    if (world == null) {
      world = findLoadedByKey(namespace, keyPath);
    }
    if (world == null) {
      return Optional.empty();
    }
    WorldKind resolvedKind = kind == WorldKind.LOBBY ? WorldKind.LOBBY : kind;
    return Optional.of(new PaperWorldHandle(world, resolvedKind, canonical));
  }

  @Override
  protected boolean backendExistsOnDisk(WorldRequest request) {
    Path folder = dimensionFolder(request);
    return Files.isDirectory(folder.resolve("region")) || Files.exists(folder.resolve("level.dat"));
  }

  /**
   * Adopts a warm pooled world: unload it (saving, so every generated chunk is on disk and chunk I/O has
   * stopped), MOVE its folder to where the requested world belongs, and load it again under the new key.
   * A move on the same filesystem is a rename — no chunk data is copied and no terrain is generated, so
   * an experience that used to cost a full world generation now costs a directory rename.
   *
   * <p>The pooled world's SEED travels with it. Without that, chunks generated later (as the player walks
   * out of the pre-generated area) would come from a different seed than the ones already on disk, and the
   * player would find a visible seam in the terrain.</p>
   */
  @Override
  protected Optional<WorldHandle> backendAdopt(WorldHandle pooled, WorldRequest request) {
    if (!(pooled instanceof PaperWorldHandle handle) || request == null) {
      return Optional.empty();
    }
    World world = handle.world();
    Path source = handle.canonicalFolder();
    Path target = dimensionFolder(request);
    if (source == null || Files.exists(target)) {
      return Optional.empty();
    }
    long seed = world.getSeed();
    String pooledName = world.getName();
    // The pooled world's spawn was placed on LAND at boot, and our dimension folders carry no level.dat,
    // so the reload would otherwise re-derive it and undo that work — landing the player back at (0, 0),
    // which is very often ocean. Carry it across explicitly, like the seed.
    Location pooledSpawn = world.getSpawnLocation();
    if (!server.unloadWorld(world, true)) {
      logger.warning("Could not unload pooled world '" + pooledName + "' to adopt it; generating instead.");
      return Optional.empty();
    }
    if (multiverse != null) {
      multiverse.forgetWorld(pooledName);
    }
    if (!stageFolder(source, target)) {
      return Optional.empty();
    }
    // The folder is now where the request expects it, so this is a plain LOAD: backendAcquire finds the
    // region data on disk and never reaches its generation path.
    Optional<WorldHandle> adopted = backendAcquire(adoptionRequest(request, seed), false);
    if (adopted.isEmpty()) {
      // The move succeeded and the load did not, so the chunks are sitting at the destination under a
      // name nothing owns. Returning empty here used to be silently destructive: the caller falls back to
      // "generate a new world", sees a folder already on disk, and loads THOSE chunks with a fresh random
      // seed and no land-spawn search — a world with a visible terrain seam whose player spawns in the
      // ocean. Put the folder back so the fallback really does generate, and the pooled world can be
      // disposed of normally instead of leaking into a namespace the pool never scans.
      // Rolled back through the SAME staging helper: a bare Files.move here would throw the identical
      // cross-mount error the forward direction does, turning a recoverable failure into the "must be
      // deleted by hand" branch every time.
      if (stageFolder(target, source)) {
        logger.warning("Adopting pooled world '" + pooledName + "' failed at load; rolled the folder back.");
      } else {
        logger.severe("Adopting pooled world '" + pooledName + "' failed at load AND the folder could not"
            + " be rolled back from " + target + " to " + source
            + ". That folder must be deleted by hand before this world name is used again.");
      }
      return Optional.empty();
    }
    if (pooledSpawn != null && adopted.get() instanceof PaperWorldHandle handled) {
      handled.world().setSpawnLocation(pooledSpawn.getBlockX(), pooledSpawn.getBlockY(), pooledSpawn.getBlockZ());
    }
    return adopted;
  }

  /**
   * Moves a world folder, falling back to copy-then-delete when a rename cannot cross the boundary.
   *
   * <p>{@code Files.move} on a directory is only ever a {@code rename(2)}, and {@code rename(2)}
   * fails with {@code EXDEV} across <b>mount points</b> — even when both are the same filesystem on
   * the same device. That is exactly this deployment: the warm pool lives on the node's own volume
   * and the experiences tree is a bind of a shared one. Java's own EXDEV fallback is no help for a
   * directory: it {@code mkdir}s the target and then tries to {@code rmdir} a non-empty source, which
   * is why the failure surfaced as {@code DirectoryNotEmptyException} naming the <i>source</i>.</p>
   *
   * <p>The consequence was that adoption never once succeeded on the network: every experience and
   * every regeneration silently fell back to generating terrain, on the world thread, at the moment a
   * player was entering. Note the earlier boot probe compared {@code FileStore}s, which are equal for
   * two mounts of one device — it reported "SAME store, adoption is a rename" while this failed every
   * time.</p>
   */
  private boolean stageFolder(Path source, Path target) {
    if (source == null || target == null) {
      return false;
    }
    try {
      Files.createDirectories(target.getParent());
      Files.move(source, target);
      return true;
    } catch (IOException notARename) {
      // toString(), not getMessage(): NIO filesystem exceptions carry only the offending PATH as their
      // message, so both directions printed as a bare path and read as "the source is the problem".
      logger.info("Could not rename " + source + " -> " + target + " (" + notARename
          + "); copying instead. This is expected when the two live on different mounts.");
    }
    // WorldClone.copy already skips session.lock, uuid.dat and *.lock — precisely the files that must
    // not travel with an adopted world — and never throws.
    if (!com.sexidium.core.world.WorldClone.copy(source, target)) {
      logger.warning("Could not copy pooled world folder " + source + " -> " + target
          + "; generating instead.");
      if (!deleteDirectory(target)) {
        // The caller answers this false with "generate a new world" -- at THIS path. backendAcquire
        // then finds region data already on disk and loads those half-copied chunks under a fresh
        // random seed and no land-spawn search, which is the silently destructive outcome the rollback
        // below exists to avoid. Whoever reads the log has to be told the path is poisoned.
        logger.severe("And the partly-copied folder " + target + " could not be removed. The next world"
            + " created under that name would load those chunks with a different seed: delete it by"
            + " hand before this world name is used again.");
      }
      return false;
    }
    // Only after the copy is complete: a source deleted early is a world lost outright.
    if (!deleteDirectory(source)) {
      // Still true: the folder IS at `target`, which is the whole of what this method promises, and
      // failing the move would send the caller into the generate-over-existing-chunks path above. But
      // in the ROLLBACK direction the leftover sits on the destination dimension path, where exactly
      // that fallback will find it -- and the caller logs "rolled the folder back". Say it here.
      logger.warning("Copied " + source + " -> " + target + ", but the original could not be removed."
          + " Two copies of that world now exist on disk; " + source + " must be deleted by hand.");
    }
    return true;
  }

  @Override
  protected boolean backendUnload(WorldHandle handle, boolean save) {
    if (!(handle instanceof PaperWorldHandle paperHandle)) {
      return false;
    }
    World world = paperHandle.world();
    // UNLOAD FIRST, DEREGISTER AFTER. The other order made every unload report failure while actually
    // succeeding, and that lie is the whole of the "worlds pile up and can never be released" bug.
    //
    // Multiverse's removeWorld does not merely forget a world: it unloads the Bukkit world too
    // (RemoveWorldOptions defaults saveBukkitWorld=true AND unloadBukkitWorld=true). So by the time
    // server.unloadWorld ran, CraftServer.unloadWorld found console.getLevel(dimension) == null --
    // one of its five false conditions -- and answered false for a world it had just closed. Callers
    // read that as "still loaded": deletePersistent refused to delete and returned BEFORE handing the
    // claim back, so the placement row stayed LOADED with a renewed lease that no peer could ever
    // adopt, for a world that was not even open. The visible "11 loaded worlds" were phantoms.
    //
    // It also fixes a data-safety hole on the eviction path: MV's default saveBukkitWorld=true was
    // SAVING a world that onLeaseLost documents must never be saved, because another node may already
    // be writing those region files.
    boolean unloaded = server.unloadWorld(world, save);
    if (unloaded && multiverse != null && !save) {
      // backendForgetRegistration rather than multiverse.forgetWorld: it retires all three Multiverse
      // spellings AND the linked _nether/_end siblings, which the raw call does not.
      backendForgetRegistration(paperHandle.runtimeName());
    }
    return unloaded;
  }

  /**
   * Flushes one open world in place: Paper's {@code World.save()}, on the thread we are called on.
   *
   * <p>Called immediately before a live experience copy, and it is the ONLY in-place save in this
   * plugin. What it buys is narrow and worth stating exactly, because the comment three methods up
   * (and the whole shape of the backup feature) rests on the other half of it: {@code save()} writes
   * level data and hands every dirty chunk to the chunk-write scheduler, then RETURNS. The writes land
   * afterwards, asynchronously. That is why unload-with-save is still the only flush this codebase
   * treats as a barrier, and why the copy that follows this call is verified against the folder it
   * reads rather than trusted because of this call.</p>
   *
   * <p>Inline, never posted. A save scheduled for the next tick would land after the copy it exists to
   * precede, which would make this worse than doing nothing — it would look like a flush in the log and
   * be a write racing the reader in fact. The caller (the backup verbs) is already on the world thread;
   * if it ever is not, Paper refuses and that is caught here and reported, not swallowed.</p>
   */
  @Override
  protected boolean backendSaveWorld(WorldHandle handle) {
    if (!(handle instanceof PaperWorldHandle paperHandle) || paperHandle.world() == null) {
      return false;
    }
    try {
      paperHandle.world().save();
      return true;
    } catch (RuntimeException refused) {
      logger.warning("Paper refused an in-place save of '" + paperHandle.runtimeName() + "': "
          + refused + ". The copy still runs and is still verified.");
      return false;
    }
  }

  @Override
  protected Optional<WorldHandle> backendLobby() {
    World lobby = server.getWorld(naming.lobbyName());
    if (lobby == null) {
      NamespacedKey key = namespacedKey(naming.lobbyNamespace(), naming.lobbyName());
      lobby = key == null ? null : server.getWorld(key);
    }
    if (lobby == null) {
      return Optional.empty();
    }
    return Optional.of(new PaperWorldHandle(lobby, WorldKind.LOBBY, naming.lobbyRuntimeName()));
  }

  @Override
  protected List<WorldHandle> backendLoadedTempWorlds() {
    List<WorldHandle> handles = new ArrayList<>();
    for (World world : server.getWorlds()) {
      String shortKey = tempShortKey(world);
      if (shortKey != null) {
        handles.add(new PaperWorldHandle(world, WorldKind.TEMP, naming.tempRuntimeName(shortKey)));
      }
    }
    return handles;
  }

  @Override
  protected Path backendTempDiskRoot() {
    return dimensionsRoot().resolve(naming.tempNamespace());
  }

  // ===== stale cleanup =========================================================================

  @Override
  protected void backendCleanupStaleTempWorlds() {
    Path tempRoot = dimensionsRoot().resolve(naming.tempNamespace());
    File root = tempRoot.toFile();
    if (!root.isDirectory()) {
      return;
    }
    Set<File> loadedFolders = new HashSet<>();
    for (World world : server.getWorlds()) {
      if (world.getWorldFolder() != null) {
        loadedFolders.add(world.getWorldFolder().getAbsoluteFile());
      }
    }
    File[] stale = root.listFiles(file -> file.isDirectory()
        && file.getName().startsWith(naming.tempPrefix())
        && !loadedFolders.contains(file.getAbsoluteFile()));
    if (stale == null) {
      return;
    }
    for (File folder : stale) {
      // Not read: this IS the sweep for these folders, it runs on every boot, and nothing writes over
      // a temp world's path in the meantime -- a folder that will not go today is collected next boot.
      deleteDirectory(folder.toPath());
    }
  }

  // ===== worldgen guarantee (reachable End) ====================================================

  /**
   * The reachable-End guarantee is delivered entirely at RUNTIME by {@code ExperienceWorldGen}: it tries
   * vanilla structure placement first, then builds a working End portal inside the experience border when
   * no locatable stronghold can be verified. We do NOT write a boot-time worldgen datapack: a structure-set
   * datapack is loaded during registry freeze BEFORE plugins enable, so any format mismatch (the schema
   * changes between MC versions — e.g. 26.1
   * made {@code concentric_rings.salt} mandatory and reworked {@code pack.mcmeta}) hard-fails the whole
   * server boot, and the plugin cannot self-heal it. The runtime path is version-independent and carries
   * no boot-brick risk, so it is the sole mechanism. A stale datapack from an earlier build is removed
   * best-effort here so it can never break a future boot.
   */
  @Override
  protected void backendRemoveStaleWorldgenDatapack() {
    Path dimensions = dimensionsRoot();
    Path saveRoot = dimensions == null ? null : dimensions.getParent();
    if (saveRoot == null) {
      return;
    }
    Path staleDatapack = saveRoot.resolve("datapacks").resolve("sexidium_worldgen");
    if (!deleteDirectory(staleDatapack) && Files.exists(staleDatapack)) {
      // The entire point of removing it is that a datapack from an earlier build is loaded during
      // registry freeze, BEFORE plugins enable, so a format mismatch hard-fails the whole server boot
      // and the plugin cannot self-heal it. A removal that failed silently leaves that brick armed.
      logger.warning("Could not remove the stale worldgen datapack at " + staleDatapack
          + ". It is loaded before plugins enable, so a format mismatch there fails the next boot"
          + " outright: delete that folder by hand.");
    }
  }

  // ===== helpers ===============================================================================

  private World resolveLoadedWorld(NamespacedKey key, WorldRequest request) {
    World world = key == null ? null : server.getWorld(key);
    if (world == null) {
      world = server.getWorld(bukkitName(request));
    }
    if (world == null) {
      world = findLoadedByKey(request.namespace(), request.keyPath());
    }
    return world;
  }

  private World findLoadedByKey(String namespace, String keyPath) {
    for (World world : server.getWorlds()) {
      NamespacedKey worldKey = world.getKey();
      if (worldKey != null && worldKey.getNamespace().equals(namespace)
          && worldKey.getKey().equals(keyPath.toLowerCase(Locale.ROOT))) {
        return world;
      }
    }
    return null;
  }

  private String tempShortKey(World world) {
    if (world == null) {
      return null;
    }
    NamespacedKey key = world.getKey();
    if (key != null && key.getNamespace().equals(naming.tempNamespace())) {
      String shortKey = WorldNaming.lastSegment(key.getKey());
      return shortKey != null && shortKey.startsWith(naming.tempPrefix()) ? shortKey : null;
    }
    String name = WorldNaming.stripNamespace(world.getName());
    String namespacePrefix = naming.tempNamespace() + "_";
    if (name.startsWith(namespacePrefix)) {
      String remainder = name.substring(namespacePrefix.length());
      if (remainder.startsWith(naming.tempPrefix())) {
        name = remainder;
      }
    }
    return name.startsWith(naming.tempPrefix()) ? name : null;
  }


  /**
   * How long a clone waits for the world root's shared lock before copying without it.
   *
   * <p>Not "as long as it takes", because {@code cloneTemplate} runs inside {@code runOnWorldThread}:
   * every millisecond spent here is a millisecond in which NOBODY on this node moves, not merely the
   * match that is starting. A seeding pass holds the lock for the length of a whole extraction pass
   * (~1-2 s per map), so waiting it out would trade a rare silent corruption for a rare visible freeze
   * of several seconds — a bad deal even though the freeze is the lesser evil.
   *
   * <p>Half a second is chosen to cover the acquisition itself (tens of microseconds when nothing is
   * contending, which is ~99% of match starts) plus a short refresh, and to cap the stall at roughly what
   * the copy already costs on this same thread. Past the budget the copy runs unlocked but VERIFIED —
   * {@code copyChunkDataChecked} turns what used to be silent corruption into a refusal, and the writer's
   * rename-based publication makes the retry above almost always succeed.
   *
   * <p>This is now the FALLBACK path, not the normal one: the control layer copies the template on its own
   * staging thread and hands us a populated folder (see {@link #backendCloneStagingFolder}), and we only
   * copy here when that did not happen — a backend/template the staging step declined, or a staged copy it
   * refused and cleaned up. So the budget stays small on purpose: it caps the stall on the rare path
   * instead of pretending the world thread can afford to wait. The unhurried wait lives on the staging
   * thread, where nobody is standing still.
   */
  private static final long CLONE_LOCK_WAIT_MILLIS = 500L;

  /**
   * The control layer stages a clone straight into the folder this world is created from, so the copy
   * happens off the world thread. Returning the real destination is what makes the hoist possible; the
   * {@code staged} flag in {@code backendAcquire}/{@code cloneTemplate} is what keeps every other part of
   * a clone (template spawn, land spawn, border) identical to when the copy happened here.
   */
  @Override
  protected Path backendCloneStagingFolder(WorldRequest request) {
    return request != null && request.kind() == WorldKind.CLONE ? dimensionFolder(request) : null;
  }

  private boolean cloneTemplate(WorldRequest request, Path destination, boolean staged) {
    Path source = worldRoot().resolve(request.templateRuntimeName().replace('\\', '/'));
    if (staged) {
      // The chunk data is already here, copied off the world thread and verified there
      // (WorldClone.copyChunkDataChecked); a copy it could not verify left no folder behind, so a staged
      // folder is a complete one. All that is left of this method is the spawn read below — which must
      // still happen, because the world border is centred on it.
      pendingCloneSpawn = readLevelSpawn(source.resolve("level.dat")).orElse(null);
      return true;
    }
    // Copy ONLY the chunk data (region/entities/poi/data) — NOT level.dat, uid.dat, datapacks/ or any
    // dimension metadata. Those carry the template's world IDENTITY + datapack-defined dimensions, and
    // copying them makes Paper reject the load as "a duplicate of another world ... duplicated Paper world
    // metadata", which is why every clone was failing. With just chunk data, backendAcquire creates a
    // fresh, unique world (its own level.dat/UID) and the template's blocks load on top.
    //
    // Under the SHARED lock of the root that really holds `source` — resolved through symlinks, so a
    // template linked into the shared map tree locks there (MapBundle.LOCK_FILE, the same file a seeding pass takes
    // exclusively) so a map refresh on this node — or, once worlds/ is a shared directory, on any other
    // node — cannot rewrite the template halfway through this walk. Without it the copy can pick up a
    // .mca that is being truncated and rewritten, or stitch old regions to new ones, and return SUCCESS:
    // the match then starts on a map with 512x512 holes in it and nothing anywhere says why.
    //
    // Scope is the copy and nothing else: the lock is released before backendAcquire goes on to
    // createWorld, which touches the new folder only, not the template.
    WorldClone.ChunkCopyResult result = MapBundle.underSharedWorldRootLock(
        worldRoot(), source, logger, CLONE_LOCK_WAIT_MILLIS, lockHeld -> {
          WorldClone.ChunkCopyResult attempt = WorldClone.copyChunkDataChecked(source, destination);
          if (attempt.failure() == WorldClone.CloneFailure.TEMPLATE_CHANGED && !lockHeld) {
            // We read the folder while somebody was rewriting it AND we could not get the lock that
            // would have prevented that. The writer publishes by rename now (MapBundle.extractToStaging),
            // so the window is microseconds wide and a single retry lands after it. Better than failing
            // into the vanilla-world fallback, which starts the match on random terrain.
            attempt = WorldClone.copyChunkDataChecked(source, destination);
          }
          return attempt;
        });
    if (!result.ok()) {
      // Name the real cause. This line used to say "no region data found" for every outcome, which sent
      // whoever read it hunting a missing map even when the truth was a concurrent refresh.
      logger.warning("Could not clone template '" + request.templateRuntimeName() + "' (" + source
          + "): " + result.failure() + ": " + result.detail());
      return false;
    }
    // Re-pin the template's stored spawn (read from its OWN level.dat in the source) so the player drops
    // onto the build, not at 0,0; an old map's build can sit far from origin (e.g. spawn at -772,31,426).
    pendingCloneSpawn = readLevelSpawn(source.resolve("level.dat")).orElse(null);
    return true;
  }

  /**
   * Minimal NBT read of a world's {@code SpawnX/Y/Z} (TAG_Int) from a gzipped {@code level.dat}. Pure byte
   * scan — no NBT library — so it survives the version spread of hand-downloaded maps (1.12 → 1.21). Returns
   * empty when the file is missing or the tags are absent (e.g. a modern level.dat that nests the spawn).
   */
  private static Optional<int[]> readLevelSpawn(Path levelDat) {
    if (!Files.isRegularFile(levelDat)) {
      return Optional.empty();
    }
    try (java.io.InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(levelDat))) {
      byte[] data = in.readAllBytes();
      Integer x = findIntTag(data, "SpawnX");
      Integer y = findIntTag(data, "SpawnY");
      Integer z = findIntTag(data, "SpawnZ");
      return (x == null || y == null || z == null) ? Optional.empty() : Optional.of(new int[]{x, y, z});
    } catch (IOException exception) {
      return Optional.empty();
    }
  }

  /** Finds a TAG_Int ({@code 0x03}, big-endian name length + name, then a 4-byte int) and returns its value. */
  private static Integer findIntTag(byte[] data, String name) {
    byte[] key = new byte[3 + name.length()];
    key[0] = 0x03;
    key[1] = (byte) ((name.length() >> 8) & 0xFF);
    key[2] = (byte) (name.length() & 0xFF);
    for (int i = 0; i < name.length(); i++) {
      key[3 + i] = (byte) name.charAt(i);
    }
    int at = indexOf(data, key);
    if (at < 0 || at + key.length + 4 > data.length) {
      return null;
    }
    int off = at + key.length;
    return ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
        | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private void applySettings(World world, WorldRequest request) {
    var settings = request.settings();
    if (settings == null) {
      return;
    }
    world.setAutoSave(settings.autoSave());
    world.setPVP(settings.pvp());
    world.setDifficulty(parseDifficulty(settings.difficulty()));
    if (settings.hardcore()) {
      // Re-asserted on every acquire: a world loaded from disk carries whatever its level.dat says, and a
      // hardcore experience must never come back as an ordinary one. Difficulty follows (vanilla hardcore
      // is HARD and cannot be lowered), so this runs after the difficulty above deliberately.
      world.setHardcore(true);
      world.setDifficulty(Difficulty.HARD);
    }
    if (settings.borderSize() > 0) {
      Location spawn = world.getSpawnLocation();
      new PaperWorldAdapter(world).setBorder(new WorldBorderSpec(
          spawn.getX(), spawn.getZ(), settings.borderSize(), settings.borderWarning(), settings.borderDamage()));
    } else if (request.kind() == WorldKind.PERSISTENT) {
      // Borderless experience: clear any 750-block border a previous build saved into this world's
      // level.dat, so existing experiences become borderless on the next load (idempotent; the lobby —
      // also border 0 — is a FLAT world, not PERSISTENT, so it is untouched).
      new PaperWorldAdapter(world).resetBorder();
    }
    if (settings.blockMobSpawn()) {
      world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
    }
    if (settings.lockTime()) {
      world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
      world.setTime(settings.timeOfDay());
    }
    if (request.kind() == WorldKind.PERSISTENT) {
      // Experiences own the player's inventory + XP (see the .yml player snapshot). The open-ended life
      // modes turn a depletion into a REAL death — death animation + death screen — so keepInventory
      // guarantees that neither items nor hard-earned XP are ever destroyed by dying. This is only the
      // DEFAULT: the owner may turn it off per experience, and ExperienceGame.applyKeepInventory re-applies
      // their choice to this world and its siblings whenever the experience starts (through the
      // WorldAdapter.setKeepInventory seam), so it always wins over the value set here.
      world.setGameRule(GameRule.KEEP_INVENTORY, true);
      // An experience world is survival: blocks must drop their loot. A world served from the warm pool
      // carries whatever its level.dat captured, and a regenerated world reloads from a folder that could
      // have inherited a doTileDrops=false from a template or an older build — silently swallowing every
      // drop. Re-asserting it on every acquire guarantees drops without depending on the default.
      world.setGameRule(GameRule.DO_TILE_DROPS, true);
      // An experience places its players at a spawn IT chose (the land-spawn search, or a challenge's own
      // island). Vanilla's respawn scatter would throw them somewhere else in a radius around it, which for
      // a SkyBlock-shaped world can mean open void. Pin it to the exact spawn.
      world.setGameRule(GameRule.SPAWN_RADIUS, 0);
    }
  }

  // ===== per-experience Nether / End siblings ==================================================

  private static final String NETHER_SUFFIX = "_nether";
  private static final String END_SUFFIX = "_end";

  private boolean linkedDimensionsEnabled() {
    return configuration.getBoolean("worlds.experiences.linked-dimensions", true);
  }

  /** Whether {@code keyPath} is an experience OVERWORLD (not one of its _nether/_end siblings). */
  private static boolean isOverworldKey(String keyPath) {
    return keyPath != null && !keyPath.endsWith(NETHER_SUFFIX) && !keyPath.endsWith(END_SUFFIX);
  }

  /**
   * Eagerly creates/loads the Nether and End siblings of a freshly acquired experience overworld, so a
   * player who builds a nether portal or activates the (vanilla) End portal lands in THIS experience's
   * own dimensions rather than the server defaults. Done eagerly (on the world thread that ran the
   * acquire) to avoid creating a world inside a portal event — which is unsafe on Folia and races vanilla
   * linking. The redirect itself lives in {@link PaperPortalListener}, which only resolves these.
   */
  private void ensureExperienceSiblings(World overworld, WorldRequest request) {
    if (overworld == null || request.kind() != WorldKind.PERSISTENT || !linkedDimensionsEnabled()) {
      return;
    }
    if (!naming.experiencesNamespace().equalsIgnoreCase(request.namespace()) || !isOverworldKey(request.keyPath())) {
      return;
    }
    try {
      // A Classic-Skyblock experience wants its Nether void too (to mirror the void Overworld); the End is
      // always left as normal terrain.
      boolean voidNether = request.settings() != null && request.settings().voidNether();
      // Hardcore is a property of the whole experience, so its Nether and End are born hardcore too —
      // otherwise stepping through a portal would hand the player ordinary hearts.
      boolean hardcore = request.settings() != null && request.settings().hardcore();
      createOrLoadSibling(overworld, World.Environment.NETHER, voidNether, hardcore);
      createOrLoadSibling(overworld, World.Environment.THE_END, false, hardcore);
    } catch (RuntimeException exception) {
      logger.warning("Could not provision linked dimensions for experience '" + request.runtimeName()
          + "': " + exception.getMessage());
    }
  }

  /** Resolves an experience world's sibling dimension (overworld/nether/end); never creates. */
  public World experienceDimension(World source, World.Environment targetEnv) {
    if (source == null || source.getKey() == null) {
      return null;
    }
    String namespace = naming.experiencesNamespace();
    if (!source.getKey().getNamespace().equalsIgnoreCase(namespace)) {
      return null;
    }
    String targetPath = dimensionPath(stripDimensionSuffix(source.getKey().getKey()), targetEnv);
    return server.getWorld(new NamespacedKey(namespace, targetPath));
  }

  /**
   * The already-loaded sibling of {@code source} in {@code targetEnv}, resolved from the key suffix
   * convention alone ({@code <key>}, {@code <key>_nether}, {@code <key>_end}) and therefore usable from
   * a bare world handle with no access to the control instance — which is what
   * {@link PaperWorldAdapter#dimension} needs. Never creates a world; returns null when the sibling is
   * not loaded (or {@code source} is not a keyed managed world).
   */
  public static World siblingDimension(World source, World.Environment targetEnv) {
    if (source == null || source.getKey() == null || targetEnv == null) {
      return null;
    }
    String parentPath = stripDimensionSuffix(source.getKey().getKey());
    String targetPath = dimensionPath(parentPath, targetEnv);
    return Bukkit.getServer().getWorld(new NamespacedKey(source.getKey().getNamespace(), targetPath));
  }

  /** The canonical runtime name of a managed world's sibling dimension (same suffix convention). */
  public static String siblingKeyPath(String runtimeName, World.Environment targetEnv) {
    return runtimeName == null ? null : dimensionPath(stripDimensionSuffix(runtimeName), targetEnv);
  }

  /**
   * The Bukkit {@link WorldType} for a request's terrain preset. A VOID world has no natural terrain to
   * shape, so it always stays {@code NORMAL} and lets {@link VoidChunkGenerator} own the result.
   */
  private static WorldType nativeWorldType(com.sexidium.core.world.WorldSettings settings) {
    if (settings == null || settings.voidWorld()) {
      return WorldType.NORMAL;
    }
    return switch (settings.terrain()) {
      case SUPERFLAT -> WorldType.FLAT;
      case LARGE_BIOMES -> WorldType.LARGE_BIOMES;
      case AMPLIFIED -> WorldType.AMPLIFIED;
      default -> WorldType.NORMAL;
    };
  }

  /** The Bukkit environment a core {@link WorldDimension} maps to. */
  public static World.Environment environmentOf(WorldDimension dimension) {
    return switch (dimension == null ? WorldDimension.OVERWORLD : dimension) {
      case NETHER -> World.Environment.NETHER;
      case END -> World.Environment.THE_END;
      default -> World.Environment.NORMAL;
    };
  }

  private static String dimensionPath(String parentPath, World.Environment targetEnv) {
    return switch (targetEnv) {
      case NETHER -> parentPath + NETHER_SUFFIX;
      case THE_END -> parentPath + END_SUFFIX;
      default -> parentPath;
    };
  }

  /** True when {@code world} is an experience world (its key is under the experiences namespace). */
  public boolean isExperienceWorld(World world) {
    return world != null && world.getKey() != null
        && naming.experiencesNamespace().equalsIgnoreCase(world.getKey().getNamespace());
  }

  /**
   * Warns when a sibling that ALREADY EXISTS was generated the wrong way for what the experience now
   * wants — the case that matters being a mode that has since started asking for a void Nether finding an
   * ordinary one.
   *
   * <p>How a world generates is decided once, when its chunks are first written; it cannot be retrofitted
   * onto terrain that is already on disk. Regenerating it here would silently destroy whatever the player
   * has built in there, which is not a decision this code gets to make — so it says so plainly and leaves
   * the world alone. The remedy is a new experience, or deleting that dimension's folder to let it
   * regenerate.</p>
   */
  private void warnIfGenerationMismatch(World existing, NamespacedKey key, boolean voidGen) {
    if (!voidGen || existing.getGenerator() instanceof VoidChunkGenerator) {
      return;
    }
    logger.warning("Experience dimension '" + key + "' wants VOID generation but already exists with"
        + " normal terrain — it was created before this mode asked for a void world. Terrain cannot be"
        + " changed after a world is generated, so it is left as it is. Create a new experience, or"
        + " delete that dimension's folder, to get the void version.");
  }

  private World createOrLoadSibling(World overworld, World.Environment env, boolean voidGen, boolean hardcore) {
    String namespace = naming.experiencesNamespace();
    String suffix = env == World.Environment.NETHER ? NETHER_SUFFIX : END_SUFFIX;
    NamespacedKey key = new NamespacedKey(namespace, overworld.getKey().getKey() + suffix);
    World existing = server.getWorld(key);
    if (existing != null) {
      warnIfGenerationMismatch(existing, key, voidGen);
      // Default parity with the overworld; the experience re-applies the owner's own choice on start.
      existing.setGameRule(GameRule.KEEP_INVENTORY, true);
      if (env == World.Environment.THE_END) {
        ensureEndEntryPlatform(existing);
      }
      return existing;
    }
    // Prefer a warm world from the pool: an experience needs BOTH a Nether and an End, so provisioning
    // them by generation meant every start paid for three world generations back to back — the freeze the
    // pool exists to remove. Adoption renames a ready world of the right shape into place instead.
    World adopted = adoptSibling(key, env, voidGen, hardcore);
    if (adopted != null) {
      finishSibling(adopted, env, overworld.getKey());
      return adopted;
    }
    WorldCreator creator = WorldCreator.ofKey(key);
    if (voidGen) {
      // Void sibling (Classic Skyblock's Nether mirror): empty terrain in the correct environment, with a
      // matching single-biome provider so the dimension stays valid. The challenge builds its island in.
      creator.environment(env).type(WorldType.NORMAL).generateStructures(false);
      creator.generator(new VoidChunkGenerator(voidBiome(env)));
    } else {
      World template = server.getWorld(NamespacedKey.minecraft(vanillaKeyOf(env)));
      if (template == null) {
        template = overworld;
      }
      creator.copy(template);
      creator.environment(env).type(WorldType.NORMAL).generateStructures(true);
    }
    // Share the overworld's seed so the linked dimensions are coherent and deterministic.
    creator.seed(overworld.getSeed());
    creator.hardcore(hardcore);
    // Skip the spawn-area prep loop and the safe-spawn search: players reach siblings via portals, never
    // the world spawn, so neither is needed and both stall the server thread (see backendAcquire).
    creator.keepSpawnLoaded(TriState.FALSE);
    creator.forcedSpawnPosition(Position.block(0, 64, 0), 0.0F, 0.0F);
    World sibling = server.createWorld(creator);
    if (sibling == null) {
      return null;
    }
    finishSibling(sibling, env, overworld.getKey());
    return sibling;
  }

  /**
   * Takes a warm world of the right shape out of the pool and renames it into {@code key}. Returns null
   * when nothing suitable is warm, and the caller generates one as before.
   */
  private World adoptSibling(NamespacedKey key, World.Environment env, boolean voidGen, boolean hardcore) {
    WorldProfile profile = new WorldProfile(dimensionOf(env), voidGen, null);
    WorldHandle pooled = takePooled(profile).orElse(null);
    if (pooled == null) {
      return null;
    }
    WorldRequest request = new WorldRequest(WorldKind.PERSISTENT, key.getNamespace(), key.getKey(),
        naming.experienceRuntimeName(key.getKey()), null, null,
        persistentSiblingSettings(dimensionOf(env), voidGen, hardcore));
    Optional<WorldHandle> adopted = backendAdopt(pooled, request);
    if (adopted.isEmpty()) {
      // Taken but unusable — dispose of it rather than leaking an orphan folder.
      discardByName(pooled.runtimeName());
      return null;
    }
    return adopted.get() instanceof PaperWorldHandle paperHandle ? paperHandle.world() : null;
  }

  /** The settings an adopted sibling is loaded with: borderless, persistent, in its own dimension. */
  private WorldSettings persistentSiblingSettings(WorldDimension dimension, boolean voidGen, boolean hardcore) {
    return persistentSettings()
        .withGeneration(new WorldGeneration(voidGen, false, WorldTerrain.NORMAL, dimension, hardcore));
  }

  /** The finishing touches every experience sibling gets, however it was provisioned. */
  private void finishSibling(World sibling, World.Environment env, NamespacedKey overworldKey) {
    new PaperWorldAdapter(sibling).resetBorder(); // borderless, like the experience overworld
    // Default parity with the overworld; the experience re-applies the owner's own choice on start.
    sibling.setGameRule(GameRule.KEEP_INVENTORY, true);
    if (multiverse != null) {
      multiverse.importWorld(sibling);
    }
    if (env == World.Environment.THE_END) {
      ensureEndEntryPlatform(sibling);
    }
    logger.info("[" + Branding.label(configuration) + "] Linked " + env + " dimension for experience '"
        + overworldKey + "' -> '" + sibling.getKey() + "'.");
  }

  /** The core dimension a Bukkit environment maps to (the inverse of {@link #environmentOf}). */
  private static WorldDimension dimensionOf(World.Environment environment) {
    return switch (environment) {
      case NETHER -> WorldDimension.NETHER;
      case THE_END -> WorldDimension.END;
      default -> WorldDimension.OVERWORLD;
    };
  }

  /** Builds the standard 5×5 obsidian entry platform at (100,48,0) so an End arrival is never a void drop. */
  private void ensureEndEntryPlatform(World end) {
    int baseY = 48;
    for (int x = 98; x <= 102; x++) {
      for (int z = -2; z <= 2; z++) {
        end.getBlockAt(x, baseY, z).setType(org.bukkit.Material.OBSIDIAN, false);
        for (int y = baseY + 1; y <= baseY + 3; y++) {
          end.getBlockAt(x, y, z).setType(org.bukkit.Material.AIR, false);
        }
      }
    }
  }

  private static String stripDimensionSuffix(String keyPath) {
    if (keyPath == null) {
      return "";
    }
    if (keyPath.endsWith(NETHER_SUFFIX)) {
      return keyPath.substring(0, keyPath.length() - NETHER_SUFFIX.length());
    }
    if (keyPath.endsWith(END_SUFFIX)) {
      return keyPath.substring(0, keyPath.length() - END_SUFFIX.length());
    }
    return keyPath;
  }

  @Override
  public boolean deletePersistent(com.sexidium.core.world.WorldKey worldKey) {
    // Delete the experience's linked Nether/End siblings first (unload + remove their folders), then let
    // the base class delete the overworld. Siblings live in their own folders OUTSIDE the overworld's, so
    // the base single-folder delete does not cascade to them.
    String key = worldKey == null ? null : worldKey.key();
    if (key != null && isOverworldKey(key)) {
      String namespace = naming.experiencesNamespace();
      for (String suffix : new String[] {NETHER_SUFFIX, END_SUFFIX}) {
        World sibling = server.getWorld(new NamespacedKey(namespace, key + suffix));
        if (sibling != null) {
          evacuateToOverworld(sibling, key);
          // save=true: the overworld delete below can still REFUSE (its own unload fails while
          // players are mid-teleport), and then this experience keeps being played -- with a Nether
          // and End that would have silently lost everything since the last autosave.
          if (!server.unloadWorld(sibling, true)) {
            // Same rule as the base class, per sibling: a world we could not unload is left entirely
            // alone. Deleting its folder would leave a loaded world with nothing behind it.
            logger.severe("Refusing to delete experience dimension '" + key + suffix
                + "': it could not be unloaded. Nothing was removed.");
            return false;
          }
        }
      }
    }
    // The overworld goes FIRST, and the siblings' folders only after it succeeded. The old order
    // removed them up front and then delegated -- so a refusal from the base class (the overworld
    // could not be unloaded, e.g. players are still inside it) left a live, playable experience whose
    // Nether and End no longer existed on disk. Losing the delete is recoverable; that was not.
    if (!super.deletePersistent(worldKey)) {
      return false;
    }
    boolean removed = true;
    if (key != null && isOverworldKey(key)) {
      String namespace = naming.experiencesNamespace();
      for (String suffix : new String[] {NETHER_SUFFIX, END_SUFFIX}) {
        forgetRegistration(namespace, key + suffix);
        // Read, exactly like the base class reads its own two: the caller drops the registry row on a
        // true, so a sibling folder that outlived the delete would be an orphan nobody can name.
        Path sibling = experienceFolderFor(key + suffix);
        if (!deleteDirectory(sibling)) {
          removed = false;
          logger.severe("Deleted experience world '" + key + "', but its dimension folder '" + sibling
              + "' is still on disk. That world is unloaded, so nothing is using it: remove it by"
              + " hand.");
        }
      }
      forgetRegistration(namespace, key);
    }
    return removed;
  }

  /**
   * Close this experience's linked dimensions when the experience is released.
   *
   * <p>Nothing else did. They are created by {@code ensureExperienceSiblings}, outside the name
   * registry and outside the placement lease, so a released experience used to leave two worlds open
   * on this node with nothing recording that fact — and on shared storage a peer would then open the
   * same files. {@code save=true}: this is a release, not a delete, and the chunks must reach disk.</p>
   */
  @Override
  protected void releaseSiblings(WorldHandle handle) {
    String key = handle == null ? null : experienceKeyPathOf(handle.runtimeName());
    if (key == null || !isOverworldKey(key)) {
      return;
    }
    String namespace = naming.experiencesNamespace();
    for (String suffix : siblingKeySuffixes()) {
      World sibling = server.getWorld(new NamespacedKey(namespace, key + suffix));
      if (sibling == null) {
        continue;
      }
      evacuateToOverworld(sibling, key);
      if (!server.unloadWorld(sibling, true)) {
        logger.warning("Could not unload experience dimension '" + key + suffix
            + "'; it stays open on this node with no lease covering it.");
      }
    }
  }

  /** Paper gives every experience a linked Nether and End, so a name claims all three. */
  @Override
  protected List<String> siblingKeySuffixes() {
    return List.of(NETHER_SUFFIX, END_SUFFIX);
  }

  /**
   * Drops a world from Multiverse's registry as part of deleting it.
   *
   * <p>This has to happen for EVERY deleted world, not just ones that happened to be loaded. Multiverse
   * autoloads what is on its books before Sexidium is even enabled, so a registration left behind for a
   * folder we have deleted becomes a {@code WORLD_FOLDER_INVALID} error on every subsequent boot, for
   * ever. Deleting an experience used to unregister only the overworld, and only when it was loaded at
   * the time — which is why a server ended up logging a wall of failures for experiences whose owner had
   * deleted them long ago, including their Nether and End siblings, which were never unregistered at all.</p>
   */
  private void forgetRegistration(String namespace, String keyPath) {
    if (multiverse == null) {
      return;
    }
    // Multiverse may know the world by its key form or by the flattened Bukkit name, depending on how it
    // was imported and which MV version wrote the entry — so retire every form it could be filed under.
    multiverse.forgetWorld(namespace + ":" + keyPath);
    multiverse.forgetWorld(namespace + "_" + keyPath.replace('/', '_'));
    multiverse.forgetWorld(keyPath);
  }

  /**
   * Reconciles Multiverse's registry with what is actually on disk at boot: any world it still lists
   * under a namespace WE own, whose dimension folder no longer exists, is dropped. This is the repair for
   * registrations that predate the delete-time fix above — without it a server keeps logging the same
   * autoload failures for ever, since nothing else ever revisits those entries.
   *
   * <p>Deliberately limited to our own namespaces and to worlds with no folder: an operator's own worlds,
   * and anything that merely failed to load for another reason, are never touched.</p>
   */
  @Override
  protected void backendForgetRegistration(String runtimeName) {
    String keyPath = experienceKeyPathOf(runtimeName);
    if (keyPath == null || keyPath.isBlank()) {
      return;
    }
    String namespace = naming.experiencesNamespace();
    forgetRegistration(namespace, keyPath);
    // The linked dimensions travel with their overworld and are registered the same way, so they have
    // to be retired the same way -- they were never unregistered at all before.
    for (String suffix : siblingKeySuffixes()) {
      forgetRegistration(namespace, keyPath + suffix);
    }
  }

  /**
   * Drop every experience registration this node has no claim on, at boot.
   *
   * <p>The check this replaces asked "is the folder missing?", which on shared storage is never true —
   * so a node kept a registration for every world it had ever touched, for ever, and each of N workers
   * accumulated the whole park. Multiverse autoloads what is on its books BEFORE Sexidium is enabled,
   * so on the next boot every one of them opened those worlds concurrently with no placement claim in
   * sight. The question that matters is not whether the bytes exist — on one shared tree they always
   * do — but whether this node has any business opening them.</p>
   */
  @Override
  protected void backendPruneForeignRegistrations(java.util.Set<String> keysThisNodeHolds) {
    if (multiverse == null) {
      return;
    }
    String experiences = naming.experiencesNamespace();
    int removed = 0;
    for (String registered : multiverse.registeredWorldNames()) {
      String normalized = registered.replace(':', '/');
      int slash = normalized.indexOf('/');
      String namespace = slash > 0 ? normalized.substring(0, slash) : null;
      String keyPath = slash > 0 ? normalized.substring(slash + 1)
          : (registered.startsWith(experiences + "_") ? registered.substring(experiences.length() + 1) : null);
      if (keyPath == null || (namespace != null && !experiences.equals(namespace))) {
        continue; // an operator's own world, or a namespace we do not manage
      }
      if (keysThisNodeHolds.contains(stripDimensionSuffix(keyPath))) {
        continue; // ours, claim and all
      }
      multiverse.forgetWorld(registered);
      removed++;
    }
    if (removed > 0) {
      logger.info("Dropped " + removed + " Multiverse registration(s) for experience worlds this node"
          + " does not hold. They stay on disk; this node simply stops autoloading somebody else's.");
    }
  }

  @Override
  protected void backendCleanupStaleRegistrations() {
    if (multiverse == null) {
      return;
    }
    List<String> managed = List.of(naming.experiencesNamespace(), naming.tempNamespace());
    int removed = 0;
    for (String registered : multiverse.registeredWorldNames()) {
      String normalized = registered.replace(':', '/');
      int slash = normalized.indexOf('/');
      if (slash <= 0) {
        continue;
      }
      String namespace = normalized.substring(0, slash);
      String keyPath = normalized.substring(slash + 1);
      if (!managed.contains(namespace) || Files.exists(dimensionsRoot().resolve(namespace).resolve(keyPath))) {
        continue;
      }
      multiverse.forgetWorld(registered);
      removed++;
    }
    if (removed > 0) {
      logger.info("Dropped " + removed + " stale Multiverse registration(s) for deleted Sexidium worlds.");
    }
  }

  /** Moves any occupants of a doomed sibling back to its overworld (or the lobby) before it unloads. */
  private void evacuateToOverworld(World sibling, String overworldKey) {
    if (sibling.getPlayers().isEmpty()) {
      return;
    }
    World overworld = server.getWorld(new NamespacedKey(naming.experiencesNamespace(), overworldKey));
    Location target = overworld != null ? overworld.getSpawnLocation()
        : (server.getWorlds().isEmpty() ? null : server.getWorlds().get(0).getSpawnLocation());
    if (target != null) {
      for (org.bukkit.entity.Player player : sibling.getPlayers()) {
        player.teleport(target);
      }
    }
  }

  private void ensureMetadata(WorldRequest request) {
    if (request.kind() != WorldKind.PERSISTENT) {
      return;
    }
    try {
      Files.createDirectories(experienceFolderFor(request.keyPath()));
    } catch (IOException exception) {
      logger.warning("Could not create experience metadata folder for '" + request.keyPath() + "': " + exception.getMessage());
    }
  }

  private NamespacedKey keyFor(WorldRequest request) {
    return namespacedKey(request.namespace(), request.keyPath());
  }

  private static NamespacedKey namespacedKey(String namespace, String keyPath) {
    try {
      return new NamespacedKey(namespace.toLowerCase(Locale.ROOT), keyPath.toLowerCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  /** A Bukkit world name (label) for a request: the key path flattened, kept unique by its id/short. */
  private static String bukkitName(WorldRequest request) {
    return (request.namespace() + "_" + request.keyPath().replace('/', '_')).toLowerCase(Locale.ROOT);
  }

  private static boolean segmentEquals(Path path, String name) {
    return path.getFileName() != null && path.getFileName().toString().equalsIgnoreCase(name);
  }

  private static Difficulty parseDifficulty(String raw) {
    if (raw == null) {
      return Difficulty.NORMAL;
    }
    try {
      return Difficulty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return Difficulty.NORMAL;
    }
  }
}
