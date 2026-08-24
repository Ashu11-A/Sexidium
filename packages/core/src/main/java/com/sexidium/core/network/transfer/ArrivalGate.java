package com.sexidium.core.network.transfer;

import java.util.Optional;
import java.util.UUID;

/**
 * The destination's half: "was this player sent HERE, and what for?".
 *
 * <p>Invariant I8 — a ticket is consumed by exactly the addressed {@code (node, epoch)} and by nobody
 * else. What this replaces was
 * {@code SELECT … WHERE player_uuid = ? AND state <> 'ABANDONED' AND deadline > ?} with no
 * {@code ORDER BY}, no {@code LIMIT 1}, no {@code node_id} and no {@code node_epoch}, taking whichever
 * row came back first. Live, one player had two rows in window — a fresh experience rendezvous and a
 * UUID-keyed roster row from the previous launch that nothing ever deleted — and when the roster row
 * won the coin flip the arrival was silently swallowed: nothing opened a world, the real ticket was
 * never consumed, and nothing was logged.</p>
 */
public interface ArrivalGate {

  /**
   * Atomically claim the ticket addressed to this node, if there is one.
   *
   * <p>Claiming is a guarded UPDATE, so two concurrent claimers cannot both succeed and a ticket
   * addressed to a different node — or to a previous boot of this one — is left alone.</p>
   */
  Optional<TransferTicket> claimArrival(UUID playerId, String myNode, long myEpoch);
}
