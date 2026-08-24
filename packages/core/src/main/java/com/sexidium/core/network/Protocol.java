package com.sexidium.core.network;

/**
 * This build's wire/schema contract tag.
 *
 * <p>Two nodes on different builds share a database, a bus and a set of fenced tables. The tag is how
 * a node decides whether a peer is close enough to hand a running process to. <b>Nothing
 * player-facing is gated on it</b> — routing, heartbeats, transfers, liveness and the proxy's backend
 * registration keep working under any skew, because that is what keeps players connected. Only
 * ownership transfer is gated.</p>
 *
 * <h2>When to bump {@link #VERSION}</h2>
 * <ol>
 *   <li>the semantics of a shared column both builds write change — the fence triple
 *       {@code (node_id, node_epoch, fence)}, the placement {@code state} constants, the claim
 *       predicate;</li>
 *   <li>the payload grammar of an <em>existing</em> bus topic changes;</li>
 *   <li>the meaning of an <em>existing</em> {@code network_nodes} column changes;</li>
 *   <li>a new <em>required</em> participant behaviour lands in the drain protocol.</li>
 * </ol>
 *
 * <p>It is <b>not</b> bumped for: a new bus topic (an old node has no subscriber and ignores it — the
 * one forward-compat mechanism the bus actually has), a new challenge or mode (that is
 * {@link ContentManifest}'s job), a new column only the new build reads and writes, or any gameplay
 * change at all.</p>
 *
 * <p>How a developer finds out they have to bump it: {@code ProtocolContractTest} digests the wire
 * surface and fails the build when it moves. Bump the tag and update the digest in the same commit,
 * which puts a reviewer in front of the decision.</p>
 */
public final class Protocol {

  /** Monotonic. Bumped by hand, under the rules above. */
  public static final long VERSION = 1L;

  /**
   * The oldest peer tag this build will EXCHANGE OWNERSHIP with.
   *
   * <p>A floor, not a range, and deliberately so: during a rolling update the <em>old</em> nodes are
   * the receivers, and an old build cannot know about a tag that did not exist when it was compiled.
   * An upper bound would deadlock every rolling update at the first bump. The NEWER node is always
   * the one that enforces.</p>
   *
   * <p>Raising this is the breaking move and must be a <b>two-release</b> operation: ship
   * {@code VERSION = k} everywhere first, then raise {@code MIN_PEER} to {@code k} in a later
   * release. Never raise both in one commit — that strands every node still running the previous
   * build.</p>
   */
  public static final long MIN_PEER = 1L;

  /** A peer that predates this work publishes 0, which is below every floor we will ever declare. */
  public static final long UNKNOWN = 0L;

  private Protocol() {
  }
}
