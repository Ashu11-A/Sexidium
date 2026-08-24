package com.sexidium.core.network.transfer;

import java.util.Optional;
import java.util.UUID;

/**
 * The requesting half of a transfer: the backend that discovered a world lives somewhere else.
 *
 * <p>Idempotent on purpose, and that alone collapses the observed eight transfers into one. Asking for
 * the same destination for the same reason while a live ticket exists returns the SAME ticket and
 * moves nobody — the old {@code PlayerRouteService.request} explicitly made "the newest intent win",
 * so a repeated request was indistinguishable from a first one and pass eight of a loop looked exactly
 * like pass one.</p>
 */
public interface TransferDispatcher {

  /**
   * Ask for a player to be moved.
   *
   * <p>Empty means the request was REFUSED — the circuit breaker tripped, or the ticket could not be
   * written. The caller is expected to tell the player something and leave them where they are, which
   * is the outcome the old design had no way to express.</p>
   */
  Optional<TransferTicket> request(UUID playerId, String targetNode, long targetEpoch,
      TransferReason reason, String worldKey);

  /** The current state of a ticket this node minted. */
  Optional<TransferTicket> statusOf(String token);

  /** Drop a player's live ticket — they logged off, or a rolling restart made it meaningless. */
  void cancel(UUID playerId);

  /** Whether a live ticket exists for this player. Backs the launcher's "do not report a failure". */
  boolean inFlight(UUID playerId);
}
