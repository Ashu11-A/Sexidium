package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.transfer.DbTransferService;
import com.sexidium.core.network.transfer.TransferReason;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.ClaimOutcome;
import com.sexidium.core.world.WorldClaim;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The counters and reads a live verification is made of. (F17.)
 *
 * <p>Nothing counted a loop-breaker trip or an eviction, and there was no admin command anywhere that
 * could read the placement or node tables — so the live transfer loop had to be diagnosed by
 * hand-querying MariaDB, which tests the database rather than the system.</p>
 */
class NetworkInspectorTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private static final WorldKey KEY = WorldKey.parse("death_resets_ab12cd34");

  @TempDir
  Path tmp;

  private Database database;
  private DbWorldLeaseAuthority placements;
  private DbTransferService transfers;
  private NetworkInspector inspector;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "inspector.db"));
    NodeIdentity identity =
        NodeIdentity.of("worker-1", "worker-1", NodeIdentity.capabilitiesForRole("worker"));
    NodeRegistry registry = new NodeRegistry(database, SILENT, identity, 30_000L);
    registry.publishAddress("10.0.0.7", 25566);
    registry.heartbeat(3, 2);
    placements = new DbWorldLeaseAuthority(database, SILENT, identity, 100L, 60_000L);
    transfers = new DbTransferService(database, SILENT, "worker-1", 30_000L, 3, 60_000L, 3);
    inspector = new NetworkInspector(registry, placements, transfers);
  }

  @Test
  @DisplayName("nodes report the two columns that used to be wrong: worlds, and an address")
  void nodesReportWorldsAndAddress() {
    NodeRegistry.Node node = inspector.nodes().get(0);

    assertEquals(2, node.worlds(), "worlds was hard-coded 0 on every heartbeat of every node");
    assertTrue(node.addressable(), "address/port existed in the schema and were NEVER written");
    assertEquals("10.0.0.7", node.address());
  }

  @Test
  @DisplayName("placements are readable, and filterable by key, node or state")
  void placementsAreReadableAndFilterable() {
    assertInstanceOf(ClaimOutcome.Granted.class, placements.claim(KEY, null));

    assertEquals(1, inspector.placements(null).size());
    assertEquals(1, inspector.placements("death_resets").size());
    assertEquals(1, inspector.placements("worker-1").size());
    assertEquals(1, inspector.placements("RESERVED").size());
    assertTrue(inspector.placements("nothing_like_this").isEmpty());
  }

  @Test
  @DisplayName("locate answers V2 directly: which node holds this experience, with its fence")
  void locateAnswersTheSingleHolderQuestion() {
    WorldClaim claim =
        assertInstanceOf(ClaimOutcome.Granted.class, placements.claim(KEY, null)).claim();
    placements.confirmOpen(claim);

    var found = inspector.locate(KEY).orElseThrow();

    assertEquals("worker-1", found.nodeId());
    assertEquals(claim.fence(), found.fence(), "the fence is the whole of I1 and has to be visible");
    assertTrue(found.leaseHeld(System.currentTimeMillis()));
  }

  @Test
  @DisplayName("stats counts breaker trips and evictions — the two V-check numbers")
  void statsCountsTripsAndEvictions() {
    assertEquals(0L, inspector.stats().loopBreakerTrips());
    assertEquals(0L, inspector.stats().evictions());

    UUID player = UUID.randomUUID();
    for (int i = 0; i < 4; i++) {
      transfers.request(player, "worker-2", 1L, TransferReason.EXPERIENCE, KEY.key());
      transfers.claimArrival(player, "worker-2", 1L);
    }
    inspector.recordEviction();
    inspector.recordEviction();

    NetworkInspector.TransferStats stats = inspector.stats();
    assertTrue(stats.loopBreakerTrips() >= 1, "V6 watches this number go up");
    assertEquals(2L, stats.evictions());
  }

  @Test
  @DisplayName("stats counts live nodes and loaded worlds")
  void statsCountsLiveNodesAndLoadedWorlds() {
    WorldClaim claim =
        assertInstanceOf(ClaimOutcome.Granted.class, placements.claim(KEY, null)).claim();
    placements.confirmOpen(claim);

    NetworkInspector.TransferStats stats = inspector.stats();

    assertEquals(1, stats.liveNodes());
    assertEquals(1, stats.loadedWorlds(), "V1 checks exactly one row LOADED with a live lease");
  }

  @Test
  @DisplayName("in-flight transfers are listed with their attempt count")
  void inFlightTransfersAreListed() {
    UUID player = UUID.randomUUID();
    transfers.request(player, "worker-2", 5L, TransferReason.EXPERIENCE, KEY.key());

    var tickets = inspector.inFlight();

    assertEquals(1, tickets.size());
    assertEquals("worker-2", tickets.get(0).targetNode());
    assertEquals(5L, tickets.get(0).targetEpoch(), "V1 checks node_epoch != 0");
  }

  @Test
  @DisplayName("evict force-expires the lease WITHOUT unloading or deleting anything")
  void evictForceExpiresTheLease() {
    WorldClaim claim =
        assertInstanceOf(ClaimOutcome.Granted.class, placements.claim(KEY, null)).claim();
    placements.confirmOpen(claim);

    assertTrue(inspector.evict(KEY));

    var row = placements.lookup(KEY.key()).orElseThrow();
    assertFalse(row.leaseHeld(System.currentTimeMillis()));
    // The holder finds out on its next renewal, which is the ordinary eviction path — so the world is
    // evacuated and unloaded by the node that has it open rather than yanked out from under it.
    assertFalse(placements.renew(claim, 1));
    assertFalse(inspector.evict(WorldKey.parse("nothing_ff00ff00")), "and an unknown key is not a lie");
  }

  @Test
  @DisplayName("keyOf follows the newest generation of a run")
  void keyOfFollowsTheLineage() {
    placements.claim(KEY, null);
    placements.allocateNextGeneration("death_resets_ab12cd34", KEY);

    assertEquals("death_resets_ab12cd34_r1", inspector.keyOf("death_resets_ab12cd34").orElseThrow().key());
  }
}
