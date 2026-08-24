package com.sexidium.core.world;

/**
 * The three things that can happen when a node asks to host a world.
 *
 * <p>A sealed answer rather than a boolean, because the caller has to do something different in each
 * case and the old {@code Decision(allowed, ownerNodeId, busy, takeover)} could not say which. In
 * particular {@code takeover} was produced and read by nobody at all.</p>
 */
public sealed interface ClaimOutcome {

  /** This node holds the world. The claim is the proof, and every write must carry it. */
  record Granted(WorldClaim claim) implements ClaimOutcome { }

  /** Somebody else has it. {@code busy} distinguishes "open there now" from "homed there". */
  record Elsewhere(String nodeId, boolean busy) implements ClaimOutcome { }

  /** Nobody can have it — no capable node is alive, or the row could not be written. */
  record Unavailable(String reason) implements ClaimOutcome { }
}
