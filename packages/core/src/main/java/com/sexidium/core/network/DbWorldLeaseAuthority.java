package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Which node physically holds a world folder.
 *
 * <p>This is the highest-stakes class in the network layer, because of one asymmetry:</p>
 *
 * <ul>
 *   <li>{@code node_id} is a durable <b>assignment</b>. It is authoritative for routing and survives
 *       the node being offline. A persistent experience's chunks live on that machine's disk.</li>
 *   <li>{@code lease_expires_at} is only a <b>load lease</b> — mutual exclusion so two nodes cannot
 *       have the same world open at once.</li>
 * </ul>
 *
 * <p><b>Lease expiry never reassigns.</b> It marks the placement {@code ORPHANED}. Reassigning would
 * point a player at a node with no such folder on disk, and the world-acquisition path treats "no
 * folder on disk" as "generate a fresh one" — silently replacing a player's saved map with an empty
 * world. An experience whose worker is down must report the worker as offline, not be re-created
 * somewhere else.</p>
 *
 * <p>Moving a world between nodes is therefore a deliberate operation ({@link #rehome}) that copies
 * the folder first and refuses while the world is loaded — never a side effect of a timeout.</p>
 */
public final class DbWorldLeaseAuthority implements com.sexidium.core.world.WorldLeaseAuthority {

  public static final String STATE_RESERVED = "RESERVED";
  /**
   * A world nobody has open. Replaces both UNLOADED and ORPHANED.
   *
   * <p>ORPHANED was meaningless under shared storage — it said "the node holding the bytes is gone",
   * which on one shared tree is never true — and it was produced by the reaper as a side effect of
   * clearing leases it had no business clearing. There is one idle state now.</p>
   */
  public static final String STATE_IDLE = "IDLE";
  public static final String STATE_LOADED = "LOADED";
  public static final String STATE_MIGRATING = "MIGRATING";

  public static final String KIND_PERSISTENT = "PERSISTENT";
  public static final String KIND_TEMP = "TEMP";
  public static final String KIND_LOBBY = "LOBBY";

  /** A world's home, as recorded. */
  public record Placement(
      String worldKey,
      String experienceId,
      int generation,
      String kind,
      String nodeId,
      long nodeEpoch,
      long fence,
      String ownerUuid,
      String state,
      int players,
      long leaseExpiresAt) {

    /** The pre-fence shape, for the many constructions that only set the routing fields. */
    public Placement(String worldKey, String kind, String nodeId, long nodeEpoch, String ownerUuid,
        String state, int players, long leaseExpiresAt) {
      this(worldKey, null, 0, kind, nodeId, nodeEpoch, 0L, ownerUuid, state, players, leaseExpiresAt);
    }

    public boolean leaseHeld(long now) {
      return leaseExpiresAt > now;
    }

    /**
     * A home that was <em>planned</em> and never taken up: no node has ever opened this world, so no
     * folder exists anywhere for it.
     *
     * <p>The marker is {@code node_epoch == 0} while still {@code RESERVED}. Every path that touches a
     * world locally — {@link #claim}, {@link #claimOn}, {@link #adopt} — stamps this node's boot epoch
     * first, so a non-zero epoch proves some node got as far as opening it. That distinction is what
     * makes re-planning safe: a provisional row can be moved to another node without risking a single
     * chunk, while a materialised row must never move except through {@link #rehome}.</p>
     */
    public boolean provisional() {
      return STATE_RESERVED.equals(state) && nodeEpoch == 0L;
    }
  }

  /** Outcome of asking to host a world. */
  public sealed interface ClaimResult {
    /** This node may load the world. */
    record Granted(Placement placement) implements ClaimResult { }

    /** Another live node currently has it open. */
    record HeldElsewhere(String nodeId) implements ClaimResult { }

    /** The world belongs to a different node's disk; the player must be routed there. */
    record WrongNode(String nodeId) implements ClaimResult { }
  }

  private final Database database;
  private final LoggerAdapter logger;
  private final NodeIdentity identity;
  private final long epoch;
  private final long leaseMillis;
  /**
   * Whether every node sees the same world folders.
   *
   * <p>The one fact that decides whether an idle world may change hands. With per-node disks a
   * placement is an assignment to a MACHINE and only that machine can serve it; with a shared tree it
   * is merely who holds it open, and any worker can take an idle one — which is what makes the
   * workers load balancers rather than permanent homes. What does NOT change either way: a world
   * with a live lease is never contested, because two servers writing one set of region files
   * corrupts them.</p>
   */
  public DbWorldLeaseAuthority(
      Database database, LoggerAdapter logger, NodeIdentity identity, long epoch, long leaseMillis) {
    this.database = database;
    this.logger = logger;
    this.identity = identity;
    this.epoch = epoch;
    this.leaseMillis = leaseMillis;
  }

  public Optional<Placement> lookup(String worldKey) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          SELECT_COLUMNS + " FROM world_placements WHERE world_key = ?")) {
        ps.setString(1, worldKey);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(read(rs)) : Optional.empty();
        }
      } catch (SQLException failed) {
        logger.warning("Placement lookup failed for " + worldKey + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  /** Every read below projects these columns, in this order. */
  private static final String SELECT_COLUMNS =
      "SELECT world_key, experience_id, generation, kind, node_id, node_epoch, fence, owner_uuid,"
          + " state, players, lease_expires_at";

  private static Placement read(ResultSet rs) throws SQLException {
    return new Placement(
        rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
        rs.getLong(6), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getInt(10),
        rs.getLong(11));
  }

  /**
   * Ask to host a world on this node.
   *
   * <p>An unplaced world is assigned here. A world already assigned here is re-leased. A world
   * assigned elsewhere is refused with the node that owns it, so the caller can route rather than
   * generate.</p>
   */
  public ClaimResult claim(String worldKey, String kind, String ownerUuid) {
    return claimOn(worldKey, kind, ownerUuid, identity.nodeId());
  }

  /**
   * Ask to host a world, having already decided <em>where</em> an unplaced one should live.
   *
   * <p>The difference from {@link #claim} is the whole point of the placement work: a node that
   * discovers an unplaced world does not automatically become its home. It consults
   * {@link NodePlacementPlanner}, passes the answer here, and — when the answer is another node —
   * gets back {@link ClaimResult.WrongNode} so the player is routed instead of the world being
   * generated locally. Recording the decision in the row (rather than keeping it in memory) is what
   * makes two nodes planning the same world at the same instant converge: the primary key means one
   * insert wins and the loser reads the winner's answer.</p>
   *
   * <p>{@code plannedNodeId} only ever applies to a world with no materialised home. An existing
   * placement wins outright, with one exception spelled out in {@link Placement#provisional()}: a
   * plan that its target never took up, and whose target the caller has established is not coming
   * back, may be re-planned because there is no folder anywhere to lose.</p>
   */
  public ClaimResult claimOn(String worldKey, String kind, String ownerUuid, String plannedNodeId) {
    long now = System.currentTimeMillis();
    String target = plannedNodeId == null || plannedNodeId.isBlank() ? identity.nodeId() : plannedNodeId;
    boolean local = target.equals(identity.nodeId());
    Optional<Placement> existing = lookup(worldKey);

    if (existing.isEmpty()) {
      // A remote plan is written WITHOUT an epoch and WITHOUT a lease: this node is recording a
      // decision, not opening anything. The target stamps both when it actually claims the world.
      Placement placement = new Placement(
          worldKey, kind, target, local ? localEpoch() : 0L, ownerUuid, STATE_RESERVED, 0,
          local ? now + leaseMillis : 0L);
      if (insert(placement)) {
        return local ? new ClaimResult.Granted(placement) : new ClaimResult.WrongNode(target);
      }
      // Lost an insert race: re-read and answer from what the winner wrote.
      return lookup(worldKey)
          .<ClaimResult>map(winner -> winner.nodeId().equals(identity.nodeId())
              ? new ClaimResult.Granted(winner)
              : new ClaimResult.WrongNode(winner.nodeId()))
          .orElse(new ClaimResult.HeldElsewhere("unknown"));
    }

    Placement placement = existing.get();
    if (!placement.nodeId().equals(target)
        && !placement.leaseHeld(now)
        && repoint(worldKey, placement.nodeId(), target, local ? localEpoch() : 0L)) {
      logger.info("Re-planned '" + worldKey + "' from '" + placement.nodeId() + "' to '" + target
          + "'; " + (placement.provisional()
              ? "it was only ever a plan, so no world folder exists to move."
              : "the world is idle and its folder is on storage both nodes can see."));
      return claimOn(worldKey, kind, ownerUuid, target);
    }
    if (!placement.nodeId().equals(identity.nodeId())) {
      // A live lease means that node is serving the world right now. Never contested, under either
      // storage model: one open world, one writer.
      if (placement.leaseHeld(now)) {
        return new ClaimResult.HeldElsewhere(placement.nodeId());
      }
      // No live lease, so take it over. Every worker sees the same bytes, so an idle world belongs
      // to whoever needs it -- which is what makes the workers interchangeable rather than each being
      // the permanent home of whatever it happened to open first. WHETHER this node should be the one
      // taking it over is PlacementPlanner's decision, made before we get here.
      if (!local) {
        return new ClaimResult.WrongNode(placement.nodeId());
      }
      if (!repoint(worldKey, placement.nodeId(), identity.nodeId(), localEpoch())) {
        // Lost the race to another worker doing the same thing; answer from what it wrote.
        return lookup(worldKey)
            .<ClaimResult>map(winner -> winner.nodeId().equals(identity.nodeId())
                ? new ClaimResult.Granted(winner)
                : new ClaimResult.HeldElsewhere(winner.nodeId()))
            .orElse(new ClaimResult.HeldElsewhere(placement.nodeId()));
      }
      logger.info("Took over idle world '" + worldKey + "' from '" + placement.nodeId()
          + "'; its lease had lapsed.");
      placement = new Placement(
          worldKey, placement.kind(), identity.nodeId(), localEpoch(), placement.ownerUuid(),
          placement.state(), placement.players(), placement.leaseExpiresAt());
    }

    // Ours: renew the lease and adopt the current boot's epoch.
    Placement renewed = new Placement(
        worldKey, placement.kind(), identity.nodeId(), localEpoch(), placement.ownerUuid(),
        STATE_RESERVED, placement.players(), now + leaseMillis);
    update(renewed);
    return new ClaimResult.Granted(renewed);
  }

  /**
   * This boot's epoch, never zero.
   *
   * <p>Zero is reserved as "no node has ever opened this world" (see {@link Placement#provisional()}),
   * so a local claim must never write it — otherwise a world this node really is hosting would look
   * like an untaken plan and could be re-planned out from under its own folder.</p>
   */
  private long localEpoch() {
    return Math.max(1L, epoch);
  }

  /**
   * Move a plan that was never taken up to a different node.
   *
   * <p>Guarded on the whole precondition — same old node, still RESERVED, still epoch 0 — so it is a
   * compare-and-swap: if the target woke up and claimed the world between the read and this write,
   * the UPDATE matches nothing and the caller falls through to routing the player there, which is
   * the correct outcome.</p>
   */
  private boolean repoint(String worldKey, String fromNodeId, String toNodeId, long toEpoch) {
    synchronized (database.lock()) {
      // Two different guards, because "may this row move?" has two different answers.
      //
      // Per-node disks: only a PLAN nobody took up (RESERVED, epoch 0). A materialised row names the
      // one machine holding the bytes and moving it would point players at a node with no folder.
      //
      // Shared storage: any row whose LEASE HAS LAPSED, materialised or not -- every node can read the
      // folder, so the row is only "who has it open", and nobody has. Without this branch the takeover
      // above could never actually happen: the UPDATE matched zero rows, silently, and the caller fell
      // through to reporting the world as busy on a node that had closed it. The lease predicate stays
      // inside the statement so the check and the write are one atomic step.
      // ONE guard: the lease has lapsed. The per-node-disk alternative (" AND state = ? AND
      // node_epoch = 0", i.e. only a plan nobody took up) is gone with the flag that selected it --
      // it defaulted FALSE in two legacy constructors while production defaults TRUE, so every test
      // that used those constructors exercised a mode the network never runs in. That is how F10
      // shipped: the branch it lived in had zero coverage under the deployed storage model.
      String guard = " AND lease_expires_at <= ?";
      // The lease is TAKEN in the same statement that takes the row, and that is the whole point.
      // Repointing without it left the row "mine, with an expired lease" between this write and the
      // renew that used to follow -- which is exactly the predicate the shared guard above tests, so a
      // second node could take the same world in that gap. Reproduced: 12 double-grants in 400 races.
      // One statement, one winner, no window.
      long now = System.currentTimeMillis();
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET node_id = ?, node_epoch = ?, lease_expires_at = ?, updated_at = ?"
              + " WHERE world_key = ? AND node_id = ?" + guard)) {
        ps.setString(1, toNodeId);
        ps.setLong(2, toEpoch);
        // A remote PLAN takes no lease: only the node that will actually open the world may hold one.
        ps.setLong(3, toNodeId.equals(identity.nodeId()) ? now + leaseMillis : 0L);
        ps.setLong(4, now);
        ps.setString(5, worldKey);
        ps.setString(6, fromNodeId);
        ps.setLong(7, now);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not re-plan " + worldKey + ": " + failed.getMessage());
        return false;
      }
    }
  }

  /** Every placement in the network. Used by reconciliation, which has to see other nodes' rows. */
  public List<Placement> all() {
    List<Placement> placements = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          SELECT_COLUMNS + " FROM world_placements ORDER BY world_key");
           ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          placements.add(read(rs));
        }
      } catch (SQLException failed) {
        logger.warning("Could not list placements: " + failed.getMessage());
      }
    }
    return placements;
  }

  /**
   * Record a world this node already has on disk but which nothing had registered.
   *
   * <p>The one operation that turns a folder into an assignment. It exists because the alternative
   * outcome is data loss: an unregistered world is invisible to the planner, so it would be planned
   * onto some other node, and that node — finding no folder — would generate an empty world under the
   * same name while the real save sat untouched on this disk, unreachable.</p>
   *
   * <p>State is {@code UNLOADED} rather than {@code RESERVED}: the world is materialised (it is right
   * there on the disk) but not currently open, and the non-zero epoch keeps re-planning away from it.</p>
   */
  public boolean adopt(String worldKey, String kind, String ownerUuid) {
    return insert(new Placement(
        worldKey, kind, identity.nodeId(), localEpoch(), ownerUuid, STATE_IDLE, 0, 0L));
  }

  /**
   * Delete a placement row.
   *
   * <p>For bookkeeping that provably refers to nothing — no folder on any disk and no experience
   * pointing at it. It never touches a world folder, and callers must establish that emptiness
   * themselves; this method cannot, because it can only see the database.</p>
   */
  public boolean forget(String worldKey) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "DELETE FROM world_placements WHERE world_key = ? AND state <> ?")) {
        ps.setString(1, worldKey);
        // Never delete a row for a world someone currently has open, whatever the caller believes.
        ps.setString(2, STATE_LOADED);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not drop the placement of " + worldKey + ": " + failed.getMessage());
        return false;
      }
    }
  }

  private boolean insert(Placement placement) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "INSERT INTO world_placements (world_key, experience_id, generation, kind, node_id,"
              + " node_epoch, fence, owner_uuid, state, players, lease_expires_at, updated_at)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        ps.setString(1, placement.worldKey());
        ps.setString(2, placement.experienceId());
        ps.setInt(3, placement.generation());
        ps.setString(4, placement.kind());
        ps.setString(5, placement.nodeId());
        ps.setLong(6, placement.nodeEpoch());
        ps.setLong(7, placement.fence());
        ps.setString(8, placement.ownerUuid());
        ps.setString(9, placement.state());
        ps.setInt(10, placement.players());
        ps.setLong(11, placement.leaseExpiresAt());
        ps.setLong(12, System.currentTimeMillis());
        return ps.executeUpdate() > 0;
      } catch (SQLException duplicateOrFailure) {
        // The primary key on world_key makes a concurrent insert fail here rather than
        // producing two homes for one world. That is the desired outcome, not an error.
        return false;
      }
    }
  }

  private void update(Placement placement) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          // `AND node_id = ?`: without it this statement would quietly TAKE BACK a row that a peer
          // legitimately owns now -- a node whose main thread stalled past the timeout has its rows
          // orphaned and re-homed, and its next renew would have overwritten that with itself.
          "UPDATE world_placements SET kind = ?, node_id = ?, node_epoch = ?, owner_uuid = ?,"
              + " state = ?, players = ?, lease_expires_at = ?, updated_at = ?"
              + " WHERE world_key = ? AND node_id = ?")) {
        ps.setString(1, placement.kind());
        ps.setString(2, placement.nodeId());
        ps.setLong(3, placement.nodeEpoch());
        ps.setString(4, placement.ownerUuid());
        ps.setString(5, placement.state());
        ps.setInt(6, placement.players());
        ps.setLong(7, placement.leaseExpiresAt());
        ps.setLong(8, System.currentTimeMillis());
        ps.setString(9, placement.worldKey());
        ps.setString(10, placement.nodeId());
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Placement update failed for " + placement.worldKey() + ": " + failed.getMessage());
      }
    }
  }

  /** Mark a claimed world as open and renew its lease. Called once the world is actually loaded. */
  public void confirmLoaded(String worldKey, int players) {
    setState(worldKey, STATE_LOADED, players, System.currentTimeMillis() + leaseMillis);
  }

  /** Renew while a world stays open; a lapsed lease lets a peer treat it as orphaned. */
  public void renew(String worldKey, int players) {
    setState(worldKey, STATE_LOADED, players, System.currentTimeMillis() + leaseMillis);
  }

  /**
   * Release the load lease, keeping the assignment.
   *
   * <p>The world stays homed here — its folder has not moved — so a returning player is still routed
   * to this node.</p>
   */
  public void release(String worldKey) {
    setState(worldKey, STATE_IDLE, 0, 0L);
  }

  private void setState(String worldKey, String state, int players, long leaseExpiresAt) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET state = ?, players = ?, lease_expires_at = ?, updated_at = ?"
              + " WHERE world_key = ? AND node_id = ?")) {
        ps.setString(1, state);
        ps.setInt(2, players);
        ps.setLong(3, leaseExpiresAt);
        ps.setLong(4, System.currentTimeMillis());
        ps.setString(5, worldKey);
        // Guarded on ownership: a node must never rewrite another node's placement.
        ps.setString(6, identity.nodeId());
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Placement state change failed for " + worldKey + ": " + failed.getMessage());
      }
    }
  }

  /** Placements homed on a node. Used at boot to re-adopt this node's own worlds. */
  public List<Placement> placementsOn(String nodeId) {
    List<Placement> placements = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          SELECT_COLUMNS + " FROM world_placements WHERE node_id = ? ORDER BY world_key")) {
        ps.setString(1, nodeId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            placements.add(read(rs));
          }
        }
      } catch (SQLException failed) {
        logger.warning("Could not list placements for " + nodeId + ": " + failed.getMessage());
      }
    }
    return placements;
  }

  /**
   * Tidy up after a node the registry has declared DOWN — <b>without ever shortening a live lease</b>.
   *
   * <p>This replaces {@code orphanPlacementsOf}, which was the single most dangerous statement in the
   * tree. It cleared {@code lease_expires_at} on EVERY row of the named node, <em>including rows in
   * LOADED with a lease that was still perfectly valid</em>. Node liveness is not a fencing token: a
   * 35-second GC pause, a chunk-save storm, or one slow query on the connection every heartbeat shares
   * with gameplay was enough to have a healthy worker declared dead — and the instant its lease was
   * zeroed a peer's {@code adoptableHere} saw an idle world with a readable folder and opened the same
   * region files. The stalled node then woke up and carried on writing, because its own renew was
   * guarded on {@code node_id} alone and silently matched nothing.</p>
   *
   * <p>So the predicate is now {@code AND lease_expires_at <= ?}: only leases that have ALREADY
   * expired by the clock are cleared, and a reaper can therefore never create the window it was
   * supposed to be closing. A genuinely dead node's leases expire on their own within
   * {@code world-lease-seconds}, which stage 0 made strictly shorter than the node timeout precisely
   * so that this is always true by the time anybody reaps.</p>
   *
   * @return how many rows were tidied; a live holder contributes zero, which is the point
   */
  public int clearExpiredLeasesOf(String deadNodeId) {
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET state = ?, players = 0, lease_expires_at = 0, fence = 0,"
              + " updated_at = ? WHERE node_id = ? AND state <> ? AND lease_expires_at <= ?")) {
        ps.setString(1, STATE_IDLE);
        ps.setLong(2, now);
        ps.setString(3, deadNodeId);
        ps.setString(4, STATE_IDLE);
        // THE guard. Without it a reaper shortens a lease it has no business touching.
        ps.setLong(5, now);
        return ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not clear expired leases of " + deadNodeId + ": " + failed.getMessage());
        return 0;
      }
    }
  }

  /**
   * Deliberately move a world's home to another node.
   *
   * <p>The <em>only</em> supported way a placement changes node. Refuses while the world is open,
   * because the folder has to be copied across and copying a live world folder is how region files
   * get corrupted. The caller is responsible for actually moving the bytes (a documented rsync)
   * before calling this.</p>
   *
   * <p>Without this, a worker can never be decommissioned and a dead disk is permanent data loss —
   * which is why it is in the first release rather than deferred with the automated rebalancer.</p>
   */
  public boolean rehome(String worldKey, String targetNodeId) {
    Optional<Placement> existing = lookup(worldKey);
    if (existing.isEmpty()) {
      return false;
    }
    Placement placement = existing.get();
    if (STATE_LOADED.equals(placement.state()) && placement.leaseHeld(System.currentTimeMillis())) {
      logger.warning("Refusing to rehome " + worldKey + ": still loaded on " + placement.nodeId());
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET node_id = ?, node_epoch = 0, state = ?, players = 0,"
              + " lease_expires_at = 0, updated_at = ? WHERE world_key = ?")) {
        ps.setString(1, targetNodeId);
        ps.setString(2, STATE_IDLE);
        ps.setLong(3, System.currentTimeMillis());
        ps.setString(4, worldKey);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Rehome failed for " + worldKey + ": " + failed.getMessage());
        return false;
      }
    }
  }

  // ===== WorldLeaseAuthority: the fence ==========================================================

  /**
   * Mint a grant token. Random and non-zero, never a counter.
   *
   * <p>Fencing needs UNIQUENESS PER GRANT, not ordering. A random token needs no read-back — MariaDB
   * has no {@code UPDATE … RETURNING}, so a counter would need a second statement and a window
   * between them — and no clock, so it cannot be confused by skew between nodes. Do not "improve"
   * this into a monotonic counter.
   */
  private static long mintFence() {
    long fence;
    do {
      fence = java.util.concurrent.ThreadLocalRandom.current().nextLong();
    } while (fence == 0L); // zero is the "never granted" value in the column
    return fence;
  }

  @Override
  public com.sexidium.core.world.ClaimOutcome claim(
      com.sexidium.core.world.WorldKey key, String ownerUuid) {
    if (key == null) {
      return new com.sexidium.core.world.ClaimOutcome.Unavailable("no world key");
    }
    long now = System.currentTimeMillis();
    long fence = mintFence();
    long expiresAt = now + leaseMillis;
    String worldKey = key.key();

    synchronized (database.lock()) {
      // ONE conditional UPDATE. This is invariant I1 in a single statement: whoever's UPDATE matches
      // owns the world, and there is no window between deciding and writing for a second node to slip
      // into. claimOn used to read, decide in Java, and then write -- three round trips with the
      // decision made in the gap.
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET node_id = ?, node_epoch = ?, fence = ?, state = ?,"
              + " players = 0, lease_expires_at = ?, updated_at = ?"
              + " WHERE world_key = ? AND lease_expires_at <= ?")) {
        ps.setString(1, identity.nodeId());
        ps.setLong(2, localEpoch());
        ps.setLong(3, fence);
        ps.setString(4, STATE_RESERVED);
        ps.setLong(5, expiresAt);
        ps.setLong(6, now);
        ps.setString(7, worldKey);
        ps.setLong(8, now);
        if (ps.executeUpdate() > 0) {
          return new com.sexidium.core.world.ClaimOutcome.Granted(
              new com.sexidium.core.world.WorldClaim(
                  key, identity.nodeId(), localEpoch(), fence, expiresAt));
        }
      } catch (SQLException failed) {
        logger.warning("Could not claim '" + worldKey + "': " + failed.getMessage());
        return new com.sexidium.core.world.ClaimOutcome.Unavailable(failed.getMessage());
      }

      // The UPDATE matched nothing: either the row does not exist yet, or somebody holds a live lease.
      Optional<Placement> existing = lookup(worldKey);
      if (existing.isEmpty()) {
        Placement fresh = new Placement(
            worldKey, key.base(), key.generation(), KIND_PERSISTENT, identity.nodeId(),
            localEpoch(), fence, ownerUuid, STATE_RESERVED, 0, expiresAt);
        if (insert(fresh)) {
          return new com.sexidium.core.world.ClaimOutcome.Granted(
              new com.sexidium.core.world.WorldClaim(
                  key, identity.nodeId(), localEpoch(), fence, expiresAt));
        }
        // Lost the insert race; the primary key settled it. Read the winner's answer.
        return lookup(worldKey)
            .<com.sexidium.core.world.ClaimOutcome>map(winner ->
                new com.sexidium.core.world.ClaimOutcome.Elsewhere(winner.nodeId(), true))
            .orElse(new com.sexidium.core.world.ClaimOutcome.Unavailable("insert race lost twice"));
      }
      Placement held = existing.get();

      // RE-ENTRANT for the node that already holds it. Without this the CAS refuses its own holder --
      // a node renews its lease every 5 s, so `lease_expires_at <= now` is false for the very node
      // that owns the world -- and the caller was told Elsewhere(itself). The world layer then
      // "routed" the players to the node they were already standing on, the proxy completed that
      // ticket as LANDED ("already on the target"), and nothing opened the world: clicking Enter did
      // nothing at all, silently. Reachable for every second entrant to a live world, and for any
      // re-entry inside the unload-retry window.
      //
      // The existing fence is returned, NEVER a fresh one. Minting here would invalidate the claim
      // this node is already holding in memory, and its next renew would report eviction -- turning a
      // harmless re-ask into a self-inflicted evacuation.
      if (held.nodeId().equals(identity.nodeId())
          && held.nodeEpoch() == localEpoch()
          && held.fence() != 0
          && held.leaseHeld(now)) {
        return new com.sexidium.core.world.ClaimOutcome.Granted(
            new com.sexidium.core.world.WorldClaim(
                key, identity.nodeId(), localEpoch(), held.fence(), held.leaseExpiresAt()));
      }
      return new com.sexidium.core.world.ClaimOutcome.Elsewhere(held.nodeId(), held.leaseHeld(now));
    }
  }

  @Override
  public boolean renew(com.sexidium.core.world.WorldClaim claim, int players) {
    return fenced(claim, STATE_LOADED, players, System.currentTimeMillis() + leaseMillis, "renew");
  }

  @Override
  public boolean confirmOpen(com.sexidium.core.world.WorldClaim claim) {
    return fenced(claim, STATE_LOADED, 0, System.currentTimeMillis() + leaseMillis, "confirmOpen");
  }

  @Override
  public boolean release(com.sexidium.core.world.WorldClaim claim) {
    return fenced(claim, STATE_IDLE, 0, 0L, "release");
  }

  @Override
  public boolean unclaim(com.sexidium.core.world.WorldClaim claim) {
    // RESERVED -> FREE. The row keeps its node_id (it is a fine place to try again) but drops the
    // lease and the fence, so anybody may claim it immediately. The rollback that did not exist.
    return fenced(claim, STATE_IDLE, 0, 0L, "unclaim");
  }

  /**
   * Every write a holder makes, guarded on {@code (node_id, node_epoch, fence)}.
   *
   * <p>The return value is the whole point: {@code false} means the row no longer matches this claim,
   * i.e. THIS NODE HAS BEEN EVICTED. Before the fence, {@code renew} was guarded on {@code node_id}
   * alone, so a dispossessed holder's renewal matched zero rows and it never found out.</p>
   *
   * <p>A SQL FAILURE is reported as {@code true}, deliberately. Eviction has to mean "the row
   * genuinely does not match us any more", not "we could not ask": a database hiccup that ejected
   * every player from every healthy world would be far worse than the race this guards against, and
   * the lease expiring on its own is the correct fallback if the database really is gone.</p>
   */
  private boolean fenced(com.sexidium.core.world.WorldClaim claim, String state, int players,
      long leaseExpiresAt, String what) {
    if (claim == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET state = ?, players = ?, lease_expires_at = ?, updated_at = ?"
              + " WHERE world_key = ? AND node_id = ? AND node_epoch = ? AND fence = ?")) {
        ps.setString(1, state);
        ps.setInt(2, players);
        ps.setLong(3, leaseExpiresAt);
        ps.setLong(4, System.currentTimeMillis());
        ps.setString(5, claim.key().key());
        ps.setString(6, claim.nodeId());
        ps.setLong(7, claim.nodeEpoch());
        ps.setLong(8, claim.fence());
        if (ps.executeUpdate() > 0) {
          return true;
        }
      } catch (SQLException failed) {
        logger.warning("Could not " + what + " '" + claim.key() + "': " + failed.getMessage());
        return true; // a blip is not an eviction
      }
      logger.severe("EVICTED: this node's claim on '" + claim.key() + "' (epoch " + claim.nodeEpoch()
          + ", fence " + claim.fence() + ") no longer matches the placement row, so " + what
          + " matched nothing. Another node holds this world. Freezing writes and unloading.");
      return false;
    }
  }

  /**
   * Force a lease to lapse, by hand, for the admin surface.
   *
   * <p>The escape hatch for the one failure the design cannot resolve on its own: a world that could
   * not be unloaded keeps its claim renewed for ever -- correctly, because its region files ARE open
   * -- and is then permanently un-takeable. Live, one world sat LOADED with zero players and a
   * perpetually renewed lease for two days, and neither {@code forget}, {@code rehome} nor an
   * adoption could touch it.</p>
   *
   * <p>Clearing the FENCE is what makes this safe: the holder's very next renewal is refused, which
   * is the ordinary eviction path, so the world is evacuated and unloaded by the node that has it
   * open rather than being yanked out from under it.</p>
   */
  public boolean forceExpireLease(String worldKey) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE world_placements SET lease_expires_at = 0, fence = 0, state = ?, players = 0,"
              + " updated_at = ? WHERE world_key = ?")) {
        ps.setString(1, STATE_IDLE);
        ps.setLong(2, System.currentTimeMillis());
        ps.setString(3, worldKey);
        if (ps.executeUpdate() > 0) {
          logger.warning("An operator force-expired the lease on '" + worldKey + "'. Its holder will"
              + " be refused on its next renewal and will evacuate and unload it.");
          return true;
        }
        return false;
      } catch (SQLException failed) {
        logger.warning("Could not force-expire '" + worldKey + "': " + failed.getMessage());
        return false;
      }
    }
  }

  // ===== ExperienceLocator: the read-only half ===================================================

  @Override
  public Optional<com.sexidium.core.world.ExperienceLocator.Placement> locate(
      com.sexidium.core.world.WorldKey key) {
    return key == null ? Optional.empty() : lookup(key.key()).map(DbWorldLeaseAuthority::project);
  }

  /**
   * The same read as {@link #locate}, except that a failed one says so.
   *
   * <p>{@link #lookup} logs a SQLException and answers {@code Optional.empty()} — indistinguishable
   * from a world nothing has recorded. Every caller that merely routes can live with that; the one
   * that DELETES cannot, because "no row" is its licence to forget the rows naming the world. This is
   * the one read that does not collapse the two.</p>
   */
  @Override
  public com.sexidium.core.world.ExperienceLocator.Home home(com.sexidium.core.world.WorldKey key) {
    if (key == null) {
      return com.sexidium.core.world.ExperienceLocator.Home.none();
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          SELECT_COLUMNS + " FROM world_placements WHERE world_key = ?")) {
        ps.setString(1, key.key());
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next()
              ? com.sexidium.core.world.ExperienceLocator.Home.at(project(read(rs)))
              : com.sexidium.core.world.ExperienceLocator.Home.none();
        }
      } catch (SQLException failed) {
        logger.warning("Placement lookup failed for " + key.key() + ": " + failed.getMessage()
            + " -- reporting UNKNOWN, not 'no such world'.");
        return com.sexidium.core.world.ExperienceLocator.Home.unknown();
      }
    }
  }

  @Override
  public Optional<com.sexidium.core.world.WorldKey> keyOf(String experienceId) {
    if (experienceId == null || experienceId.isBlank()) {
      return Optional.empty();
    }
    synchronized (database.lock()) {
      // Two predicates, because `experience_id` holds the world BASE (`<slug>_<id>`) rather than the
      // experience id -- claim() has the WorldKey and not the Experience, so that is the identity it
      // can write. The exact match is what allocateNextGeneration uses (it passes a base); the suffix
      // match is what an operator typing an experience id needs. Without the second one
      // `/sx admin net locate <experienceId>` matched nothing, ever, which is the command the
      // single-holder verification leans on. A LIKE with a leading wildcard cannot use the index, and
      // that is fine: this runs when a human types it, not on the entry path.
      try (PreparedStatement ps = database.connection().prepareStatement(
          SELECT_COLUMNS + " FROM world_placements WHERE experience_id = ? OR experience_id LIKE ?"
              + " ORDER BY generation DESC")) {
        ps.setString(1, experienceId);
        // `_` is LIKE's single-character wildcard, and the character it lands on here IS the literal
        // underscore of `<slug>_<id>`. Deliberately not `\_`: backslash is an escape by default on
        // MySQL and not on SQLite (which needs an explicit ESCAPE clause), so escaping would behave
        // differently in production than in the tests.
        ps.setString(2, "%_" + experienceId);
        try (ResultSet rs = ps.executeQuery()) {
          // The NEWEST generation is the live one; a reset leaves its predecessor behind until the
          // teardown poll can prove it is empty.
          return rs.next()
              ? com.sexidium.core.world.WorldKey.tryParse(rs.getString(1))
              : Optional.empty();
        }
      } catch (SQLException failed) {
        logger.warning("Could not resolve the world of " + experienceId + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  @Override
  public List<com.sexidium.core.world.ExperienceLocator.Placement> heldBy(String nodeId) {
    List<com.sexidium.core.world.ExperienceLocator.Placement> held = new ArrayList<>();
    for (Placement placement : placementsOn(nodeId)) {
      held.add(project(placement));
    }
    return held;
  }

  private static com.sexidium.core.world.ExperienceLocator.Placement project(Placement placement) {
    return new com.sexidium.core.world.ExperienceLocator.Placement(
        com.sexidium.core.world.WorldKey.tryParse(placement.worldKey()).orElse(null),
        placement.experienceId(), placement.nodeId(), placement.nodeEpoch(), placement.fence(),
        placement.state(), placement.players(), placement.leaseExpiresAt());
  }

  /**
   * The next generation of a run, allocated IN THE PLACEMENT ROW.
   *
   * <p>{@code SELECT MAX(generation)} over one indexed lineage plus a guarded insert of the successor;
   * the primary key on {@code world_key} settles a race between two allocators. What this replaces
   * probed up to 1000 candidate names, each one doing a {@code Files.exists} plus a backend disk test
   * per linked dimension — up to 3000 stats against a network filesystem, on the main thread, on the
   * tick a player died.</p>
   */
  @Override
  public com.sexidium.core.world.WorldKey allocateNextGeneration(
      String experienceId, com.sexidium.core.world.WorldKey current) {
    if (current == null) {
      return null;
    }
    String lineage = experienceId == null || experienceId.isBlank() ? current.base() : experienceId;
    synchronized (database.lock()) {
      int highest = current.generation();
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT MAX(generation) FROM world_placements WHERE experience_id = ?")) {
        ps.setString(1, lineage);
        try (ResultSet rs = ps.executeQuery()) {
          if (rs.next()) {
            highest = Math.max(highest, rs.getInt(1));
          }
        }
      } catch (SQLException failed) {
        logger.warning("Could not read the generation of " + lineage + ": " + failed.getMessage());
      }
      // Bounded, like the probe loop it replaces: if a hundred consecutive successors are all taken
      // something is very wrong and spinning makes it worse.
      for (int attempt = 0; attempt < 100; attempt++) {
        com.sexidium.core.world.WorldKey candidate =
            new com.sexidium.core.world.WorldKey(current.base(), ++highest);
        Placement reserved = new Placement(
            candidate.key(), lineage, candidate.generation(), KIND_PERSISTENT, identity.nodeId(),
            localEpoch(), 0L, null, STATE_IDLE, 0, 0L);
        if (insert(reserved)) {
          return candidate;
        }
        // The row already exists, which means somebody allocated this generation first. Next.
      }
      logger.severe("Could not allocate a successor generation for '" + current + "'.");
      return null;
    }
  }
}
