package com.sexidium.core.network;

/**
 * How far through a rolling-update drain a node is.
 *
 * <p>Deliberately NOT a set of {@code network_nodes.state} values. That column is read as an opaque
 * string by {@link NodePlacementPlanner}, by the proxy's backend directory and by
 * {@link NodeRegistry.Node#alive}, so any node compiled before a new value existed would silently
 * misread it — and {@link NodeRegistry#STATE_DRAINING} already means exactly the right thing
 * (excluded from placement, still routable, still heartbeating). A drain therefore sits in DRAINING
 * for its whole window and records its progress in {@code node_drains} instead.</p>
 *
 * <p>The names are part of the operator contract: they are what {@code GET /node/drain} returns and
 * what the {@code SX-DRAIN phase=…} log lines carry, so an orchestrator can wait on them.</p>
 */
public enum DrainPhase {
  /** Not draining. The only phase a healthy node is ever in. */
  NONE,
  /** The drain was accepted: state is DRAINING, no new placements land here, players were told. */
  ANNOUNCED,
  /** Handing worlds, players, matches and queue tickets over. */
  OFFERING,
  /** Nothing left to hand over. The earliest point the container may be killed without cost. */
  QUIESCENT,
  /** Quiescent AND every writer queue flushed. This is the line to wait on before a restart. */
  READY,
  /** The drain could not complete inside its deadline — abort the roll, do not restart the node. */
  STALLED,
  /** This node booted and found its own drain row from a previous epoch; it is cleaning it up. */
  RECLAIMING
}
