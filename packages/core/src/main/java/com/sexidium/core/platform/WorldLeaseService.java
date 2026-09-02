package com.sexidium.core.platform;

import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.WorldGeneration;
import com.sexidium.core.world.WorldKey;
import com.sexidium.core.world.WorldProfile;

import java.nio.file.Path;
// WorldAdapter is in this same package (com.sexidium.core.platform); no import needed.
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface WorldLeaseService {
  boolean enabled();

  void start();

  void preserve(Collection<String> worldNames);

  Optional<WorldLease> reacquireByName(String worldName);

  void discardByName(String worldName);

  /**
   * Logical name of the configured lobby world (e.g. {@code "lobby"}).
   * Implementations resolve this from the active configuration so callers do
   * not have to know the path layout.
   */
  String lobbyName();

  /**
   * Filesystem root that hosts the lobby and temp worlds, relative to the
   * server home (e.g. {@code <server-home>/worlds}). Implementations should
   * resolve this from the active configuration and create the folder if it
   * does not yet exist.
   */
  Path worldRoot();

  /**
   * Path of the temp subfolder that holds disposable game worlds (e.g.
   * {@code <server-home>/worlds/temp}).
   */
  Path tempSubdir();

  /**
   * Fully qualified filesystem path of the lobby world folder.
   */
  default Path lobbyFolder() {
    return worldRoot().resolve(lobbyName());
  }

  /**
   * On-disk folder that holds the lobby world's DATA (and its co-located settings sidecars, e.g.
   * {@code sexidium-lobby.yml} written by {@code /lobby setspawn}). On MC 26.1+ the lobby is a custom
   * dimension, so its real data folder is not simply {@link #lobbyFolder()}; the active backend resolves
   * the true location. The default returns {@link #lobbyFolder()} for folder-based/no-op backends.
   */
  default Path lobbyDataFolder() {
    return lobbyFolder();
  }

  Optional<WorldPosition> lobbySpawn();

  /**
   * Borrows a ready-made world of {@code profile} from the warm pool, or empty when none is warm.
   *
   * <p>This is the seam ANY subsystem uses to get a world without paying for one. Creating a world is the
   * most expensive thing the plugin does — terrain generation and the safe-spawn search both run on the
   * server thread, so a world created while players are online is felt by everyone as a freeze. The pool
   * pays for it at boot instead, and taking one immediately starts generating its replacement, so the
   * cost stays off the critical path for as long as the pool is deep enough.</p>
   *
   * <p>Only shapes the pool actually warms can be served (see {@code worlds.temp.pool.*}); anything else
   * returns empty and the caller must generate normally.</p>
   */
  Optional<WorldLease> acquireReady(WorldProfile profile);

  /** The Overworld form, for the many callers that only ever want a plain disposable world. */
  default Optional<WorldLease> acquireReady() {
    return acquireReady(WorldProfile.OVERWORLD);
  }

  void acquireOrCreate(Collection<? extends PlayerAdapter> viewers, Consumer<WorldLease> onReady, Runnable onFailure);

  /**
   * Finalises a map edit: UNLOADS the disposable edit world held by {@code lease} <b>with save</b> (which
   * flushes every chunk to its region files and waits for chunk I/O to halt — the only reliable way to get
   * the admin's block edits onto disk; an in-place {@code World.save()} returns before the async chunk
   * writes finish), copies its chunk data ({@code region/entities/poi/data}) over the template map folder
   * {@code templateWorldName} under {@link #worldRoot()} (keeping the template's own {@code level.dat}),
   * then disposes the edit world. So structures the admin builds in the clone become part of the BASE map
   * that {@link #acquireOrCreateClone} stamps out for each match. Returns true when the copy succeeded.
   * Default: just dispose the lease (no persistence).
   */
  default boolean saveTemplateAndDispose(WorldLease lease, String templateWorldName) {
    if (lease != null) {
      lease.close();
    }
    return false;
  }

  /**
   * Acquires a disposable temp world that is a CLONE of the template world folder named
   * {@code templateWorldName} (resolved under {@link #worldRoot()}): the folder is copied into a fresh
   * temp world and loaded, so the match runs on an exact copy of a pre-built map (e.g. a TNT-War arena
   * with both bases). The clone is disposed like any temp world when the match ends, leaving the
   * template untouched. The default implementation degrades gracefully to {@link #acquireOrCreate} (a
   * freshly generated world) so platforms without folder-clone support still start the match.
   */
  default void acquireOrCreateClone(
      String templateWorldName,
      Collection<? extends PlayerAdapter> viewers,
      Consumer<WorldLease> onReady,
      Runnable onFailure
  ) {
    acquireOrCreate(viewers, onReady, onFailure);
  }

  /**
   * Like {@link #acquireOrCreateClone} but STRICT: if the template cannot be cloned, it invokes
   * {@code onFailure} instead of silently falling back to a freshly generated (vanilla) world. The map
   * editor uses this so an admin is never dropped into — and never saves — a vanilla world over a real
   * template map. Default delegates to the lenient clone.
   */
  default void acquireCloneStrict(
      String templateWorldName,
      Collection<? extends PlayerAdapter> viewers,
      Consumer<WorldLease> onReady,
      Runnable onFailure
  ) {
    acquireOrCreateClone(templateWorldName, viewers, onReady, onFailure);
  }

  /**
   * Folder name (a single path segment) of the persistent-experiences subdir under {@link #worldRoot()},
   * e.g. {@code "experience"}. Used both to build the on-disk path and to recover an experience's stable
   * key from a runtime world name like {@code worlds/experience/<nick>/<map>}. Implementations resolve
   * this from configuration.
   */
  default String experiencesSubdirName() {
    return "experience";
  }

  /**
   * Filesystem path of the subfolder that holds PERSISTENT, player-owned experience worlds (e.g.
   * {@code <server-home>/worlds/experience}). These are never recycled by the temp pool and never
   * deleted by stale-world cleanup.
   */
  default Path experiencesSubdir() {
    return worldRoot().resolve(experiencesSubdirName());
  }

  /**
   * Acquires a PERSISTENT experience world by its {@link WorldKey}: loads it if its folder already
   * exists under {@link #experiencesSubdir()}, otherwise creates it. The returned lease's
   * {@code close()} unloads the world but NEVER deletes it. Used for player-owned experiences that
   * must survive emptiness and server restarts. The default implementation fails (platforms without
   * persistent-world support).
   *
   * <p>A {@code WorldKey}, never a {@code String}. Three legacy arities used to sit in front of this
   * one, each taking a world NAME, and every caller spelled that name differently — which is how one
   * world came to hold several placement rows on several nodes.</p>
   */
  default void acquireOrCreatePersistent(
      WorldKey key,
      Collection<? extends PlayerAdapter> viewers,
      WorldGeneration generation,
      Consumer<WorldLease> onReady,
      Runnable onFailure
  ) {
    if (onFailure != null) {
      onFailure.run();
    }
  }

  /**
   * Permanently deletes a persistent experience world's folder. Only ever called on an explicit owner
   * delete (via the experience GUI), when the owner is banned, or once a regenerated world's predecessor
   * is verifiably empty — never automatically.
   *
   * @return true when the world is gone. <b>False means it is still there and still loaded</b>: a world
   *     that could not be unloaded is never deleted from disk, because deleting the folder out from
   *     under a live world leaves a "loaded ghost" that the acquire path will happily hand back as if it
   *     were fresh. Callers must not report success on a false.
   */
  default boolean deletePersistent(WorldKey key) {
    return false;
  }

  /**
   * Whether ANY dimension of this experience is open on this node right now — its Overworld, one of
   * its linked siblings, or one that is still closing.
   *
   * <p>Exists for the backup verbs, which read region files directly off the disk. There is no {@code
   * session.lock} on a keyed dimension folder, so nothing on disk says who has it.</p>
   *
   * <p><b>What this now refuses, and what it no longer refuses.</b> It still refuses everything that
   * REPLACES or REMOVES a folder: a restore (both worlds) and the delete at the end of a refresh. It no
   * longer refuses the READ side of a copy — see {@code worlds.experiences.allow-live-copy} and
   * {@link #saveExperienceNow}. A copy of an open world is protected by the inventory bracket in
   * {@code WorldClone.copyWorldFolderChecked}, which fails the copy rather than publishing a torn
   * replica; a folder deleted under a player has no such backstop, which is why that half stays
   * strict. Default false — a platform with no persistent worlds has none open.</p>
   */
  default boolean experienceWorldLoaded(WorldKey key) {
    return false;
  }

  /**
   * Asks the server to flush {@code key}'s Overworld <b>and both linked dimensions</b> to disk now, and
   * returns once the save call has been ISSUED for each one that is open here.
   *
   * <p>Called immediately before a live copy (backup / refresh / duplicate). Its whole job is to narrow
   * the window in which the folder about to be read is holding unwritten state.</p>
   *
   * <p><b>It does not close that window, and nothing here should be read as if it did.</b> Paper's
   * {@code World.save()} returns before the asynchronous chunk writes it schedules have landed — that
   * is exactly why an in-place save was never accepted as a flush in this codebase, and why the only
   * honest flush remains unload-with-save. So a copy taken right after this can still read a region
   * file that is being rewritten. What makes a live copy defensible is the before/after inventory
   * bracket in {@code WorldClone.copyWorldFolderChecked}, which refuses the copy outright when the
   * source moved under the read. <em>The bracket is the guarantee; this is the courtesy</em> — it puts
   * the recent state on disk so the copy is a copy of something current, and so that the ordinary case
   * (a world someone is standing still in) settles instead of drifting.</p>
   *
   * <p>Must be called ON THE WORLD THREAD: implementations issue the save inline rather than posting
   * it, because a save posted for later would land after the copy it exists to precede.</p>
   *
   * @return true when at least one dimension was actually asked to save. False means nothing here was
   *     open (the normal case for a closed world) or the platform has no save seam — neither is an
   *     error, and no caller may refuse on it.
   */
  default boolean saveExperienceNow(WorldKey key) {
    return false;
  }

  /**
   * Whether {@code key} (and every linked dimension it would claim) is unused — on disk and in memory.
   *
   * <p>Default false, deliberately: a backend that cannot answer must not have a copy written under a
   * name it cannot vouch for. It reads as "no free name here", and the caller reports a failure rather
   * than clobbering something it could not see.</p>
   */
  default boolean experienceKeyFree(WorldKey key) {
    return false;
  }

  /**
   * Whether this node can see {@code key}'s folder on its own disk right now — <b>presence only</b>.
   *
   * <p>A restore rewrites which folder two rows name and moves no bytes, so it is only correct on a
   * node where BOTH folders exist: swapping the rows from a node that can see one of them would leave
   * a live world naming a folder that is not there, and the entry path would then generate fresh
   * terrain into it. That is the entire question this answers.</p>
   *
   * <p><b>It is not a safety check and must never be read as one.</b> It says nothing about whether
   * anybody has the world open, and on the live deployment it cannot: the experiences tree is a
   * symlink into shared storage, the same device and inode from every Paper node, so this is true on
   * every node for every experience that has ever existed. "I can see the folder" and "the folder is
   * mine to act on" are different sentences, and only {@link
   * com.sexidium.core.game.experience.ExperienceBackupService.PlacementGate} answers the second.</p>
   *
   * <p>Default false, for the same reason {@link #experienceKeyFree} defaults false: a backend that
   * cannot answer must not be taken as having said yes.</p>
   */
  default boolean experienceFolderPresent(WorldKey key) {
    return false;
  }

  /**
   * Whether there is room on this node's disk to copy {@code source}'s folders once more.
   *
   * <p>Exists because a full disk had no distinct answer: the copy failed somewhere deep in the
   * verification bracket and the owner was told "the copy could not be finished", which is the same
   * sentence they get for a torn read. Asking first turns the one failure they can act on into its own
   * refusal, before anything is staged.</p>
   *
   * <p>Default true: a backend that cannot measure the disk must not refuse a copy that would have
   * worked. Being wrong in this direction costs a {@code FAILED} the caller already handles; being
   * wrong the other way makes backups impossible on any platform that cannot answer.</p>
   */
  default boolean roomToCopyExperience(WorldKey source) {
    return true;
  }

  /**
   * Copies every dimension folder of {@code source} to {@code destination}, off the world thread.
   *
   * <p>The engine half of the experience backup. A byte-for-byte copy of a keyed dimension folder
   * reproduces the world exactly — seed, generator registry, spawn, hardcore, difficulty, gamerules,
   * border, weather, clock, every per-player save and every counter the challenges wrote down — so
   * there is no metadata to reconstruct and nothing here knows what an experience IS.</p>
   *
   * <p>Copies land in a staging folder beside the destination and are published by rename, so a
   * half-written backup can never survive; anything that fails removes every folder it wrote and
   * reports false. {@code beforePublish} (nullable) is handed each staged folder while it is still
   * invisible, which is where the caller rewrites anything inside it that names the SOURCE world.
   * {@code onDone} always runs, on the world thread, exactly once.</p>
   *
   * <p>Default: no copy, and false — a platform that cannot do this must not pretend it did.</p>
   */
  default void copyExperienceWorld(
      WorldKey source,
      WorldKey destination,
      Consumer<Path> beforePublish,
      Consumer<Boolean> onDone
  ) {
    if (onDone != null) {
      onDone.accept(false);
    }
  }

  /**
   * The key a persistent experience world should be REGENERATED under — its own base plus the next free
   * generation marker ({@code …_ab12cd34} &rarr; {@code …_ab12cd34_r1}) — or null when none is available.
   *
   * <p>A regenerated world cannot reuse its own name. The old world is still loaded and still has players
   * standing in it while the new one is being built, so the two must coexist. That constraint turns out
   * to be a feature: because the name genuinely changes, the entry teleport into the new world is a real
   * world change, which is the only moment
   * {@link com.sexidium.core.game.EntryPolicy#prepareArrival} is allowed to re-send the hardcore view.</p>
   *
   * <p>Implementations must not return a key that is taken — on disk, loaded, or claimed by a linked
   * dimension — so a folder left behind by a crashed teardown is skipped rather than clobbered. Default
   * hands the key back unchanged, which is correct for a platform with no experience worlds.</p>
   */
  default WorldKey nextExperienceGeneration(WorldKey key) {
    return key;
  }

  void shutdown();

  /**
   * Garbage-collects orphaned disposable game worlds. A background sweep unloads and securely deletes
   * every temp world — whether still loaded in memory or only left on disk — that no longer backs a use:
   * it is not in {@code inUseWorldNames} (the authoritative names of live + reconnect-pending matches),
   * not in the warm pool, and not preserved. This reclaims worlds whose minigame ended without cleanup
   * or whose players all abandoned it, plus folders left behind by a crash. The lobby, persistent
   * experience worlds, the warm pool and in-use match worlds are never touched. Returns the number of
   * worlds reclaimed (best-effort; the sweep may run asynchronously on the world thread). Default no-op.
   */
  default int collectGarbage(Collection<String> inUseWorldNames) {
    return 0;
  }

  default void preserveSingle(String worldName) {
    if (worldName != null && !worldName.isBlank()) {
      preserve(List.of(worldName));
    }
  }
}
