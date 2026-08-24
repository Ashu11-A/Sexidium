package com.sexidium.core.network;

import com.sexidium.core.platform.LoggerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Makes the three things that all claim to know where a world lives agree with each other.
 *
 * <p>There are three sources of truth and they drift independently:</p>
 *
 * <ol>
 *   <li>the <b>disk</b> — a folder under the experiences root is a world that physically exists;</li>
 *   <li><b>{@code world_placements}</b> — which node is allowed to open it and where players are routed;</li>
 *   <li>the <b>{@code experiences} table</b> — the world a player can see and ask to enter.</li>
 * </ol>
 *
 * <p>They already disagree in production: a world folder with no placement row (which the planner
 * would happily hand to another node, whose acquisition path would generate an empty world over the
 * top of it), and placement rows naming folders nobody has. Left alone the first one destroys a save
 * the first time its owner clicks it.</p>
 *
 * <p><b>The safety rule is one sentence: a world folder is never deleted, and the disk wins ties.</b>
 * Everything here is either an INSERT that records something real, or a DELETE of a row proven to
 * reference nothing at all. Anything ambiguous is logged for a human and left exactly as it is —
 * an unreconciled world costs one confused player, while a wrong repair costs a save.</p>
 *
 * <p>Runs at boot on every node, before players can arrive, and each node reconciles only its own
 * disk: it is the only node that can see it.</p>
 */
public final class PlacementReconciler {

  /**
   * The result of looking at this node's disk.
   *
   * <p>The distinction between {@link #unreadable()} and {@code readable(Set.of())} is the whole
   * point of the type, and it is not hypothetical: the scan used to answer both with an empty set.
   * A shared mount that was slow, not yet mounted, or unreadable therefore arrived here as "this
   * node has no worlds", and every placement row homed here read as a folder that had vanished —
   * which is one branch away from dropping the only record of where a world lives.</p>
   */
  public record DiskScan(boolean readable, Set<String> keys) {

    /** The disk was scanned successfully. An empty set here genuinely means "no worlds here". */
    public static DiskScan readable(Set<String> keys) {
      return new DiskScan(true, Set.copyOf(keys));
    }

    /** The disk could not be scanned. Says nothing at all about what is on it. */
    public static DiskScan unreadable() {
      return new DiskScan(false, Set.of());
    }
  }

  /** What one pass did, in the order the actions are described in {@link #reconcile()}. */
  public record Report(
      List<String> adopted,
      List<String> conflicts,
      List<String> missingFolders,
      List<String> dropped,
      boolean diskUnreadable) {

    /**
     * Nothing to report AND the report is worth something. An unreadable disk is never clean: the
     * pass did nothing, and saying "consistent" about a disk nobody could read is the kind of false
     * reassurance that teaches an operator to stop reading the line.
     */
    public boolean clean() {
      return !diskUnreadable
          && adopted.isEmpty() && conflicts.isEmpty() && missingFolders.isEmpty() && dropped.isEmpty();
    }

    public int total() {
      return adopted.size() + conflicts.size() + missingFolders.size() + dropped.size();
    }
  }

  private final DbWorldLeaseAuthority placements;
  private final NodeIdentity identity;
  private final LoggerAdapter logger;
  private final Supplier<DiskScan> localWorlds;
  private final Predicate<String> isKnownExperience;
  /** Whether every node sees the same folders — which changes what "it is on this disk" proves. */
  private final boolean sharedStorage;

  /**
   * @param localWorlds world keys whose folder is on <em>this</em> node's disk, and whether the
   *     disk could be read at all — see {@link DiskScan}
   * @param isKnownExperience whether the {@code experiences} table still references a world key
   */
  public PlacementReconciler(
      DbWorldLeaseAuthority placements,
      NodeIdentity identity,
      LoggerAdapter logger,
      Supplier<DiskScan> localWorlds,
      Predicate<String> isKnownExperience) {
    this(placements, identity, logger, localWorlds, isKnownExperience, false);
  }

  public PlacementReconciler(
      DbWorldLeaseAuthority placements,
      NodeIdentity identity,
      LoggerAdapter logger,
      Supplier<DiskScan> localWorlds,
      Predicate<String> isKnownExperience,
      boolean sharedStorage) {
    this.placements = placements;
    this.identity = identity;
    this.logger = logger;
    this.localWorlds = localWorlds;
    this.isKnownExperience = isKnownExperience;
    this.sharedStorage = sharedStorage;
  }

  public Report reconcile() {
    DiskScan scan = localWorlds.get();
    if (!scan.readable()) {
      // Every repair below reasons from "the folder is not on this disk". If we could not read the
      // disk we do not know that, and the one destructive branch -- forgetting a row whose folder is
      // gone -- would fire on EVERY row homed here. Reconciling nothing costs a confused operator;
      // reconciling from a failed scan costs the only record of where a world lives.
      logger.severe("Skipping placement reconciliation: this node's experiences folder could not be"
          + " read. No rows were changed. Nothing is wrong with the placements themselves -- fix the"
          + " mount (on the network it is a symlink into the shared tree) and restart this node.");
      return new Report(List.of(), List.of(), List.of(), List.of(), true);
    }
    Set<String> onDisk = scan.keys();
    List<DbWorldLeaseAuthority.Placement> rows = placements.all();

    List<String> adopted = new ArrayList<>();
    List<String> conflicts = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    List<String> dropped = new ArrayList<>();

    for (String worldKey : onDisk) {
      DbWorldLeaseAuthority.Placement row = rows.stream()
          .filter(placement -> placement.worldKey().equals(worldKey))
          .findFirst()
          .orElse(null);

      if (row == null) {
        // The dangerous case, and the reason this class exists. Adopting is safe precisely because
        // nothing else claims the world: no other node can be serving it, because no other node has
        // been told it may.
        //
        // Except on SHARED storage, where "on this disk" is true on every node at once and therefore
        // says nothing about who should host it. Adopting there would have whichever node booted
        // first claim the entire park, and the row would then be an accident of boot order rather
        // than a decision. The gate assigns it properly the first time somebody opens it.
        if (sharedStorage) {
          continue;
        }
        if (placements.adopt(worldKey, DbWorldLeaseAuthority.KIND_PERSISTENT, null)) {
          adopted.add(worldKey);
          logger.warning("Adopted unregistered world '" + worldKey + "' onto node '"
              + identity.nodeId() + "': it was on this disk with no placement row, so it would have"
              + " been planned onto another node and regenerated empty.");
        }
        continue;
      }

      if (!row.nodeId().equals(identity.nodeId())) {
        // On SHARED storage this is the NORMAL state, not a conflict: every node sees every folder,
        // so seeing one here is not evidence of a second copy. Reporting it would mean a SEVERE per
        // world per node on every boot -- the kind of noise that teaches an operator to ignore the
        // word SEVERE, which costs far more than it buys.
        if (sharedStorage) {
          continue;
        }
        // Two disks, one world key. Almost always a leftover copy from a move that was never
        // cleaned up -- but "almost always" is not good enough to delete somebody's world, and
        // guessing wrong either way loses a save. A human decides which copy is real.
        conflicts.add(worldKey);
        logger.severe("World '" + worldKey + "' exists on this node's disk but is homed on node '"
            + row.nodeId() + "'. Nothing was changed. Players go to '" + row.nodeId()
            + "'; if this copy is the real one, stop both nodes and rehome it deliberately.");
      }
    }

    for (DbWorldLeaseAuthority.Placement row : rows) {
      if (!row.nodeId().equals(identity.nodeId()) || onDisk.contains(row.worldKey())) {
        continue; // Another node's row (only it can check its own disk), or a folder we just saw.
      }
      if (row.provisional()) {
        continue; // A plan nobody took up yet. Having no folder is exactly what it means.
      }
      if (isKnownExperience.test(row.worldKey())) {
        // The experience is still listed, so a player can still click it and would be sent here,
        // to a node with no folder -- where it would be regenerated empty. That is worth shouting
        // about, but the row is the only record of where the world was, so it stays.
        missing.add(row.worldKey());
        logger.severe("Placement for '" + row.worldKey() + "' claims this node, but no world folder"
            + " is on this disk. The experience still lists it, so entering will generate a NEW"
            + " empty world. Restore the folder from backup before anyone enters it.");
        continue;
      }
      // No folder here, no experience pointing at it: the row refers to nothing that exists in
      // either of the other two sources of truth. Dropping it cannot lose data because there is
      // no data -- and leaving it would keep a name reserved on a node forever.
      if (placements.forget(row.worldKey())) {
        dropped.add(row.worldKey());
        logger.info("Dropped placement '" + row.worldKey() + "': no folder on this disk and no"
            + " experience refers to it.");
      }
    }

    Report report = new Report(List.copyOf(adopted), List.copyOf(conflicts),
        List.copyOf(missing), List.copyOf(dropped), false);
    if (report.clean()) {
      logger.info("World placements are consistent with this node's disk (" + onDisk.size()
          + " world folder(s)).");
    } else {
      logger.warning("Placement reconciliation: " + adopted.size() + " adopted, " + conflicts.size()
          + " conflict(s), " + missing.size() + " missing folder(s), " + dropped.size() + " dropped.");
    }
    return report;
  }
}
