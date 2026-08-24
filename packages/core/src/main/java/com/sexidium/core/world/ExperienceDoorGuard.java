package com.sexidium.core.world;

/**
 * Invariant I3: a node without {@code NodeCapability.EXPERIENCES} may never open, create, delete or
 * regenerate an experience world.
 *
 * <p>The capability already existed and was consulted in exactly one place — {@code
 * PlacementDecider.adoptableHere} — so it decided where a world would be PLANNED and guarded none of
 * the doors that actually open one. There were four: {@code acquireOrCreatePersistent}'s
 * already-loaded fast path, {@code reacquirePersistent} (which had no placement check of any kind and
 * was reached from every single join), {@code reacquireByName} (which would happily CREATE), and
 * {@code deletePersistent}. The lobby restarts, imports every node's live match rows, indexes a
 * worker's players as pending, and the next reconnect opens the same region files a worker has open.
 *
 * <p>So the rule is stated once and checked in all four. Defaults to "yes" — a standalone server holds
 * every capability by definition, and so does every test that does not deliberately install a
 * refusing guard.</p>
 */
@FunctionalInterface
public interface ExperienceDoorGuard {

  /** Every node may open every experience world. Standalone, and the default for tests. */
  ExperienceDoorGuard ALLOW_ALL = key -> true;

  /** A node that must never touch experience worlds — the lobby, and the proxy. */
  ExperienceDoorGuard DENY_ALL = key -> false;

  /** Whether THIS node is permitted to open, create, delete or regenerate {@code key}. */
  boolean mayOpen(WorldKey key);
}
