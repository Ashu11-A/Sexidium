package com.sexidium.core.network.transfer;

import java.util.UUID;

/**
 * Invariant I7: a player is never transferred to the same node more than K times per window.
 *
 * <p>There was no loop breaker anywhere in the system. The live failure was eight transfers of one
 * player between two nodes in 45 seconds, and every layer involved treated pass eight as if it were
 * pass one. Stages 2 and 3 remove the causes we found; this bounds whatever cause we did not, makes it
 * visible in the logs and in {@code stats()}, and turns an unbounded ping-pong into a bounded one with
 * an error message at the end of it.</p>
 *
 * <p>Deliberately PER PLAYER, not per node or per world: a party of six entering one experience is six
 * players with one transfer each, which no sane bound refuses. Three transfers of one player to one
 * node per minute is far above any legitimate rate.</p>
 */
public interface TransferCircuitBreaker {

  /** Whether this player may be sent to this node right now. */
  boolean allow(UUID playerId, String targetNode);

  /** Record what happened, so a repeat inside the window counts towards the bound. */
  void observe(UUID playerId, String targetNode, TransferState outcome);

  /** How many times the breaker has refused since this process started. For the admin surface. */
  long trips();
}
