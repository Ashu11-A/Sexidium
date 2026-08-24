package com.sexidium.core.world;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The lease/garbage bookkeeping seam: the managed / leased / preserved name registries and the
 * name-matching predicates (protected-set membership, preserved checks, the protected-name expansion
 * across namespace + segment + temp-runtime forms) that the disposal and GC paths consult.
 *
 * <p>Extracted from {@code AbstractWorldControl} so all name-set state lives in one place. It mutates
 * only its own sets and delegates every naming rule to {@link WorldNaming}; it makes no platform or
 * filesystem calls.</p>
 */
final class WorldNameRegistry {
  private final WorldNaming naming;
  // Concurrent for the same reason as WorldPool: these sets are written from the world thread when a
  // warm world is created and read/written from the caller's thread when one is handed out.
  private final Set<String> managedNames = ConcurrentHashMap.newKeySet();
  private final Set<String> leasedNames = ConcurrentHashMap.newKeySet();
  private final Set<String> preservedNames = ConcurrentHashMap.newKeySet();

  WorldNameRegistry(WorldNaming naming) {
    this.naming = naming;
  }

  // ----- mutation ------------------------------------------------------------------------------

  void addManaged(String name) {
    managedNames.add(name);
  }

  void removeManaged(String name) {
    managedNames.remove(name);
  }

  void addLeased(String name) {
    leasedNames.add(name);
  }

  void removeLeased(String name) {
    leasedNames.remove(name);
  }

  void clearLeased() {
    leasedNames.clear();
  }

  int leasedCount() {
    return leasedNames.size();
  }

  boolean isLeased(String name) {
    return leasedNames.contains(name);
  }

  /** Snapshot of every world this node currently holds open, for the placement lease heartbeat. */
  java.util.Set<String> leasedNames() {
    return java.util.Set.copyOf(leasedNames);
  }

  void registerLeased(String name) {
    managedNames.add(name);
    leasedNames.add(name);
    preservedNames.remove(name);
  }

  // ----- preserved ------------------------------------------------------------------------------

  void preserveSingle(String worldName) {
    if (worldName != null && !worldName.isBlank()) {
      preservedNames.add(worldName);
      preservedNames.add(WorldNaming.lastSegment(worldName));
    }
  }

  void preserve(Collection<String> worldNames) {
    if (worldNames == null) {
      return;
    }
    for (String worldName : worldNames) {
      preserveSingle(worldName);
    }
  }

  void unpreserve(String worldName) {
    preservedNames.remove(worldName);
    preservedNames.remove(WorldNaming.lastSegment(worldName));
  }

  boolean isPreserved(String name) {
    if (preservedNames.contains(name)) {
      return true;
    }
    String segment = WorldNaming.lastSegment(name);
    return segment != null && preservedNames.contains(segment);
  }

  // ----- protected-name expansion + matching ---------------------------------------------------

  Set<String> protectedNames(String lobbyRuntimeName, Collection<String> inUseWorldNames,
      Collection<WorldHandle> readyPool) {
    Set<String> names = new HashSet<>();
    addProtectedName(names, lobbyRuntimeName);
    if (inUseWorldNames != null) {
      for (String worldName : inUseWorldNames) {
        addProtectedName(names, worldName);
      }
    }
    for (String worldName : leasedNames) {
      addProtectedName(names, worldName);
    }
    for (String worldName : preservedNames) {
      addProtectedName(names, worldName);
    }
    for (WorldHandle handle : readyPool) {
      if (handle != null) {
        addProtectedName(names, handle.runtimeName());
      }
    }
    return names;
  }

  private void addProtectedName(Set<String> names, String worldName) {
    if (worldName == null || worldName.isBlank()) {
      return;
    }
    names.add(worldName);
    names.add(WorldNaming.stripNamespace(worldName));
    String segment = WorldNaming.lastSegment(worldName);
    if (segment != null && !segment.isBlank()) {
      names.add(segment);
      if (segment.startsWith(naming.tempPrefix())) {
        names.add(naming.tempRuntimeName(segment));
      }
    }
  }

  boolean isProtected(String worldName, Set<String> protectedNames) {
    if (worldName == null || protectedNames == null || protectedNames.isEmpty()) {
      return false;
    }
    if (protectedNames.contains(worldName) || protectedNames.contains(WorldNaming.stripNamespace(worldName))) {
      return true;
    }
    String segment = WorldNaming.lastSegment(worldName);
    if (segment != null && protectedNames.contains(segment)) {
      return true;
    }
    for (String protectedName : protectedNames) {
      if (WorldNaming.sameWorld(protectedName, worldName)) {
        return true;
      }
    }
    return false;
  }
}
