package com.sexidium.core.network;

import com.sexidium.core.network.transfer.TransferTicket;
import com.sexidium.core.world.ExperienceLocator;
import com.sexidium.core.world.WorldKey;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One place an operator can look. (F17.)
 *
 * <p>When a player landed in the wrong world an operator got: a refusal line on the DECIDING node —
 * a good line, on the wrong machine, since the symptom is on the worker — one routing line on the
 * proxy, and, on the destination, nothing whatsoever. That is why a player ping-ponging between the
 * lobby and worker-1 eight times in 45 seconds left no trace on either node. There were no metrics,
 * no counters, and no admin command anywhere under {@code command/} that could read the placement or
 * node tables at all. The two columns anybody would check first were both wrong: {@code worlds} was
 * hard-coded 0 and {@code players} was a whole-node count.</p>
 */
public final class NetworkInspector {

  /**
   * The counters a live verification actually needs, and which nothing counted.
   *
   * @param loopBreakerTrips how many transfers the breaker has refused since this process started
   * @param evictions        how many worlds this node has been evicted from (a renewal refused)
   * @param transfersInFlight live tickets across the network right now
   * @param liveNodes        nodes with a fresh heartbeat
   * @param loadedWorlds     placements in LOADED with a live lease
   */
  public record TransferStats(
      long loopBreakerTrips,
      long evictions,
      int transfersInFlight,
      int liveNodes,
      int loadedWorlds) {
  }

  private final NodeRegistry registry;
  private final DbWorldLeaseAuthority placements;
  private final com.sexidium.core.network.transfer.DbTransferService transfers;
  /** Evictions are counted here because this is the only object that outlives every individual world. */
  private final AtomicLong evictions = new AtomicLong();

  public NetworkInspector(NodeRegistry registry, DbWorldLeaseAuthority placements,
      com.sexidium.core.network.transfer.DbTransferService transfers) {
    this.registry = registry;
    this.placements = placements;
    this.transfers = transfers;
  }

  /** Called by the world layer whenever a claim is lost, so the count is real rather than inferred. */
  public void recordEviction() {
    evictions.incrementAndGet();
  }

  public List<NodeRegistry.Node> nodes() {
    return registry == null ? List.of() : registry.all();
  }

  /**
   * Placements, optionally filtered by a substring of the key, the node or the state.
   *
   * <p>Filtering in Java over one already-fetched list rather than in SQL: this is an operator command
   * run by hand, the table is small, and every additional query shape is another statement competing
   * for the single JDBC connection that gameplay is also using.</p>
   */
  public List<DbWorldLeaseAuthority.Placement> placements(String filter) {
    if (placements == null) {
      return List.of();
    }
    List<DbWorldLeaseAuthority.Placement> all = placements.all();
    if (filter == null || filter.isBlank()) {
      return all;
    }
    String needle = filter.trim().toLowerCase(java.util.Locale.ROOT);
    return all.stream()
        .filter(placement -> contains(placement.worldKey(), needle)
            || contains(placement.nodeId(), needle)
            || contains(placement.state(), needle))
        .toList();
  }

  /** Where one experience's world lives right now. The read-only question the system never had. */
  public Optional<ExperienceLocator.Placement> locate(WorldKey key) {
    return placements == null ? Optional.empty() : placements.locate(key);
  }

  public Optional<WorldKey> keyOf(String experienceId) {
    return placements == null ? Optional.empty() : placements.keyOf(experienceId);
  }

  public List<TransferTicket> inFlight() {
    return transfers == null ? List.of() : transfers.inFlight();
  }

  public TransferStats stats() {
    long now = System.currentTimeMillis();
    int live = 0;
    for (NodeRegistry.Node node : nodes()) {
      if (!NodeRegistry.STATE_DOWN.equals(node.state())) {
        live++;
      }
    }
    int loaded = 0;
    for (DbWorldLeaseAuthority.Placement placement : placements(null)) {
      if (DbWorldLeaseAuthority.STATE_LOADED.equals(placement.state()) && placement.leaseHeld(now)) {
        loaded++;
      }
    }
    return new TransferStats(
        transfers == null ? 0L : transfers.trips(),
        evictions.get(),
        inFlight().size(),
        live,
        loaded);
  }

  /**
   * Force a world's lease to lapse, so a stuck holder can be released by hand.
   *
   * <p>The escape hatch for the one failure the design cannot resolve on its own: a world that could
   * not be unloaded keeps its claim renewed for ever — correctly, because its region files ARE open —
   * and is then permanently un-takeable by any peer. Live, one world sat LOADED with zero players and
   * a perpetually renewed lease for two days.</p>
   *
   * <p>It does NOT unload anything and it does not touch a folder. It clears the row, and the holder
   * finds out on its next renewal — which is exactly the eviction path, so the world is evacuated and
   * unloaded by the node that has it open rather than yanked out from under it.</p>
   */
  public boolean evict(WorldKey key) {
    if (placements == null || key == null) {
      return false;
    }
    return placements.forceExpireLease(key.key());
  }

  private static boolean contains(String value, String needle) {
    return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
  }
}
