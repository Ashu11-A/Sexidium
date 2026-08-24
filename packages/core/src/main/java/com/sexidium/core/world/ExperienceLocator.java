package com.sexidium.core.world;

import java.util.List;
import java.util.Optional;

/**
 * The read-only answer to "where does this experience world live right now, and who may open it".
 *
 * <p>There was no such query. The closest thing, {@code PlacementDecider.check}, is a <em>mutating</em>
 * claim — so every caller that merely wanted to know where a world was either took out a claim it did
 * not want, or guessed from something that cannot answer the question: a local loaded-world lookup
 * (which is why the owner-delete path removed a shared folder out from under a live world on another
 * node), or the presence of a folder on disk (which, under shared storage, is true on every node
 * including the lobby).</p>
 *
 * <p>Every implementation reads {@code world_placements} and nothing else. Not Bukkit's loaded-world
 * set, not the disk, not a local match map — those are the three sources of truth that were allowed to
 * disagree.</p>
 */
public interface ExperienceLocator {

  /** A world's recorded home. A projection of the placement row, deliberately not the row itself. */
  record Placement(
      WorldKey key,
      String experienceId,
      String nodeId,
      long nodeEpoch,
      long fence,
      String state,
      int players,
      long leaseExpiresAt) {

    /** Whether the recorded lease is still valid at {@code now}. */
    public boolean leaseHeld(long now) {
      return leaseExpiresAt > now;
    }
  }

  /**
   * Where this world lives, as an answer that can also be "I could not tell".
   *
   * <p>{@link #locate} cannot say that: a read that failed and a world nothing has ever recorded both
   * come back empty. For most callers the two really are the same — they fall back to asking again.
   * For a DELETE they are opposites. "Nothing records this world" licenses the caller to drop the rows
   * that name it, because a world only ever gets a placement row by being opened or created, so there
   * is no folder anywhere to orphan. A lock-wait timeout on the shared MariaDB would then cash in that
   * licence over a world that is intact on a worker's disk — the owner told "deleted", the folder left
   * with nothing naming it.</p>
   */
  record Home(boolean known, Placement placement) {

    /** The world lives here. */
    public static Home at(Placement placement) {
      return new Home(true, placement);
    }

    /** Read successfully, and nothing has ever recorded this world. */
    public static Home none() {
      return new Home(true, null);
    }

    /** The table could not be read. Says nothing at all about whether the world exists. */
    public static Home unknown() {
      return new Home(false, null);
    }

    /** Whether a node is positively recorded as holding this world. */
    public boolean recorded() {
      return known && placement != null;
    }

    /** The node holding it, or null when there is none or the answer is unknown. */
    public String nodeId() {
      return placement == null ? null : placement.nodeId();
    }
  }

  /** Where this world lives, or empty when nothing has ever recorded it. NEVER mutates. */
  Optional<Placement> locate(WorldKey key);

  /**
   * {@link #locate}, keeping a failed read distinct from a missing row.
   *
   * <p>Defaulted to the lossy answer so an implementation that genuinely cannot tell the two apart
   * (an in-memory one, a test fake) needs no change. The database-backed one overrides it, because it
   * is the only one where "could not read" is a thing that happens.</p>
   */
  default Home home(WorldKey key) {
    return locate(key).map(Home::at).orElseGet(Home::none);
  }

  /** The current world of an experience, following whatever generation it has reached. */
  Optional<WorldKey> keyOf(String experienceId);

  /** Every placement recorded against a node. For the admin surface and the janitor. */
  List<Placement> heldBy(String nodeId);
}
