package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a node finds when it comes back from the restart it drained for.
 *
 * <p>The reclaim is a <b>query</b>, and these tests are the reason. {@code DbNetworkBus.start()}
 * seeds its cursor to {@code MAX(id)}, so a node that was down for the whole restart sees nothing
 * that was published while it was gone — a bus-based reclaim would be correct in every test that
 * never restarts anything and wrong in production, every time.</p>
 */
class DrainReclaimTest {

  private static final long TIMEOUT = 30_000L;

  private static final class CapturingLogger implements LoggerAdapter {
    final List<String> lines = new ArrayList<>();

    @Override public void info(String message) {
      lines.add(message);
    }

    @Override public void warning(String message) {
      lines.add(message);
    }

    @Override public void severe(String message) {
      lines.add(message);
    }

    @Override public void warning(String message, Throwable throwable) {
      lines.add(message);
    }

    @Override public void severe(String message, Throwable throwable) {
      lines.add(message);
    }

    String reclaimLine() {
      return lines.stream().filter(line -> line.startsWith("SX-RECLAIM ")).findFirst().orElse("");
    }
  }

  @TempDir
  Path tmp;

  private Database database;
  private final AtomicLong now = new AtomicLong(9_000_000L);
  private CapturingLogger logger;
  private DbDrainStore store;
  private DbNetworkLeases leases;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "reclaim.db"));
    logger = new CapturingLogger();
    store = new DbDrainStore(database, logger);
    leases = new DbNetworkLeases(database, logger, now::get);
  }

  private NodeIdentity identity(String nodeId, String role) {
    return NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole(role));
  }

  private DbWorldLeaseAuthority placements(String nodeId, long epoch) {
    return new DbWorldLeaseAuthority(database, logger, identity(nodeId, "worker"), epoch, 15_000L);
  }

  private DrainCoordinator coordinator(String nodeId, NodeRegistry registry) {
    return new DrainCoordinator(identity(nodeId, "worker"), registry, store, leases,
        placements(nodeId, registry.epoch()), DrainWorkPort.NOOP, null, logger,
        DrainCoordinator.Settings.defaults(), now::get);
  }

  @Test
  @DisplayName("a returning node keeps what is still homed here and counts what peers took")
  void reclaimKeepsMineAndCountsWhatPeersAdopted() {
    NodeRegistry before = new NodeRegistry(database, logger, identity("worker-1", "worker"), TIMEOUT);
    before.heartbeat(0, 0);
    new NodeRegistry(database, logger, identity("lobby", "lobby"), TIMEOUT).heartbeat(1, 0);

    // Three worlds homed here when the drain began.
    DbWorldLeaseAuthority mine = placements("worker-1", before.epoch());
    mine.claim("experiences/alpha_ab12", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    mine.claim("experiences/beta_cd34", DbWorldLeaseAuthority.KIND_PERSISTENT, null);
    mine.claim("experiences/gamma_ef56", DbWorldLeaseAuthority.KIND_PERSISTENT, null);

    DrainCoordinator drain = coordinator("worker-1", before);
    assertTrue(drain.drain("rolling-update", false, "api").accepted());
    assertEquals(3, drain.state().worldsTotal());

    // The node goes away. A peer adopts one of the three, which rewrites its node_id.
    assertTrue(new DbWorldLeaseAuthority(database, logger, identity("worker-2", "worker"),
        before.epoch() + 5L, 15_000L).rehome("experiences/gamma_ef56", "worker-2"));

    // ... and comes back with a FRESH epoch.
    NodeRegistry after = new NodeRegistry(database, logger, identity("worker-1", "worker"), TIMEOUT);
    coordinator("worker-1", after).reclaim();

    assertEquals("SX-RECLAIM node=worker-1 kept=2 adoptedByPeers=1", logger.reclaimLine());
    assertTrue(store.byNode("worker-1").isEmpty(), "the drain row goes with the drain");
    assertTrue(leases.holder(DbNetworkLeases.DRAIN).isEmpty(),
        "a node that came back still holding the drain lease would block every other node's roll");
  }

  @Test
  @DisplayName("a node with no drain row reclaims nothing and says nothing")
  void reclaimIsSilentWhenThereWasNoDrain() {
    NodeRegistry registry =
        new NodeRegistry(database, logger, identity("worker-1", "worker"), TIMEOUT);
    coordinator("worker-1", registry).reclaim();
    assertEquals("", logger.reclaimLine());
  }

  @Test
  @DisplayName("a row carrying THIS boot's epoch is impossible, so it is reported and deleted")
  void reclaimDeletesACorruptSameEpochRow() {
    NodeRegistry registry =
        new NodeRegistry(database, logger, identity("worker-1", "worker"), TIMEOUT);
    registry.heartbeat(0, 0);
    assertTrue(store.insert(new DrainRecord("worker-1", registry.epoch(), 77L, DrainPhase.OFFERING,
        "x", "api", now.get(), 0, 0, 0, 0, null, now.get(), now.get())));

    coordinator("worker-1", registry).reclaim();

    assertTrue(store.byNode("worker-1").isEmpty());
    assertTrue(logger.lines.stream().anyMatch(line -> line.contains("cannot happen")),
        "the epoch is minted fresh from the wall clock on every boot; a row at this boot's epoch"
            + " means something wrote a row it had no right to, and an operator must be told");
  }

  @Test
  @DisplayName("a drain row swept while the node was down leaves nothing to reclaim, and no noise")
  void reclaimHandlesARowSweptWhileDown() {
    NodeRegistry before = new NodeRegistry(database, logger, identity("worker-1", "worker"), TIMEOUT);
    before.heartbeat(0, 0);
    new NodeRegistry(database, logger, identity("lobby", "lobby"), TIMEOUT).heartbeat(1, 0);
    assertTrue(coordinator("worker-1", before).drain("rolling-update", false, "api").accepted());

    // A sweeper removed it between the read and the CAS.
    DrainCoordinator returning = coordinator("worker-1",
        new NodeRegistry(database, logger, identity("worker-1", "worker"), TIMEOUT));
    store.deleteAny("worker-1");

    returning.reclaim();
    assertEquals("", logger.reclaimLine());
  }
}
