package com.sexidium.core.network;

import java.util.List;

/**
 * Everything a drain has to <em>do</em> to the running server, as a seam.
 *
 * <p>{@link DrainCoordinator} owns the state machine and nothing else. It knows about rows, leases,
 * fences and phases; it knows nothing about menus, matches, experiences or players. That split is not
 * tidiness — it is what makes the twelve drain cases (nobody volunteers, the volunteer dies, the node
 * never comes back, two nodes drain at once, a player joins mid-drain) plain unit tests driven by a
 * hand-advanced clock, with no server, no world and no sleeping anywhere.</p>
 *
 * <p>Every method has a no-op default and the whole interface has a {@link #NOOP}, so a node whose
 * platform has not wired one drains correctly — it simply reports nothing left to do, which for a
 * node with no game layer is the truth.</p>
 *
 * <p><b>Never throws.</b> These run inside the network heartbeat. A throw here costs the tick, and a
 * node that stops checking in is reaped and loses its worlds — a far worse outcome than a drain that
 * stalls and tells an operator so.</p>
 */
public interface DrainWorkPort {

  /** How many players are on this node right now. */
  default int onlinePlayers() {
    return 0;
  }

  /**
   * Tell everyone online that this node is updating and they are about to be moved.
   *
   * <p>Called once, at ANNOUNCED. The honest promise is <b>no disconnect, no data loss, one loading
   * screen</b> — not invisibility — and the message says exactly that.</p>
   */
  default void announceDrain() {
  }

  /**
   * Close every open Sexidium menu.
   *
   * <p>First thing in OFFERING, and load-bearing: a chest GUI whose listener goes away with the
   * process is an inventory a player can pull menu items out of as <em>real</em> items. This is the
   * difference between "one loading screen" and a duplication bug.</p>
   */
  default void closeMenus() {
  }

  /** Live minigame matches. These do not move (no serialised match state); they are ended at grace. */
  default int matchesInProgress() {
    return 0;
  }

  /**
   * End every live minigame through the reconnectable path, telling the players why.
   *
   * <p>Called once, at the grace boundary. A minigame has no disk anchor and no serialised state, so
   * migrating one would mean inventing both; ending it with the snapshot the reconnect path already
   * writes loses nobody anything but the round.</p>
   */
  default void endMatchesForUpdate() {
  }

  /** The persistent experience worlds this node currently has open. */
  default List<String> openExperienceWorlds() {
    return List.of();
  }

  /**
   * Hand one experience world over: capture every player's state, move them, release the claim.
   *
   * <p>The capture is the whole point — see the fix in {@code ExperienceGame.stop()}. Without it the
   * stop path clears inventories that were last written to disk up to one autosave ago.</p>
   */
  default void handOverWorld(String worldKey) {
  }

  /**
   * Send every player still on this node to a lobby node.
   *
   * <p>Deliberately with transfer reason {@code LOBBY}, which {@code DbTransferService.request} never
   * bounds or counts — so evacuating forty players cannot trip the circuit breaker that exists to
   * catch a two-node loop.</p>
   */
  default void evacuateToLobby() {
  }

  /**
   * Move this node's shared lobby groups and queue tickets to {@code targetNodeId}.
   *
   * <p>Membership is already database-backed, so this is a repoint and not a migration. Idempotent:
   * a re-run matches no rows.</p>
   */
  default void repointLobbyState(String targetNodeId) {
  }

  /**
   * Drain the asynchronous database writer queues and wait for them.
   *
   * <p>The gate between QUIESCENT and READY. Until this returns, a queued rank or friend write is
   * still in a single-threaded executor that {@code close()} would shut down under it.</p>
   */
  default void flushWriters() {
  }

  /** The answer for a node with no game layer: nothing to close, nothing to move, nobody here. */
  DrainWorkPort NOOP = new DrainWorkPort() {
  };
}
