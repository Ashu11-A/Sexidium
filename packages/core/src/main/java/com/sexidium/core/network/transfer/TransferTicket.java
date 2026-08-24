package com.sexidium.core.network.transfer;

import java.util.UUID;

/**
 * One transfer, end to end: minted by the deciding node, acted on by a proxy, consumed by exactly the
 * node it names.
 *
 * <p>It replaces two uncorrelated rows. A transfer used to be a {@code match_handoffs} row with a
 * 120-second TTL <em>and</em> a {@code player_routes} row with a 30-second TTL, written back to back,
 * with no token joining them, no target check at the destination, no idempotency, no acknowledgement
 * and no attempt bound. The observed consequence was one player transferred eight times in 45 seconds
 * with nothing in the system aware that it was happening.</p>
 *
 * @param token        the rendezvous token; unique, and what a status query is keyed by
 * @param playerId     who is being moved
 * @param sourceNode   who asked
 * @param targetNode   where they are going
 * @param targetEpoch  the destination's boot epoch — the fence that stops a ticket addressed to a
 *                     worker that has since restarted being consumed by its successor
 * @param reason       why, as a typed value rather than a string prefix
 * @param worldKey     the experience world they are going for, or null
 * @param state        where this transfer has got to
 * @param attempts     how many times a proxy has tried to move them
 * @param claimedBy    the executor id currently holding it, or null
 * @param expiresAt    when this ticket stops being actionable
 * @param detail       the last thing that happened to it, for an operator to read
 */
public record TransferTicket(
    String token,
    UUID playerId,
    String sourceNode,
    String targetNode,
    long targetEpoch,
    TransferReason reason,
    String worldKey,
    TransferState state,
    int attempts,
    String claimedBy,
    long expiresAt,
    String detail) {

  /** Whether {@code (node, epoch)} is the address on this ticket. Epoch 0 means "any epoch". */
  public boolean addressedTo(String nodeId, long nodeEpoch) {
    if (targetNode == null || !targetNode.equals(nodeId)) {
      return false;
    }
    // A zero epoch on either side is "unknown", not "wrong". A standalone node and a registry that
    // has not booted yet both report 0, and refusing those would break every single-server arrival.
    return targetEpoch == 0L || nodeEpoch == 0L || targetEpoch == nodeEpoch;
  }

  /** Whether this ticket names the same destination for the same purpose as {@code other}. */
  public boolean sameDestination(String node, TransferReason why, String world) {
    return java.util.Objects.equals(targetNode, node)
        && reason == why
        && java.util.Objects.equals(worldKey, world);
  }
}
