package com.sexidium.core.game.experience;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Takes a point-in-time copy of an experience: its terrain, its per-player saves, and every counter
 * the challenges have written down.
 *
 * <p>This is the seam between the menu that asks for a backup and the world layer that can actually
 * read the folder. It exists as an interface for one reason: <b>the owner clicks in the lobby, and
 * the lobby never holds an experience world.</b> The click has to travel to the node that does,
 * exactly like {@link ExperienceCommandRouter.Op#DELETE} already does, and only that node can
 * implement this.</p>
 *
 * <h2>What a backup IS</h2>
 *
 * <p>A backup is a <em>real experience</em> — its own row, its own {@code world_key}, its own three
 * dimension folders on disk — that happens to remember which experience it was copied from. That is
 * deliberate: it means restoring is "enter the backup", not a second restore engine that has to put
 * bytes back underneath a running world. Nothing about a backup is a special case at read time.</p>
 *
 * <h2>A copy may be taken while people are playing</h2>
 *
 * <p>An experience world is a <em>dimension of the node's own level</em>, so nothing on disk stops a
 * second reader, and an in-place {@code save()} returns before the async chunk writes land — there is
 * no true flush short of unload-with-save. Copies are taken anyway, because being unable to back up
 * the world you are actually playing is the wrong answer to that. What makes it defensible is that a
 * bad copy is <b>caught rather than delivered</b>: the source is flushed where it stands, every folder
 * is copied inside a before/after inventory bracket sampled at nanosecond precision, a source that
 * moved is copied again a bounded number of times, and a source that keeps moving ends the verb as
 * {@link Outcome#FAILED} with nothing published. The risk this accepts is "your backup did not
 * happen"; the risk it does not accept is "your backup is subtly wrong".</p>
 *
 * <p>A world being OPEN is therefore no longer, by itself, a reason to refuse a copy — see {@code
 * worlds.experiences.allow-live-copy}, which an operator can set false to restore the old refusal.
 * {@link Outcome#BUSY} still belongs, unconditionally, to three things. Two cannot be verified after
 * the fact: a {@link #restore(UUID, String, Consumer)} (both worlds), and the removal of the old
 * folder at the end of a {@link #refresh(UUID, String, Consumer)}. The third is a collision between
 * the verbs themselves: one folder verb at a time per experience, so a {@code backup}, {@code
 * duplicate}, {@code refresh} or {@code restore} that names an id another one is already copying is
 * answered BUSY whatever {@code allow-live-copy} says — two verbs on one row are how a folder ends up
 * named by nothing.</p>
 *
 * <h2>Entering a copy changes it</h2>
 *
 * <p>Minecraft writes to any world it loads. Opening a backup to look at it rewrites its level data
 * and its spawn region within minutes, so a visited copy is no longer byte-identical to the instant it
 * captured — the terrain and the saves are still there, but "untouched since it was taken" stops being
 * true the moment somebody walks in. To inspect a copy without contaminating it, {@link
 * #duplicate(UUID, String, Consumer)} it and enter the duplicate.</p>
 */
public interface ExperienceBackup {

  /** How a backup ended, from the owner's point of view. */
  enum Outcome {
    /** The copy is on disk, verified, and registered as an experience of its own. */
    CREATED,
    /**
     * The backup is the live world now, and the world it replaced is a backup in its place.
     *
     * <p>No bytes moved: a restore swaps which folder each of two rows names. See
     * {@link #restore(UUID, String, Consumer)}.</p>
     */
    RESTORED,
    /** This backup was taken again from its source; its old folder is gone. */
    REFRESHED,
    /** The copy is on disk as a playable experience of its own, owing nothing to what it came from. */
    DUPLICATED,
    /** Not this player's experience (or there is no such experience). */
    NOT_OWNER,
    /**
     * There is nothing on the other end. The experience this backup was taken of has been deleted, so
     * there is no live world to restore over and nothing to take the copy again from.
     *
     * <p>Not {@link #NOT_OWNER}: the requester already owns the backup, so naming the gap leaks
     * nothing and is the only answer they can act on (enter the copy, or duplicate it).</p>
     */
    GONE,
    /**
     * A world that has to be left alone is open — someone is inside it, or it has not finished
     * unloading since the last player left. Nothing was changed. Leaving and retrying in a moment is
     * the whole fix.
     *
     * <p>Or a verb of this same family is already working on that experience — one folder verb at a
     * time per id, and the answer to the second one is the same word and the same instruction.</p>
     *
     * <p>Reachable from a restore (asked of BOTH worlds), from a refresh whose old copy is open, from
     * ANY of the four verbs colliding with one already in flight on the same id, and from a copy verb
     * whose source is open only where an operator has set {@code worlds.experiences.allow-live-copy}
     * false. A plain copy of a world people are inside is no longer refused for being open; it is
     * verified, and ends {@link #FAILED} if the source would not hold still.</p>
     */
    BUSY,
    /** This experience already has as many backups as the server allows. Delete one first. */
    LIMIT_REACHED,
    /**
     * The node holding the folder does not have room for another copy of it.
     *
     * <p>Its own answer rather than a {@link #FAILED}, because it is the one failure the owner can do
     * something about, and because a full disk that reads as "something broke" is a support ticket
     * instead of a deleted backup.</p>
     */
    NO_SPACE,
    /**
     * Written down and sent, but the node holding the world has not answered yet — it is restarting,
     * or busy. The request stands and that node runs it the moment it reads its own table.
     */
    QUEUED,
    /**
     * The copy was attempted and did not complete: an I/O error, or a source that kept changing
     * underneath the read for every one of the attempts allowed. Any half-written folder has already
     * been removed.
     *
     * <p>The second cause is the ordinary one now that a live world may be copied, and it is the
     * designed outcome rather than a fault: a world under heavy play may never hold still long enough
     * to be replicated, and the alternative to reporting this is publishing a copy that is quietly
     * not the world it claims to be. "Try again when it is quieter" is a true instruction.</p>
     */
    FAILED
  }

  /**
   * Copies {@code experienceId} into a new experience owned by the same player.
   *
   * <p>The callback may arrive on any thread and may arrive long after this returns. Callers that
   * touch a player from it must hop to that player's region first.</p>
   *
   * @param requester who asked; must be the owner or the call ends {@link Outcome#NOT_OWNER}
   * @param experienceId the experience to copy
   * @param onOutcome called exactly once with how it ended
   */
  void backup(UUID requester, String experienceId, Consumer<Outcome> onOutcome);

  /**
   * Makes {@code backupId} the live world of the experience it was taken from, and turns that world
   * into a backup in its place.
   *
   * <p><b>Nothing is copied.</b> Both folders already exist on disk, fully written, in exactly the
   * state they need to be in — so a restore is a transactional swap of which folder each of the two
   * registry rows names. The world being replaced becomes the safety copy for free, because it already
   * IS one, byte for byte; it is renamed {@code "<name> (before restore)"} and forced private.</p>
   *
   * <p>Refused {@link Outcome#BUSY} while EITHER world is open, and <b>never covered by {@code
   * worlds.experiences.allow-live-copy}</b> — that setting is about reading a folder, and this reads
   * none. The source's bytes are never touched, but the swap rewrites the {@code world_key} a running
   * match's {@code ExperiencePersistence} is bound to, and only {@code ExperienceWorldReset} — which
   * owns the match, the countdown and the teleports — can move a live match between worlds. A restore
   * clicked from the lobby owns none of those, and there is nothing about it that a verification pass
   * could catch afterwards.</p>
   *
   * <p>Restoring rolls the run's statistics back with the folder: {@code stats.run.*} lives in the
   * {@code state.yml} inside the world being restored. That is the point of a backup, not a side
   * effect. So does {@code dead}: a copy taken before a hardcore death restores a world that is not
   * lost, which is the sanctioned escape hatch this feature has always been.</p>
   *
   * @param requester who asked; must own BOTH the backup and its source
   * @param backupId the copy to make live
   * @param onOutcome called exactly once with how it ended
   */
  void restore(UUID requester, String backupId, Consumer<Outcome> onOutcome);

  /**
   * Takes {@code backupId} again from the experience it is a copy of, replacing its contents.
   *
   * <p>A full re-copy, every time — this is NOT an incremental backup and must never be sold as one.
   * What it buys is that the owner's newest copy stays their newest copy without spending another slot
   * of the per-experience cap and without a fourth folder on disk.</p>
   *
   * <p>The row keeps its id, so anything pointing at this backup keeps pointing at it. Only the folder
   * underneath changes: a fresh key is copied into, the row is re-pointed onto it, and only then is the
   * old folder removed — the same "never leave a folder nothing names" order the create path uses.</p>
   */
  void refresh(UUID requester, String backupId, Consumer<Outcome> onOutcome);

  /**
   * Copies {@code backupId} into a brand-new PLAYABLE experience that is nobody's backup.
   *
   * <p>The one verb that spends a slot of the per-player cap, because the result is a world in its own
   * right: {@code backup_of} is null, it appears in "My Experiences" as a world, and deleting the
   * original changes nothing about it. Whoever offers this must say so.</p>
   */
  void duplicate(UUID requester, String backupId, Consumer<Outcome> onOutcome);
}
