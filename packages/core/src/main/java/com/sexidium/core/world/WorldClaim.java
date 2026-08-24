package com.sexidium.core.world;

/**
 * Proof that THIS node, on THIS boot, may open one world right now.
 *
 * <p>The fence is the part that did not exist. A lease used to be advisory: {@code renew} was
 * {@code WHERE world_key = ? AND node_id = ?}, so a node that had been dispossessed renewed zero rows
 * and never found out — its {@code UPDATE} simply matched nothing and returned. A 35-second GC pause,
 * a stalled query on the shared connection, or a brief partition therefore produced two servers with
 * the same region files open, and neither of them could tell.</p>
 *
 * <p>{@code fence} is a random non-zero 64-bit token minted per GRANT, not a counter. Fencing needs
 * uniqueness per grant, not ordering, and a random token needs no read-back (MariaDB has no
 * {@code UPDATE … RETURNING}) and no clock. Every guarded write carries
 * {@code AND node_id = ? AND node_epoch = ? AND fence = ?} and reports whether it matched — that
 * equality is how a holder learns it was evicted.</p>
 *
 * @param key        the world this claim is for
 * @param nodeId     who holds it
 * @param nodeEpoch  which boot of that node holds it
 * @param fence      the grant token; unique per grant, never zero
 * @param expiresAt  when the claim lapses unless renewed
 */
public record WorldClaim(WorldKey key, String nodeId, long nodeEpoch, long fence, long expiresAt) {

  public WorldClaim {
    if (key == null) {
      throw new IllegalArgumentException("A claim needs a world key.");
    }
    if (fence == 0L) {
      // Zero is the "never granted" value in the column, so a real grant must never write it.
      throw new IllegalArgumentException("A claim's fence is never zero.");
    }
  }

  /** Whether this claim is still valid by the clock. The DB remains the authority; this is a hint. */
  public boolean valid(long now) {
    return expiresAt > now;
  }

  /** The same claim with a later expiry, after a successful renewal. */
  public WorldClaim renewedUntil(long expiresAt) {
    return new WorldClaim(key, nodeId, nodeEpoch, fence, expiresAt);
  }
}
