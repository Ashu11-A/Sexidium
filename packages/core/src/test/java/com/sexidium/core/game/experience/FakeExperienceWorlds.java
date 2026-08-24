package com.sexidium.core.game.experience;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.WorldKey;
import com.sexidium.core.world.WorldProfile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A world layer that answers exactly what a test set it to, and records anything destructive.
 *
 * <p>Shared by every experience test that needs the folder half of the seam without a real disk: what
 * is open, which folders this node can see, whether there is room, and what was copied or deleted.
 * Recording matters as much as answering, because most of the guarantees around backups are NEGATIVE —
 * a refusal that quietly deleted something would still pass an outcome assertion.</p>
 */
final class FakeExperienceWorlds implements WorldLeaseService {

  /** World keys somebody has open right now. */
  final Set<String> loaded = new HashSet<>();
  /** World keys whose folder this node CANNOT see, i.e. non-shared storage that has diverged. */
  final Set<String> missingFolders = new HashSet<>();
  final List<String> deleted = new ArrayList<>();
  final List<String> copied = new ArrayList<>();
  /**
   * Every world key the engine asked to flush, and — because it is the SAME list {@link #copied}
   * appends to — in the order it asked relative to the copies. A save issued after the copy it exists
   * to precede is worse than no save at all, and only an ordering assertion can see that.
   */
  final List<String> calls = new ArrayList<>();
  final List<String> saved = new ArrayList<>();
  boolean roomOnDisk = true;
  boolean copySucceeds = true;
  boolean deleteSucceeds = true;
  /**
   * Ran INSIDE the copy, before it is answered — the window a real multi-hundred-megabyte copy leaves
   * open. Nothing else in this fake can express "the world was closed when we checked and open by the
   * time we acted", which is the shape of every re-entrancy bug around these verbs.
   */
  Runnable duringCopy;

  @Override
  public boolean experienceWorldLoaded(WorldKey key) {
    return key != null && loaded.contains(key.key());
  }

  @Override
  public boolean experienceFolderPresent(WorldKey key) {
    return key != null && !missingFolders.contains(key.key());
  }

  @Override
  public boolean experienceKeyFree(WorldKey key) {
    return true;
  }

  @Override
  public boolean roomToCopyExperience(WorldKey source) {
    return roomOnDisk;
  }

  @Override
  public boolean deletePersistent(WorldKey key) {
    deleted.add(key.key());
    return deleteSucceeds;
  }

  @Override
  public boolean saveExperienceNow(WorldKey key) {
    if (key == null) {
      return false;
    }
    saved.add(key.key());
    calls.add("save " + key.key());
    return loaded.contains(key.key());
  }

  @Override
  public void copyExperienceWorld(WorldKey source, WorldKey destination,
      Consumer<Path> beforePublish, Consumer<Boolean> onDone) {
    copied.add(source.key() + " -> " + destination.key());
    calls.add("copy " + source.key() + " -> " + destination.key());
    if (duringCopy != null) {
      duringCopy.run();
    }
    onDone.accept(copySucceeds);
  }

  // ----- the rest of the seam, which no backup test has an opinion about ------------------------

  @Override
  public boolean enabled() {
    return true;
  }

  @Override
  public void start() {
  }

  @Override
  public void preserve(Collection<String> worldNames) {
  }

  @Override
  public Optional<WorldLease> reacquireByName(String worldName) {
    return Optional.empty();
  }

  @Override
  public void discardByName(String worldName) {
  }

  @Override
  public String lobbyName() {
    return "lobby";
  }

  @Override
  public Path worldRoot() {
    return Path.of(".");
  }

  @Override
  public Path tempSubdir() {
    return Path.of("temp");
  }

  @Override
  public Optional<WorldPosition> lobbySpawn() {
    return Optional.empty();
  }

  @Override
  public Optional<WorldLease> acquireReady(WorldProfile profile) {
    return Optional.empty();
  }

  @Override
  public void acquireOrCreate(Collection<? extends PlayerAdapter> viewers,
      Consumer<WorldLease> onReady, Runnable onFailure) {
    onFailure.run();
  }

  @Override
  public void shutdown() {
  }
}
