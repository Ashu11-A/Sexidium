package com.sexidium.core.world;

/**
 * The single authority on who may open an experience world, and the only thing that grants a fence.
 *
 * <p>Invariant I1 — at most one node holds a valid claim on a {@link WorldKey} at any instant — is
 * enforced by one conditional {@code UPDATE} inside {@link #claim}. Invariant I4 is enforced by the
 * contract on {@link #renew}: <b>false means this node has been evicted</b>, and the holder must
 * freeze writes immediately and have unloaded within one lease period.</p>
 *
 * <p>Extends {@link ExperienceLocator} deliberately: "where does this live" and "may I open it" are
 * two questions about one row, and the reason the second used to be the only one available is that
 * {@code PlacementDecider.check} — the closest thing to a locator — MUTATES.</p>
 */
public interface WorldLeaseAuthority extends ExperienceLocator {

  /** Ask to host a world. The only thing that mints a fence. */
  ClaimOutcome claim(WorldKey key, String ownerUuid);

  /**
   * Keep a claim alive.
   *
   * <p><b>FALSE means this node has been evicted.</b> Contract: freeze writes NOW, evacuate, and
   * unload within one lease period. Not "the renewal did not happen" — a SQL failure is reported as
   * true, because a database blip must not eject players from a healthy world.</p>
   */
  boolean renew(WorldClaim claim, int players);

  /** RESERVED → LOADED, once the world is actually open. */
  boolean confirmOpen(WorldClaim claim);

  /** LOADED → IDLE: a clean unload. The world stays claimable by anyone. */
  boolean release(WorldClaim claim);

  /**
   * RESERVED → FREE: the rollback that did not exist.
   *
   * <p>The gate stamped a row with a real epoch and a live lease BEFORE the world was created, and if
   * creation then failed nothing released it. The row stayed materialised-looking for a world that
   * did not exist, so every later entry was routed to a node with no folder — which then generated a
   * fresh one there.</p>
   */
  boolean unclaim(WorldClaim claim);

  /** Allocate the next generation of a run in the placement row, without touching the filesystem. */
  WorldKey allocateNextGeneration(String experienceId, WorldKey current);
}
