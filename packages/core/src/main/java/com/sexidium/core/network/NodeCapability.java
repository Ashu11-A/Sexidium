package com.sexidium.core.network;

/**
 * What a node is allowed to do, as opposed to what it is called.
 *
 * <p>Capabilities rather than a flat {@code role} enum because role is what an operator configures
 * while capability is what the code branches on. A single {@code if (role == LOBBY)} reads fine the
 * first time and then multiplies: the lobby hosts NPCs <em>and</em> owns the queue <em>and</em> may
 * host the bot, and those three facts move independently — a two-worker deployment folds the queue
 * onto a worker, an external Discord service removes {@link #BOT_HOST} from every node. Each of those
 * is a capability edit, not a new role.</p>
 *
 * <p>Consumed in exactly one place per subsystem, at its registration line, so gameplay code never
 * asks what kind of node it is running on.</p>
 */
public enum NodeCapability {
  /** Hosts the persistent lobby world, its NPCs, decor, HUD and hotbar. */
  LOBBY,

  /** May host persistent experience worlds. Their folders live on this node's local disk. */
  EXPERIENCES,

  /** May host disposable minigame match worlds leased from the warm pool. */
  MINIGAMES,

  /** Owns party/queue state and runs the matchmaking tick. Exactly one node in a network. */
  QUEUE_AUTHORITY,

  /** Supervises the Discord bot child process. Exactly one node, guarded by a lease. */
  BOT_HOST,

  /**
   * Builds and serves the menu resource pack, and is the only node that offers it to a client.
   * More than one offering node means more than one pack SHA-1, which re-prompts the player on every
   * server switch.
   */
  PACK_HOST,

  /** Exposes the loopback HTTP/RPC bridge the Discord bot talks to. */
  API_HOST,

  /**
   * May write to a minigame map template: its chunk data and its {@code sexidium-*.yml} sidecars.
   *
   * <p>Exists because "one container is responsible for the maps" is otherwise a statement about a
   * directory rather than about the code. Three of the four writers into {@code worlds/} are the
   * in-game map editor, and all three used to run from <em>any</em> node with no gate at all: the
   * TNT War / Combat sidecar capture and the editor's Confirm, which copies the edited world back
   * over the template. Designating a writer without a declared authority makes nobody the sole
   * writer — it only makes the other writers undocumented.</p>
   *
   * <p>The failure it prevents is silent and permanent, which is why it is a capability and not a
   * warning: an admin captures bases on worker-2, the template on worker-2 diverges from the other
   * three nodes, and the next match on any other node starts with the old layout. Nothing errors.
   * With the gate the admin is told, at the moment of the attempt, which node to do it on.</p>
   *
   * <p>Deliberately separate from {@link #MINIGAMES}: hosting a match only <em>reads</em> a
   * template (it is cloned before it is loaded), so every worker keeps MINIGAMES while exactly one
   * node keeps the right to change what is cloned.</p>
   */
  MAP_AUTHORITY,

  /** Routes players between nodes. Only ever the proxy. */
  ROUTER
}
