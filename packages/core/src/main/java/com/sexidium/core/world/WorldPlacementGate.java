package com.sexidium.core.world;

/**
 * Asked before this node creates or loads a persistent world.
 *
 * <p>Exists to close one specific hole. {@code acquireOrCreatePersistent} treats "no folder on disk"
 * as "generate a new world" — correct on a single server, catastrophic on a network: a player whose
 * experience lives on worker-2 arriving at worker-3 would have a fresh, empty world generated under
 * the same name, over the top of their saved map. The folder is on the other machine's disk, so the
 * only safe answer is to refuse and route them.</p>
 *
 * <p>Standalone uses {@link #ALLOW_ALL}, which is the behaviour that exists today: this node owns
 * every world it has, so there is nothing to arbitrate and no database is consulted.</p>
 */
@FunctionalInterface
public interface WorldPlacementGate {

  /** Whether this node may proceed, and if not, who owns the world. */
  record Decision(boolean allowed, String ownerNodeId, boolean busy) {

    public static Decision allow() {
      return new Decision(true, null, false);
    }

    /** The world belongs to another node; the caller must route rather than create. */
    public static Decision ownedBy(String nodeId) {
      return new Decision(false, nodeId, false);
    }

    /** The owning node currently has it open; loading it here would be a second writer. */
    public static Decision busyOn(String nodeId) {
      return new Decision(false, nodeId, true);
    }
  }

  // Decision.takeover and Decision.allowTakeover used to live here. `takeover` was PRODUCED by the
  // decider and read by nobody at all -- acquireOrCreatePersistent only ever tested allowed(). Its
  // stated purpose was to let the caller clear a session.lock left by a previous holder, which is
  // moot twice over: a keyed dimension folder has no session.lock (that file belongs to world/, which
  // is node-local), and deleting one while a holder is still alive is what defeats the guard.

  Decision check(String worldKey);

  /**
   * The claim the most recent successful {@link #check} minted, or null.
   *
   * <p>A seam, not a design. The gate answers with a boolean-shaped {@code Decision} while the world
   * layer needs the FENCE, and threading it through the return type would change every implementor
   * including the allow-all one a standalone server uses. Standalone mints no claims and answers
   * null, which the world layer reads as "there is nothing to renew here".</p>
   */
  default WorldClaim lastClaim() {
    return null;
  }

  /** The standalone gate: this node owns everything it can see. */
  WorldPlacementGate ALLOW_ALL = worldKey -> Decision.allow();
}
