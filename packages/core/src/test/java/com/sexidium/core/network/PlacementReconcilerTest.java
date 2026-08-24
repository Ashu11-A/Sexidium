package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconciliation is allowed to be wrong in exactly one direction: it may leave a mess for a human.
 * It may never remove a world, and it may never hand a world with a folder to a node without one.
 */
class PlacementReconcilerTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private Database database;
  private final Set<String> onDisk = new LinkedHashSet<>();
  private final Set<String> experiences = new LinkedHashSet<>();

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "reconcile.db"));
  }

  private DbWorldLeaseAuthority placements(String nodeId) {
    return new DbWorldLeaseAuthority(
        database, SILENT,
        NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole("worker")), 42L, 20_000L);
  }

  private PlacementReconciler reconcilerOn(String nodeId) {
    return new PlacementReconciler(
        placements(nodeId),
        NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole("worker")),
        SILENT,
        () -> PlacementReconciler.DiskScan.readable(Set.copyOf(onDisk)),
        experiences::contains);
  }

  /** Same node, but the disk cannot be read at all — a broken mount rather than an empty park. */
  private PlacementReconciler blindReconcilerOn(String nodeId) {
    return new PlacementReconciler(
        placements(nodeId),
        NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole("worker")),
        SILENT,
        PlacementReconciler.DiskScan::unreadable,
        experiences::contains);
  }

  @Test
  @DisplayName("a world on disk with no placement row is adopted by the node that has it")
  void adoptsUnregisteredWorld() {
    // The live case: look_multiplies_c47df3fe existed on the lobby's disk with no row at all, so the
    // planner would have handed it to a worker, which would have generated an empty world over it.
    onDisk.add("Ashu11a/Look_Multiplies_c47df3fe");
    experiences.add("Ashu11a/Look_Multiplies_c47df3fe");

    PlacementReconciler.Report report = reconcilerOn("lobby").reconcile();

    assertEquals(1, report.adopted().size());
    var placement = placements("lobby").lookup("Ashu11a/Look_Multiplies_c47df3fe").orElseThrow();
    assertEquals("lobby", placement.nodeId());
    assertFalse(placement.provisional(), "an adopted world must not be re-plannable");
  }

  @Test
  @DisplayName("a folder that duplicates another node's world is reported, never resolved")
  void conflictingCopyIsLeftAlone() {
    placements("worker-1").claim("a/dup_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    onDisk.add("a/dup_1");
    experiences.add("a/dup_1");

    PlacementReconciler.Report report = reconcilerOn("worker-2").reconcile();

    assertEquals(1, report.conflicts().size());
    assertTrue(report.adopted().isEmpty());
    // Routing is unchanged: players still go to the node that was always the home.
    assertEquals("worker-1", placements("worker-2").lookup("a/dup_1").orElseThrow().nodeId());
  }

  @Test
  @DisplayName("a row whose folder is gone but whose experience still exists is kept and shouted about")
  void missingFolderWithLiveExperienceIsKept() {
    placements("worker-1").claim("a/lost_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-1").release("a/lost_1");
    experiences.add("a/lost_1");

    PlacementReconciler.Report report = reconcilerOn("worker-1").reconcile();

    assertEquals(1, report.missingFolders().size());
    assertTrue(report.dropped().isEmpty());
    // The row is the only surviving record of where that world lived. Deleting it would let the
    // planner put a fresh empty world somewhere else and call the matter closed.
    assertTrue(placements("worker-1").lookup("a/lost_1").isPresent());
  }

  @Test
  @DisplayName("a row referring to no folder and no experience is dropped")
  void danglingRowIsDropped() {
    placements("worker-1").claim("a/ghost_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-1").release("a/ghost_1");

    PlacementReconciler.Report report = reconcilerOn("worker-1").reconcile();

    assertEquals(1, report.dropped().size());
    assertTrue(placements("worker-1").lookup("a/ghost_1").isEmpty());
  }

  @Test
  @DisplayName("a node never touches another node's rows: only it can see its own disk")
  void otherNodesRowsAreUntouched() {
    placements("worker-1").claim("a/theirs_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-1").release("a/theirs_1");

    PlacementReconciler.Report report = reconcilerOn("worker-2").reconcile();

    assertTrue(report.clean());
    assertTrue(placements("worker-2").lookup("a/theirs_1").isPresent());
  }

  @Test
  @DisplayName("a plan nobody has taken up yet is not mistaken for a lost world")
  void provisionalRowIsNotReported() {
    placements("lobby").claimOn("a/planned_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null, "worker-1");
    experiences.add("a/planned_1");

    // worker-1 boots, has not opened it yet, and correctly has no folder for it.
    assertTrue(reconcilerOn("worker-1").reconcile().clean());
    assertTrue(placements("worker-1").lookup("a/planned_1").isPresent());
  }

  @Test
  @DisplayName("a consistent node changes nothing")
  void consistentNodeIsANoOp() {
    placements("worker-1").claim("a/fine_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    onDisk.add("a/fine_1");
    experiences.add("a/fine_1");

    assertTrue(reconcilerOn("worker-1").reconcile().clean());
  }

  @Test
  @DisplayName("a disk that could not be read drops nothing — the one branch that loses data")
  void anUnreadableDiskDropsNothing() {
    // Exactly the setup danglingRowIsDropped uses, and there the row IS dropped. The only difference
    // is that the scan failed rather than came back empty -- which the old code could not express,
    // because an unreadable folder and an empty one were both Set.of(). Every row homed here then
    // read as "folder gone", and this branch fired on all of them.
    placements("worker-1").claim("a/ghost_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    placements("worker-1").release("a/ghost_1");

    PlacementReconciler.Report report = blindReconcilerOn("worker-1").reconcile();

    assertTrue(report.dropped().isEmpty());
    assertTrue(placements("worker-1").lookup("a/ghost_1").isPresent(),
        "an unreadable mount must never be grounds for deleting the only record of where a world is");
  }

  @Test
  @DisplayName("a disk that could not be read does not report a live world as missing")
  void anUnreadableDiskReportsNothingMissing() {
    placements("worker-1").claim("a/real_1", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    experiences.add("a/real_1");
    // The folder is right there; we simply could not look. Shouting "restore it from backup" about a
    // world that is fine is how an operator learns to ignore the message that matters.
    onDisk.add("a/real_1");

    PlacementReconciler.Report report = blindReconcilerOn("worker-1").reconcile();

    assertTrue(report.missingFolders().isEmpty());
  }

  @Test
  @DisplayName("a disk that could not be read is never reported as consistent")
  void anUnreadableDiskIsNotClean() {
    PlacementReconciler.Report report = blindReconcilerOn("worker-1").reconcile();

    assertTrue(report.diskUnreadable());
    assertFalse(report.clean(), "a pass that did nothing because it could see nothing is not clean");
    assertEquals(0, report.total());
  }
}
