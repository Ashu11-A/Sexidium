package com.sexidium.core.network;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.world.ClaimOutcome;
import com.sexidium.core.world.WorldClaim;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who releases a world claim on the way down, and when.
 *
 * <p>{@code NetworkService.close()} used to do it, for every claim, as the FIRST thing
 * {@code SexidiumCore.close()} does — while the worlds themselves are unloaded near the end of the
 * same method, and an experience world is not unloaded by the disposal policy at all, so its region
 * files are written by the platform's final save seconds later. A released claim is an invitation:
 * {@code adoptableHere} passes on any peer that can see the folder, {@code claim()}'s CAS matches on
 * the lapsed lease, and two servers write one save. The claim belongs to whoever can prove the world
 * is really closed, which is the world layer.</p>
 */
class NetworkServiceShutdownTest {

  private static final WorldKey KEY = WorldKey.parse("diamond_hunt_ab12cd34");

  /** A worker on a network: a node id, no standalone flag, and a shared database. */
  private static class WorkerAdapter extends TestServerAdapter {
    private final Path dataDirectory;

    WorkerAdapter(Path dataDirectory) {
      this.dataDirectory = dataDirectory;
    }

    @Override public Path dataDirectory() { return dataDirectory; }

    @Override public NodeIdentity identity() {
      return NodeIdentity.of("worker-1", "worker-1", Set.of(NodeCapability.EXPERIENCES));
    }
  }

  @TempDir
  Path tmp;

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "network.db"));
  }

  @Test
  @DisplayName("close() leaves the claims alone: the lease has to outlive the close")
  void closeDoesNotReleaseClaims() {
    NetworkService network = new NetworkService(new WorkerAdapter(tmp), database);
    DbWorldLeaseAuthority placements = network.placements();
    WorldClaim claim = granted(placements);
    placements.confirmOpen(claim);
    network.setClaimHeartbeat(() -> List.of(claim), lost -> { });

    network.close();

    DbWorldLeaseAuthority.Placement row = placements.lookup(KEY.key()).orElseThrow();
    assertEquals(DbWorldLeaseAuthority.STATE_LOADED, row.state(),
        "an IDLE row with a lapsed lease is exactly the shape a peer adopts, and this node has not"
            + " even started closing the world yet");
    assertTrue(row.leaseHeld(System.currentTimeMillis()),
        "the lease IS the mutual exclusion; dropping it here advertises a world we are still writing");
  }

  @Test
  @DisplayName("and the claim still works afterwards, so the world layer can release it on unload")
  void theClaimIsStillUsableAfterClose() {
    NetworkService network = new NetworkService(new WorkerAdapter(tmp), database);
    DbWorldLeaseAuthority placements = network.placements();
    WorldClaim claim = granted(placements);
    placements.confirmOpen(claim);

    network.close();

    assertTrue(placements.release(claim), "the fence is unchanged: the world layer's release lands");
    assertEquals(DbWorldLeaseAuthority.STATE_IDLE,
        placements.lookup(KEY.key()).orElseThrow().state());
  }

  @Test
  @DisplayName("one heartbeat writes the DRAINING state, instead of publishing UP and correcting it")
  void theHeartbeatPublishesTheDrainStateItself() {
    // A scheduler that runs a timer's first tick inline, which is the one thing DirectSchedulerAdapter
    // will not do -- and the heartbeat is private, driven only from that timer.
    NetworkService network = new NetworkService(new WorkerAdapter(tmp) {
      @Override public com.sexidium.core.platform.SchedulerAdapter scheduler() {
        return new com.sexidium.core.platform.SchedulerAdapter() {
          @Override public com.sexidium.core.platform.ScheduledTask runNow(Runnable runnable) {
            runnable.run();
            return com.sexidium.core.platform.noop.NoopScheduledTask.INSTANCE;
          }

          @Override public com.sexidium.core.platform.ScheduledTask runLater(
              Runnable runnable, long delayTicks) {
            return runNow(runnable);
          }

          @Override public com.sexidium.core.platform.ScheduledTask runTimer(
              Runnable runnable, long delayTicks, long periodTicks) {
            return runNow(runnable);
          }

          @Override public void runAsync(Runnable runnable) {
            runnable.run();
          }
        };
      }
    }, database);
    // A coordinator that KNOWS it is draining but writes nothing itself, so the only thing that can
    // publish the state is the heartbeat. In production the coordinator's tick() re-asserts DRAINING
    // in a SECOND transaction, right after the heartbeat published UP -- and a peer reading
    // network_nodes between the two picks a node that is on its way out.
    network.setDrainControl(new DrainControlPort() {
      @Override public DrainState state() {
        return new DrainState(DrainPhase.OFFERING, "rolling-update", "api", 1L, 2L, 1, 1, 3, 0, null);
      }

      @Override public DrainResult drain(String reason, boolean force, String requestedBy) {
        return DrainResult.accepted(state());
      }

      @Override public DrainResult undrain() {
        return DrainResult.accepted(DrainState.idle());
      }

      @Override public void tick() { }
    });

    network.start();

    NodeRegistry.Node row = network.registry().all().stream()
        .filter(node -> "worker-1".equals(node.nodeId())).findFirst().orElseThrow();
    assertEquals(NodeRegistry.STATE_DRAINING, row.state(),
        "the heartbeat has to publish what is true, not publish UP and have somebody else correct it"
            + " in a second transaction");
    network.close();
  }

  private WorldClaim granted(DbWorldLeaseAuthority placements) {
    ClaimOutcome outcome = placements.claim(KEY, null);
    Optional<WorldClaim> claim = outcome instanceof ClaimOutcome.Granted granted
        ? Optional.of(granted.claim()) : Optional.empty();
    return claim.orElseThrow(() -> new AssertionError("the first claimant must be granted: " + outcome));
  }
}
