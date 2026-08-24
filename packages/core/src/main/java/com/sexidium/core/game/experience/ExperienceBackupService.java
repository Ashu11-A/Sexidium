package com.sexidium.core.game.experience;

import com.sexidium.core.game.experience.ExperienceManager.Experience;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.world.WorldKey;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The engine behind {@link ExperienceBackup}: the folder copy, the sidecar re-point and the registry
 * row that together turn one experience into a second, independent one.
 *
 * <h2>How restore avoids being a second copy engine</h2>
 *
 * <p>A backup is a real experience. It gets an id, a {@code world_key} of its own, its own three
 * dimension folders, and a {@code backup_of} pointing at where it came from — and that is the entire
 * mechanism. The obvious restore, one that writes bytes back underneath the original, would have to
 * stop a live world, replace files another node may hold open, and be correct when it is interrupted
 * half way. This one never needs any of that: <b>both folders already exist, fully written, in exactly
 * the state they need to be in,</b> so the restore is a transactional swap of which folder each of the
 * two registry rows names, and the world being replaced becomes the safety copy for free.</p>
 *
 * <h2>Why it copies bytes and reconstructs nothing</h2>
 *
 * <p>Everything that makes a world THAT world already lives in its folder: the seed and the generator
 * registry in {@code data/minecraft/world_gen_settings.dat}; hardcore, difficulty, spawn and the world
 * clock in {@code data/paper/level_overrides.dat}; the run's counters and every per-player save under
 * {@code sexidium/}. So the copy carries them by carrying the bytes. The single exception is a VOID
 * world's generator, which is handed to the world layer programmatically and recorded nowhere on disk —
 * which is why the registry row copies the challenge list and world type verbatim, so a chunk generated
 * after the clone is first opened comes out of the same generator the original's did.</p>
 *
 * <h2>The order, and why every step is where it is</h2>
 *
 * <ol>
 *   <li>Own it, or nothing happens.</li>
 *   <li>Under the per-experience cap, or nothing happens — and never by deleting an old copy. A backup
 *       is the thing an owner keeps because they might need it; choosing which one to lose is theirs.</li>
 *   <li>Flush the source where it stands ({@link WorldLeaseService#saveExperienceNow}). The world does
 *       NOT have to be closed — see {@link #allowLiveCopy()} for the reversal and what carries the
 *       safety instead. The one thing that still must be closed is a folder about to be DELETED.</li>
 *   <li>A free destination key, checked against disk and memory including linked dimensions.</li>
 *   <li>Copy off-thread, verified, into staging beside each destination; publish by rename. A source
 *       that moves under the read is retried a bounded number of times by the world layer and then
 *       refused — never published.</li>
 *   <li>Only then the row, so {@code byWorld} resolves the copy before anything can open it.</li>
 * </ol>
 *
 * <p>If the row cannot be written after the folders landed, the folders are removed again. An orphan
 * folder that no row names is invisible to the owner and immortal on disk; a row that names a folder
 * which does not exist is worse still, because the entry path would then generate a world into it.</p>
 *
 * <h2>Where "closed" is still required, and where it is not</h2>
 *
 * <p>Copying no longer requires a closed source; deleting and swapping still do. The line is whether
 * the operation can be CHECKED after the fact. A copy can: the bracket inside {@code
 * WorldClone.copyWorldFolderChecked} fails the copy when the source moved, so the worst a live copy
 * can produce is a refusal. A delete cannot — the folder is gone — and a restore cannot either, since
 * it re-points a {@code world_key} that a live match's persistence is already holding. So {@code
 * restore} refuses a busy world on BOTH sides, and {@code refresh} refuses to remove a copy anybody
 * has open, while all three copy verbs read straight through.</p>
 *
 * <h2>"Closed" has to mean closed EVERYWHERE</h2>
 *
 * <p>Every verb here routes on the SOURCE experience's key, so it runs on the node holding the source
 * and the node-local questions — is it loaded, is it closing, is a match on it — are the truth about
 * that world. They are not the truth about the other one. {@code restore} and {@code refresh} each
 * touch a second world, the copy, which nothing addressed and which some other worker may have open;
 * and on the live deployment the folder check cannot tell, because the experiences tree is a symlink
 * into shared storage and every folder is present on every node. That is what {@link PlacementGate}
 * is for, and why the pre-delete re-check in {@code refresh} asks it too: a delete of a world open
 * elsewhere finds nothing loaded here to evacuate and removes the region files underneath it.</p>
 */
public final class ExperienceBackupService implements ExperienceBackup {

  /** How many copies of one experience the server keeps unless configured otherwise. */
  static final int DEFAULT_MAX_BACKUPS = 3;

  /**
   * "Is this world in somebody ELSE's hands?" — the half of busy that no node can see from inside.
   *
   * <p>Its own type rather than a second {@link Predicate}, because a second predicate of the same
   * shape beside {@code matchRunning} is two arguments that can be swapped at a call site with no
   * compiler anywhere to notice, and swapping these two is silent data loss.</p>
   *
   * <p>Wired from {@link ExperienceCommandRouter#heldElsewhere}, which reads {@code world_placements}
   * and nothing else — see it for what each of the three answers folds to and why.</p>
   */
  @FunctionalInterface
  public interface PlacementGate {

    /**
     * True when another node holds or is still closing this world, <b>and true when that cannot be
     * determined</b>. It is only ever asked in order to refuse, so "I do not know" has to read the
     * same as "yes": a refusal is a click the owner repeats, and a folder deleted under a player is
     * not repeatable.
     */
    boolean heldElsewhere(WorldKey key);
  }

  /**
   * Records THIS node as the home of a world whose folders it has just written.
   *
   * <p>The hole this closes. A copy builds its destination folders through
   * {@link WorldLeaseService#copyExperienceWorld}, which consults the placement gate about the
   * SOURCE and never about the destination, and then writes only the {@code experiences} row. So
   * every backup and every duplicate was born with folders and no {@code world_placements} row — and
   * on shared storage the reconciler deliberately skips the adopt that would have made one, because
   * "the folder is on this disk" is true on every node at once and therefore says nothing about who
   * should host it.</p>
   *
   * <p>A world with no row has no home, and every op that touches the FOLDER is routed at the home.
   * From the lobby — the one place an owner clicks Delete — that came back unroutable and the delete
   * was refused. The refusal was right (dropping the rows there would orphan several hundred
   * megabytes that no collector in this codebase can find, because every one of them needs a row to
   * start from), but it left the copy undeletable until somebody entered it once. Recording the home
   * the moment the folders land is what makes a copy an ordinary world from birth.</p>
   *
   * <p>Asked on the node that WROTE the folders, which by construction is one that may host
   * experience worlds: every copy verb {@code touchesTheFolder()} and is routed to the holder of the
   * source before any of this runs.</p>
   */
  @FunctionalInterface
  public interface PlacementRecorder {

    /** True when a home is recorded for {@code key}. False is never fatal — see {@code recordHome}. */
    boolean placeHere(WorldKey key);

    /** Standalone: this node holds every world it has, so there is no home to record. */
    PlacementRecorder NONE = key -> true;
  }

  private final ServerAdapter server;
  private final ExperienceManager experiences;
  private final ExperienceStateStore store;
  private final Predicate<WorldKey> matchRunning;
  private final PlacementGate placements;
  private final PlacementRecorder homes;

  /**
   * The experience ids a folder verb is working on RIGHT NOW, on this node.
   *
   * <p>Every gate above is a check-then-act separated by a multi-hundred-megabyte folder copy, and
   * nothing marked a backup as "being worked on" for the length of it. Two consequences, both silent:
   *
   * <ul>
   *   <li>two REFRESHES of one backup both passed every gate, allocated two destinations and both
   *       completed. The second re-point won, so the first one's folder ended up named by no row at
   *       all — hundreds of megabytes that nothing lists, nothing collects and no owner can see
   *       ({@code cleanupStaleBackupStaging} only sweeps {@code .incoming-*} staging);</li>
   *   <li>a REFRESH and a RESTORE of the same backup interleave into data loss, which is why the key
   *       covers the SOURCE id as well as the backup's: those two verbs touch the same pair of rows
   *       from opposite ends.</li>
   * </ul>
   *
   * <p>Taken before the copy starts and released on every exit including the failures, the
   * continuation, and a continuation that THREW instead of answering — see {@link #answering}, which
   * is what makes that last one true and without which a database that blinked during a copy locked
   * every verb on these ids for the rest of the process's life. A second call for an id already here
   * is answered {@link Outcome#BUSY}, which is the same word the owner already understands from a
   * world somebody has open, and the same instruction: try again in a moment.</p>
   */
  private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

  /**
   * @param matchRunning whether a live match is running on a world — the other half of "is it busy",
   *     since a match can hold an experience whose world has already been resolved
   * @param placements whether ANOTHER node holds the world. Every other question this engine asks is
   *     node-local — loaded worlds, closing worlds, running matches, and a folder that on the live
   *     deployment is a symlink into shared storage and therefore present on every node at once — so
   *     without this the copy side of a restore or a refresh is guarded by nothing at all. <b>Null
   *     refuses</b>, like every other unanswerable question here: an engine wired without it is loudly
   *     unable to restore, which an operator fixes, rather than quietly able to delete a world another
   *     node has open, which nobody can.
   */
  public ExperienceBackupService(ServerAdapter server, ExperienceManager experiences,
      ExperienceStateStore store, Predicate<WorldKey> matchRunning, PlacementGate placements) {
    this(server, experiences, store, matchRunning, placements, PlacementRecorder.NONE);
  }

  /**
   * The networked shape, with the destination's home recorded as well as the source's guarded.
   *
   * @param homes what writes the {@code world_placements} row for a world this node has just copied
   *     into place. <b>Null is the standalone answer</b> and not a refusal, which is the opposite of
   *     {@code placements} above and deliberately so: the two are asked at opposite ends of the same
   *     verb. {@code placements} is asked in order to REFUSE, so silence has to read as "yes,
   *     somebody has it"; this one is asked after the bytes have landed and a finished copy is never
   *     thrown away over a bookkeeping row.
   */
  public ExperienceBackupService(ServerAdapter server, ExperienceManager experiences,
      ExperienceStateStore store, Predicate<WorldKey> matchRunning, PlacementGate placements,
      PlacementRecorder homes) {
    this.server = server;
    this.experiences = experiences;
    this.store = store;
    this.matchRunning = matchRunning == null ? key -> false : matchRunning;
    this.placements = placements == null ? key -> true : placements;
    this.homes = homes == null ? PlacementRecorder.NONE : homes;
  }

  /**
   * Record this node as the home of a world whose folders were just written here.
   *
   * <p>Never fatal, and never a reason to undo the copy. The folders are complete and a registry row
   * names them, so the owner has exactly the world they asked for; what a missing placement costs is
   * that the FIRST folder op routed from the lobby cannot find a home — and the router's own fallback
   * covers that (see {@code ExperienceCommandRouter.HomeFinder}), as does entering the world once.
   * Failing the verb here would discard a finished multi-hundred-megabyte copy over one row.</p>
   */
  private void recordHome(WorldKey key, String what) {
    if (key == null || homes.placeHere(key)) {
      return;
    }
    server.logger().warning("Wrote the world folders for " + what + " ('" + key.key() + "') but"
        + " could not record which node holds it. Nothing is lost — the copy is complete and listed —"
        + " but a folder op on it has to fall back to choosing a host until somebody enters it once.");
  }

  @Override
  public void backup(UUID requester, String experienceId, Consumer<Outcome> onOutcome) {
    Consumer<Outcome> tell = onOutcome == null ? outcome -> { } : onOutcome;
    Experience source = owned(requester, experienceId);
    if (source == null) {
      tell.accept(Outcome.NOT_OWNER);
      return;
    }
    // These two guards used to live ONLY in the menu that drew the tile, which made them a property of
    // one screen rather than of the operation. Every new entry point -- a console command, another
    // menu, a peer's routed request -- got neither for free. They belong here, where nothing can be
    // added around them:
    //   * a copy of a COPY is indistinguishable from its sibling in any list, and the source it names
    //     is a world its owner may delete tomorrow, which would then promote both;
    //   * a LOST hardcore world has nothing left to carry forward -- the run is over, and the only
    //     things its own screen offers are spectating, renaming and deleting.
    if (source.isBackup() || source.dead()) {
      tell.accept(Outcome.LIMIT_REACHED);
      return;
    }
    if (experiences.countBackupsOf(source.id()) >= maxBackupsPerExperience()) {
      tell.accept(Outcome.LIMIT_REACHED);
      return;
    }
    if (!claim(source.id())) {
      tell.accept(Outcome.BUSY);
      return;
    }
    copyInto(source, ExperienceManager.backupDisplayName(source, null, ""), true,
        maxBackupsPerExperience(), releasing(tell, source.id()));
  }

  @Override
  public void duplicate(UUID requester, String backupId, Consumer<Outcome> onOutcome) {
    Consumer<Outcome> tell = onOutcome == null ? outcome -> { } : onOutcome;
    Experience source = owned(requester, backupId);
    if (source == null) {
      tell.accept(Outcome.NOT_OWNER);
      return;
    }
    // The one verb that SPENDS a world slot, because the result is a world: it is nobody's backup, it
    // outlives whatever it was copied from, and the owner has to be told that before they click it.
    if (experiences.countByOwner(source.owner()) >= maxPerPlayer()) {
      tell.accept(Outcome.LIMIT_REACHED);
      return;
    }
    if (!claim(source.id())) {
      tell.accept(Outcome.BUSY);
      return;
    }
    String base = source.displayName() == null || source.displayName().isBlank()
        ? "Experience" : source.displayName();
    copyInto(source, base + " (copy)", false, maxPerPlayer(), releasing(tell, source.id()));
  }

  /**
   * The shared copy path: pick a free key, copy the folders off-thread, then write the row.
   *
   * <p>Row LAST, always. A row that names a folder which does not exist is worse than an unnamed
   * folder, because the entry path would generate fresh terrain into it and call that the backup.</p>
   *
   * <p>The cap is handed in rather than re-read at the insert, because the two verbs count different
   * things — copies OF this experience for a backup, worlds owned BY this player for a duplicate — and
   * both are re-counted inside the insert's own transaction. The pre-check at the click and the insert
   * are separated by a multi-hundred-megabyte folder copy, which is more than enough window for two
   * clicks to both pass a check-then-act.</p>
   *
   * @param asBackup true to record {@code backup_of}; false to record a playable experience of its own
   * @param cap the limit the insert re-counts against, in whichever units this verb spends
   */
  private void copyInto(Experience source, String displayName, boolean asBackup, int cap,
      Consumer<Outcome> tell) {
    WorldLeaseService worlds = server.worlds();
    WorldKey sourceKey = source.key();
    if (worlds == null) {
      tell.accept(Outcome.FAILED);
      return;
    }
    if (!allowLiveCopy() && (worlds.experienceWorldLoaded(sourceKey) || matchRunning.test(sourceKey))) {
      // Only when an operator has asked for the old strictness back. By default a copy is allowed to
      // read a world people are inside; see allowLiveCopy() for what carries the safety instead.
      tell.accept(Outcome.BUSY);
      return;
    }
    if (!worlds.roomToCopyExperience(sourceKey)) {
      // Its own answer: a full disk used to surface as an opaque "the copy could not be finished",
      // which is the same sentence a torn read gets and sends the owner looking for a fault instead of
      // deleting something.
      //
      // It costs a stat walk of the source tree on this thread, which is the price of the honest
      // answer and is paid once per explicit click -- never on a tick, never on an entry. The copy
      // that follows walks the same tree anyway, so this is at worst the same work done twice.
      tell.accept(Outcome.NO_SPACE);
      return;
    }
    String id = ExperienceManager.newExperienceId();
    String name = displayName == null || displayName.isBlank() ? "Experience " + id : displayName;
    // The KEY is built from the source's own name, not the decorated display name: the folder is an
    // identity, and " (backup)" in a path segment is noise the owner never sees and the operator has
    // to read past. Generation 0 of a FRESH base, never nextGeneration(): a copy is a different run,
    // and a shared base would make the lineage checks treat it as a later generation of the original.
    String keyBase = source.displayName() == null || source.displayName().isBlank()
        ? "Experience " + id : source.displayName();
    WorldKey destination = WorldKey.of(keyBase, id);
    if (!destination.origin().equals(destination) || destination.sameRun(sourceKey)) {
      // Unreachable by construction (the id is new), and cheap enough to assert rather than assume.
      server.logger().severe("Refusing to copy experience " + source.id()
          + ": the destination key '" + destination.key() + "' is not a fresh run of its own.");
      tell.accept(Outcome.FAILED);
      return;
    }
    if (!worlds.experienceKeyFree(destination)) {
      server.logger().severe("Refusing to copy experience " + source.id() + ": '"
          + destination.key() + "' is already taken on this node. Nothing was copied.");
      tell.accept(Outcome.FAILED);
      return;
    }
    // The truth, from the file inside the folder about to be copied -- never the registry column,
    // which is a crash net a day behind on any world anybody actually plays.
    // Flush first, THEN read the counters, THEN copy — in that order, so both the sidecar this reads
    // and the bytes the copy walks come from the same, freshly written folder rather than from
    // whatever survived the last autosave.
    worlds.saveExperienceNow(sourceKey);
    String carriedState = encodedStateOf(sourceKey);
    long now = System.currentTimeMillis();
    worlds.copyExperienceWorld(sourceKey, destination,
        staged -> rehome(staged, sourceKey, destination),
        answering(tell, "the copy of experience " + source.id(), copied -> {
          if (!Boolean.TRUE.equals(copied)) {
            // Includes the source that never settled. The world layer already retried the verified
            // copy its bounded number of times; a world under active play can legitimately keep
            // moving, and reporting a failure then is the correct end of that story. What must never
            // happen is the other one: a copy published anyway and called an exact replica.
            tell.accept(Outcome.FAILED);
            return;
          }
          Experience stored = asBackup
              ? experiences.createBackup(source, name, id, destination, now, carriedState, cap,
                  maxPerPlayer())
              : experiences.createFrom(source, name, id, destination, now, carriedState, cap);
          if (stored == null) {
            // The bytes are on disk and nothing names them. Take them back off: an experience folder
            // with no registry row is invisible to its owner and never collected by anything.
            server.logger().severe("Copied experience " + source.id() + " to '" + destination.key()
                + "' but could not record it. Removing the copy so no folder is left unnamed.");
            worlds.deletePersistent(destination);
            // The one thing that can refuse the row after a clean copy is the cap re-count inside the
            // insert, so say so rather than reporting a fault -- on BOTH verbs. A duplicate refused by
            // the per-player cap used to answer FAILED, which tells the owner something broke when the
            // truth is that they are at their limit and can act on it by deleting a world.
            // A backup is refused by EITHER cap now: its own, and -- when the source was deleted
            // while the copy ran, which turns the row into a world of its own -- the per-player one
            // that world would then spend.
            boolean cappedOut = asBackup
                ? experiences.countBackupsOf(source.id()) >= maxBackupsPerExperience()
                    || experiences.countByOwner(source.owner()) >= maxPerPlayer()
                : experiences.countByOwner(source.owner()) >= maxPerPlayer();
            tell.accept(cappedOut ? Outcome.LIMIT_REACHED : Outcome.FAILED);
            return;
          }
          // AFTER the registry row, never before it. Between the folders landing and the row being
          // accepted there is one failure path that removes the folders again, and a placement row
          // written ahead of it would be left naming a world that no longer exists -- which is the
          // one shape of orphan this whole file is arranged to avoid. Written here the worst case is
          // the state every copy used to be born in, which the router can now recover from.
          recordHome(destination, "the copy of experience " + source.id());
          server.logger().info("Copied experience " + source.id() + " ('" + source.worldKey()
              + "') to " + stored.id() + " ('" + stored.worldKey() + "')"
              + (stored.isBackup() ? " as a backup." : " as an experience of its own."));
          // What was STORED, never what was asked for. A source deleted while the copy ran comes back
          // with no `backup_of` -- createBackup records a world of its own rather than a copy of a row
          // that is gone -- and CREATED would then tell the owner they have a backup while every list
          // they own draws it as a world. DUPLICATED is the one word already in this enum for exactly
          // that thing: a playable experience owing nothing to what it came from.
          tell.accept(stored.isBackup() ? Outcome.CREATED : Outcome.DUPLICATED);
        }));
  }

  @Override
  public void restore(UUID requester, String backupId, Consumer<Outcome> onOutcome) {
    // `refuse` answers everything decided before the claim is taken; `tell` answers everything after
    // it, and releases the claim on its way past. Two names because only the second may be captured.
    Consumer<Outcome> refuse = onOutcome == null ? outcome -> { } : onOutcome;
    Experience backup = owned(requester, backupId);
    if (backup == null) {
      refuse.accept(Outcome.NOT_OWNER);
      return;
    }
    if (!backup.isBackup()) {
      // Not a copy of anything, so there is nothing on the other end to restore over. GONE and not
      // NOT_OWNER: the requester owns this row, so naming the gap leaks nothing.
      refuse.accept(Outcome.GONE);
      return;
    }
    Experience source = experiences.get(backup.backupOf());
    if (source == null) {
      refuse.accept(Outcome.GONE);
      return;
    }
    if (!requester.equals(source.owner())) {
      refuse.accept(Outcome.NOT_OWNER);
      return;
    }
    WorldLeaseService worlds = server.worlds();
    if (worlds == null) {
      refuse.accept(Outcome.FAILED);
      return;
    }
    // BOTH ids, before anything is read: a refresh of this same backup may be minutes into a copy it
    // is about to re-point, and the swap below would move the rows underneath it.
    if (!claim(backup.id(), source.id())) {
      refuse.accept(Outcome.BUSY);
      return;
    }
    Consumer<Outcome> tell = releasing(refuse, backup.id(), source.id());
    WorldKey liveKey = source.key();
    WorldKey copyKey = backup.key();
    // BOTH worlds, and NOT covered by worlds.experiences.allow-live-copy -- that setting is about
    // READING a folder, and a restore does not read one. The swap rewrites the world_key a running
    // match's ExperiencePersistence is bound to, and only ExperienceWorldReset can move a live match
    // between worlds, because it owns the match, the countdown and the teleports. A restore clicked
    // from the lobby owns none of those, so no amount of verification could make this safe the way it
    // makes a copy safe: there is nothing here to verify, only a live match to break. Refusing is the
    // honest answer, and it is the same one the owner already understands from taking the copy.
    if (worlds.experienceWorldLoaded(liveKey) || matchRunning.test(liveKey)
        || worlds.experienceWorldLoaded(copyKey) || matchRunning.test(copyKey)) {
      tell.accept(Outcome.BUSY);
      return;
    }
    // And the same question asked of the FLEET, for the copy. This verb runs on the node holding the
    // SOURCE -- that is what it was routed on, and what the router re-checks before running it -- so
    // every local answer above is authoritative about liveKey and says nothing whatsoever about
    // copyKey. A backup a player opened, looked at and walked out of is the most ordinary flow this
    // feature has, and the worker that opened it is still flushing hundreds of megabytes and will then
    // write state.yml, player snapshots and a rememberPlayerWorld row into a folder this swap has
    // already handed to another experience.
    if (placements.heldElsewhere(copyKey)) {
      server.logger().info("Refusing to restore " + backup.id() + " over " + source.id()
          + ": another node has '" + copyKey.key() + "' open or is still closing it, or its placement"
          + " could not be read. Nothing was changed.");
      tell.accept(Outcome.BUSY);
      return;
    }
    if (!worlds.experienceFolderPresent(liveKey) || !worlds.experienceFolderPresent(copyKey)) {
      server.logger().severe("Refusing to restore " + backup.id() + " over " + source.id()
          + ": this node cannot see both world folders, and swapping the rows from here would leave a"
          + " live experience naming a folder that is not there.");
      tell.accept(Outcome.FAILED);
      return;
    }
    // Bring the crash-net column up to date on BOTH rows from the files, so that whichever row ends up
    // wearing which world, its column agrees with the state.yml inside it. Without this the swap would
    // carry a day-old set of counters across with each folder.
    long now = System.currentTimeMillis();
    refreshStoredState(source.id(), liveKey, now);
    refreshStoredState(backup.id(), copyKey, now);
    String safetyName = safetyDisplayName(source);
    if (!experiences.swapWithBackup(source.id(), backup.id(), safetyName, now)) {
      tell.accept(Outcome.FAILED);
      return;
    }
    server.logger().info("Restored experience " + source.id() + " from its backup " + backup.id()
        + ": it now runs '" + copyKey.key() + "', and '" + liveKey.key()
        + "' is kept as '" + safetyName + "'. No bytes were moved.");
    tell.accept(Outcome.RESTORED);
  }

  /**
   * What the world being replaced is called afterwards.
   *
   * <p>It is a backup taken right now — of the state the owner is walking away from — and naming it so
   * is the only thing that makes a restore reversible without explaining anything.</p>
   */
  static String safetyDisplayName(Experience source) {
    String base = source.displayName() == null || source.displayName().isBlank()
        ? "Experience " + source.id() : source.displayName();
    return base + " (before restore)";
  }

  @Override
  public void refresh(UUID requester, String backupId, Consumer<Outcome> onOutcome) {
    // `refuse` answers everything decided before the claim is taken; `tell` answers everything after
    // it, and releases the claim on its way past. Two names because only the second may be captured.
    Consumer<Outcome> refuse = onOutcome == null ? outcome -> { } : onOutcome;
    Experience backup = owned(requester, backupId);
    if (backup == null) {
      refuse.accept(Outcome.NOT_OWNER);
      return;
    }
    if (!backup.isBackup()) {
      refuse.accept(Outcome.GONE);
      return;
    }
    Experience source = experiences.get(backup.backupOf());
    if (source == null || !requester.equals(source.owner())) {
      refuse.accept(source == null ? Outcome.GONE : Outcome.NOT_OWNER);
      return;
    }
    WorldLeaseService worlds = server.worlds();
    if (worlds == null) {
      refuse.accept(Outcome.FAILED);
      return;
    }
    // Both ids, for the whole copy. Two refreshes of one backup used to allocate two destinations and
    // both complete, leaving the loser's folder named by nothing; and a restore that commits mid-copy
    // moves these two rows onto each other's folders while this one is still holding the old values.
    if (!claim(backup.id(), source.id())) {
      refuse.accept(Outcome.BUSY);
      return;
    }
    Consumer<Outcome> tell = releasing(refuse, backup.id(), source.id());
    WorldKey sourceKey = source.key();
    WorldKey oldKey = backup.key();
    // The two halves of a refresh are not the same operation and no longer carry the same gate.
    //
    //   * The SOURCE is only ever READ. A copy of a world people are inside is verified rather than
    //     refused (see allowLiveCopy), so this half is open unless an operator closed it.
    //   * The OLD COPY is DELETED at the end. That is data destruction with no bracket behind it and
    //     no undo, so it stays strict here AND is asked again immediately before the delete, because
    //     a proof taken before a multi-minute copy is a proof about a different moment.
    if (!allowLiveCopy() && (worlds.experienceWorldLoaded(sourceKey) || matchRunning.test(sourceKey))) {
      tell.accept(Outcome.BUSY);
      return;
    }
    if (worlds.experienceWorldLoaded(oldKey) || matchRunning.test(oldKey)) {
      tell.accept(Outcome.BUSY);
      return;
    }
    // Same reason as the restore: routed on the SOURCE, so nothing local here has ever been asked
    // about the copy. This one ends in a deletePersistent, and a delete of a world open on another
    // node skips evacuate and unload entirely -- there is nothing loaded HERE to evacuate -- and goes
    // straight to removing the region files under the player standing in them.
    if (placements.heldElsewhere(oldKey)) {
      server.logger().info("Refusing to refresh backup " + backup.id() + ": another node has '"
          + oldKey.key() + "' open or is still closing it, or its placement could not be read."
          + " Nothing was copied.");
      tell.accept(Outcome.BUSY);
      return;
    }
    if (!worlds.roomToCopyExperience(sourceKey)) {
      // Deliberately measured against a copy landing BESIDE the old one. The old folder is not deleted
      // until the row names the new one, so for the length of the copy both exist -- and pretending
      // otherwise is how a refresh fills a disk that a plain backup would have refused.
      tell.accept(Outcome.NO_SPACE);
      return;
    }
    String id = ExperienceManager.newExperienceId();
    String keyBase = source.displayName() == null || source.displayName().isBlank()
        ? "Experience " + id : source.displayName();
    WorldKey destination = WorldKey.of(keyBase, id);
    if (!worlds.experienceKeyFree(destination) || destination.sameRun(sourceKey)) {
      server.logger().severe("Refusing to refresh backup " + backup.id() + ": '" + destination.key()
          + "' is not a free, independent key on this node. Nothing was copied.");
      tell.accept(Outcome.FAILED);
      return;
    }
    worlds.saveExperienceNow(sourceKey);
    String carriedState = encodedStateOf(sourceKey);
    long now = System.currentTimeMillis();
    worlds.copyExperienceWorld(sourceKey, destination,
        staged -> rehome(staged, sourceKey, destination),
        answering(tell, "the refresh of backup " + backup.id(), copied -> {
          if (!Boolean.TRUE.equals(copied)) {
            // A source that never settled ends here too, after the world layer's bounded retries, and
            // the old copy is untouched — which is the right thing to still be holding.
            tell.accept(Outcome.FAILED);
            return;
          }
          // The re-point names the folder this backup is EXPECTED to still be on. Everything in this
          // continuation was captured before a copy that ran for seconds to minutes, and in that
          // window a RESTORE of this same backup can commit and move both rows onto each other's
          // folders. An unconditional re-point would then hand this row the new copy while the folder
          // it had just taken over -- the one the SOURCE was using -- ends up named by nobody, and the
          // delete at the end of this method would go on to remove the world the source runs NOW.
          // Zero rows updated is that case, and it ends the verb having changed nothing.
          if (!experiences.repointBackup(backup.id(), oldKey, destination, carriedState, now)) {
            // Same rule as the create path: never leave a folder nothing names. The row still names
            // whatever it named, which is untouched, so the backup the owner had is the backup the
            // owner still has.
            server.logger().severe("Refreshed the copy of experience " + source.id() + " into '"
                + destination.key() + "' but could not re-point backup " + backup.id()
                + " (it no longer names '" + oldKey.key() + "'). Removing the new copy; nothing else"
                + " was touched.");
            worlds.deletePersistent(destination);
            tell.accept(Outcome.FAILED);
            return;
          }
          // Same rule as the create path, and for the same reason: the row that names this folder has
          // been accepted, so from here on it is a world of its own and needs a home like any other.
          // A refresh is where it matters most -- it is the one verb an owner repeats, and every
          // repetition used to mint another unplaced folder.
          recordHome(destination, "the refresh of backup " + backup.id());
          // ASK AGAIN, and ask the FLEET as well as this node. The gate at the top of this method
          // proved the old folder was closed, and then a copy of the whole world ran for seconds to
          // minutes -- during which the backup row still named oldKey, so the owner could legitimately
          // walk into it, and the node that opens it for them is whichever one the placement gate
          // hands it to, which is not this one. The two local questions are blind to that: this node
          // has nothing loaded and no match, so both answer "closed" while a player stands in the
          // world. Deleting a world somebody is standing in is strictly worse than leaving a folder an
          // operator can see in the log, so the stale proof is not reused and neither is the local one.
          if (worlds.experienceWorldLoaded(oldKey) || matchRunning.test(oldKey)
              || placements.heldElsewhere(oldKey)) {
            server.logger().severe("Backup " + backup.id() + " now names '" + destination.key()
                + "', but its previous folder '" + oldKey.key() + "' was re-opened while the copy ran"
                + " and was left in place. It is an orphan on disk that nothing references; remove it"
                + " by hand.");
            server.logger().info("Refreshed backup " + backup.id() + " of experience " + source.id()
                + ": it now names '" + destination.key() + "'.");
            tell.accept(Outcome.REFRESHED);
            return;
          }
          // And ASK THE REGISTRY, which is the question the three above cannot pose: "is this folder
          // still nobody's?" A restore committed during the copy leaves the SOURCE row naming oldKey
          // -- the world it now runs -- and every check above answers about who has it OPEN, which is
          // "nobody" for a world whose match has not started yet. Deleting it there is deleting a live
          // experience's terrain. Same shape as the re-check above and the same verdict: the row is
          // already correct, so this is REFRESHED with an orphan an operator can see in the log, never
          // a failure reported to the owner.
          //
          // ASKED, not merely queried: `byWorld` funnels its SQLException into a warning and an empty
          // list, so "no row names this folder" and "the registry could not be reached" arrive as the
          // same null -- and the delete two lines down is the one place that difference destroys a
          // living experience's terrain. A question with no answer refuses, exactly like
          // PlacementGate.heldElsewhere and ExperienceLocator.Home already do.
          ExperienceManager.Naming naming = experiences.namedBy(oldKey);
          if (naming.named() || !naming.known()) {
            Experience stillNamed = naming.experience();
            server.logger().severe("Backup " + backup.id() + " now names '" + destination.key()
                + "', but its previous folder '" + oldKey.key() + "' "
                + (stillNamed == null
                    ? "could not be checked against the registry"
                    : "is named by experience " + stillNamed.id())
                + " and was left in place. Nothing was deleted; if that row is not"
                + " supposed to have it, sort the two out by hand.");
            server.logger().info("Refreshed backup " + backup.id() + " of experience " + source.id()
                + ": it now names '" + destination.key() + "'.");
            tell.accept(Outcome.REFRESHED);
            return;
          }
          // Only now, and a failure here does NOT fail the verb: the row is already correct, and the
          // worst case is an orphan folder an operator can see in the log and remove.
          if (!worlds.deletePersistent(oldKey)) {
            server.logger().severe("Backup " + backup.id() + " now names '" + destination.key()
                + "', but its previous folder '" + oldKey.key() + "' could not be removed. It is an"
                + " orphan on disk that nothing references; remove it by hand.");
          }
          server.logger().info("Refreshed backup " + backup.id() + " of experience " + source.id()
              + ": it now names '" + destination.key() + "'.");
          tell.accept(Outcome.REFRESHED);
        }));
  }

  /**
   * The row this player is allowed to act on, or null for both "no such experience" and "not yours".
   *
   * <p>ONE answer on purpose: they are the same answer to whoever asked, and telling them apart would
   * tell a stranger that an id exists.</p>
   */
  private Experience owned(UUID requester, String experienceId) {
    if (requester == null || experienceId == null || experiences == null || !experiences.available()) {
      return null;
    }
    Experience row = experiences.get(experienceId);
    return row == null || !requester.equals(row.owner()) ? null : row;
  }

  /** The run's counters as they are on disk, or null when this world has no state file. */
  private String encodedStateOf(WorldKey key) {
    return store == null || key == null ? null : store.encodedSharedState(key.key());
  }

  /** Writes the file's counters into the row's crash-net column, so the two agree before a swap. */
  private void refreshStoredState(String experienceId, WorldKey key, long now) {
    String truth = encodedStateOf(key);
    if (truth != null) {
      experiences.updateChallengeState(experienceId, truth, now);
    }
  }

  /**
   * Re-points every copied player snapshot at the world that now holds it, while the folder is still
   * staged and no reader can resolve it.
   *
   * <p>Coordinates are kept — this is the same terrain, block for block, which is exactly why a backup
   * must NOT do what a reset's {@code carryPlayerSnapshots} does. Only the world NAME is rewritten,
   * because leaving it naming the source makes the entry path's foreign-world guard reject every saved
   * position and drop everybody at the safe spawn.</p>
   */
  private void rehome(java.nio.file.Path staged, WorldKey source, WorldKey destination) {
    if (store == null || staged == null) {
      return;
    }
    // Only the overworld folder carries the sidecar; the siblings have none, and the call answers 0.
    int rehomed = store.rehomePlayerSnapshots(staged, source.key(), destination.key());
    if (rehomed > 0) {
      server.logger().info("Re-pointed " + rehomed + " player save(s) in the copy of '" + source.key()
          + "' at '" + destination.key() + "'.");
    }
  }

  /**
   * Takes every id this verb is about to work on, or none of them.
   *
   * <p>All-or-nothing so a half-taken claim cannot leave one id locked by a verb that never ran. The
   * ids are deduplicated because a verb may legitimately name the same row twice.</p>
   *
   * @return false when any of them is already being worked on — the caller answers {@link Outcome#BUSY}
   */
  private boolean claim(String... ids) {
    java.util.List<String> taken = new java.util.ArrayList<>(ids.length);
    for (String id : ids) {
      if (id == null || taken.contains(id)) {
        continue;
      }
      if (!inFlight.add(id)) {
        taken.forEach(inFlight::remove);
        return false;
      }
      taken.add(id);
    }
    return true;
  }

  /**
   * Wraps the owner's callback so the claim is released, and the owner answered, exactly once —
   * whichever way the verb ends.
   *
   * <p>Released BEFORE the answer is delivered: an owner who clicks again the moment they are told
   * BUSY is doing the right thing, and a claim still held while the callback runs would refuse them.
   * The callback is the right place for this rather than a {@code finally}, which would fire while the
   * copy is still running — but only because {@link #answering} makes "every path out of these verbs
   * answers" true. It was not: a continuation that threw returned through {@code runOnWorldThread},
   * which catches nothing, and the claim stayed held for the life of the JVM.</p>
   *
   * <p>At most one delivery, not merely one release: {@code answering} may have to answer for a
   * continuation that threw AFTER it had already told the owner, and an owner told twice about one
   * click is a second, contradictory sentence on their screen.</p>
   */
  private Consumer<Outcome> releasing(Consumer<Outcome> tell, String... ids) {
    java.util.concurrent.atomic.AtomicBoolean answered =
        new java.util.concurrent.atomic.AtomicBoolean();
    return outcome -> {
      if (!answered.compareAndSet(false, true)) {
        return;
      }
      for (String id : ids) {
        if (id != null) {
          inFlight.remove(id);
        }
      }
      tell.accept(outcome);
    };
  }

  /**
   * Wraps a copy's continuation so a throw out of it is still an ANSWER, and therefore still a
   * release.
   *
   * <p>These continuations run on the world thread, handed there by {@code PaperWorldControl}'s
   * {@code getGlobalRegionScheduler().run(...)}, and nothing on that path has a {@code catch}. They
   * also do real work with real failure modes: {@code ExperienceManager.transact} asks {@code
   * Database.connection()} for the connection OUTSIDE its own try, and that call throws {@link
   * IllegalStateException} when the round trip to a MariaDB that has just restarted fails. So
   * {@code createBackup} does not return null there — it throws, past {@code tell}, past the claim
   * release, out of the scheduler and into a log line nobody reads.</p>
   *
   * <p>The claim would then be held forever: backup, duplicate, refresh AND restore on those ids
   * answer BUSY — "try again in a moment" — for the rest of the process's life. That is strictly
   * worse than the failure it was guarding, which cost one backup. FAILED is the honest end of a
   * continuation that could not finish, and it is the same word the owner already gets from a copy
   * that would not settle.</p>
   */
  private Consumer<Boolean> answering(Consumer<Outcome> tell, String what,
      Consumer<Boolean> continuation) {
    return copied -> {
      try {
        continuation.accept(copied);
      } catch (RuntimeException failure) {
        // Logged with the throwable, because FAILED is all the owner can be told and an operator
        // needs the cause. `tell` is answer-once, so a throw AFTER the answer is only this line.
        server.logger().severe("Could not finish " + what + ": " + failure, failure);
        tell.accept(Outcome.FAILED);
      }
    };
  }

  /**
   * Per-experience backup cap from {@code worlds.experiences.max-backups-per-experience} (default 3).
   *
   * <p>Separate from the per-player experience cap, and never enforced by deleting: reaching it is a
   * refusal the owner answers by choosing which copy to let go of.</p>
   */
  public int maxBackupsPerExperience() {
    return Math.max(0, server.configuration()
        .getInt("worlds.experiences.max-backups-per-experience", DEFAULT_MAX_BACKUPS));
  }

  /** Default of {@code worlds.experiences.allow-live-copy}: copies may read a world people are in. */
  static final boolean DEFAULT_ALLOW_LIVE_COPY = true;

  /**
   * Whether {@code backup}, {@code refresh} and {@code duplicate} may READ a world that is open.
   *
   * <p>{@code worlds.experiences.allow-live-copy}, default true. It is a deliberate, informed reversal
   * of the original rule, and the tradeoff is not hidden: there is no save barrier on this platform —
   * {@code World.save()} returns before its chunk writes land — so a copy taken from a world under
   * active play genuinely can be a copy of a folder mid-write.</p>
   *
   * <p>What makes that acceptable rather than reckless is the order the copy runs in:</p>
   *
   * <ol>
   *   <li>{@link WorldLeaseService#saveExperienceNow} flushes the Overworld and both linked dimensions
   *       first, which narrows the window without closing it;</li>
   *   <li>every folder is copied inside {@code WorldClone.copyWorldFolderChecked}'s before/after
   *       inventory bracket, sampled at nanosecond mtime precision so that even a same-length rewrite
   *       of a one-byte counter is seen;</li>
   *   <li>a source that moved is copied again, a bounded number of times, and then <b>refused</b>. A
   *       torn copy is never published and never recorded — the owner gets FAILED and their world is
   *       untouched.</li>
   * </ol>
   *
   * <p>So the failure mode this opens is "your backup did not happen", never "your backup is subtly
   * wrong". Setting it false restores the old refusal for these three verbs and nothing else: {@code
   * restore} refuses a busy world regardless, and so does the DELETE at the end of a {@code refresh}.
   * Neither is a read, and neither has a bracket that could catch it being wrong.</p>
   */
  public boolean allowLiveCopy() {
    return server.configuration().getBoolean("worlds.experiences.allow-live-copy",
        DEFAULT_ALLOW_LIVE_COPY);
  }

  /**
   * Per-player world cap from {@code worlds.experiences.max-per-player}, read here because DUPLICATE is
   * the one copy verb that spends a slot of it. Same default and same floor as
   * {@link ExperienceService#maxPerPlayer()}: two readings of one number would eventually disagree.
   */
  public int maxPerPlayer() {
    return Math.max(1, server.configuration().getInt("worlds.experiences.max-per-player",
        ExperienceService.DEFAULT_MAX_PER_PLAYER));
  }
}
