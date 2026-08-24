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
 * The {@code node_drains} table, and the only thing that writes it.
 *
 * <p><b>This table is the response channel of the drain protocol, and the bus is not.</b> That is the
 * one design decision everything else here follows from. The draining node <em>releases</em> its
 * claims and its peers answer by taking them through the conditional UPDATE they already use — the
 * acknowledgement is the row, so it survives a restart, cannot be delivered twice, and can be read by
 * anyone at any time. {@code DbNetworkBus} could not do this job under any amount of care: it is
 * broadcast-only with no ack and no dedupe, and {@code DbNetworkBus.start()} seeds its cursor to
 * {@code MAX(id)}, so a node that was down for the restart sees <em>nothing</em> published while it
 * was gone. The {@code node.drain} bus topic exists, carries one advisory line, and nothing derives
 * state from it.</p>
 *
 * <p>Every mutation past the insert is fenced on {@code (node_id, node_epoch, fence)}. A node that
 * restarted mid-drain therefore cannot write over its predecessor's row by accident — it has to go
 * through {@link #reclaim}, which is a deliberate compare-and-swap against the epoch it just read.</p>
 */
public final class DbDrainStore {

  /**
   * The {@code node_drains} column list — the drain protocol's wire surface.
   *
   * <p>Public because {@code ProtocolContractTest} digests it: renaming {@code worlds_left} is a
   * change to what a mixed-build fleet reads, and it must not be possible to make it quietly.</p>
   */
  public static final String COLUMNS =
      "node_id, node_epoch, fence, phase, reason, requested_by, deadline, worlds_total,"
          + " worlds_left, players_left, matches_left, detail, started_at, updated_at";

  private final Database database;
  private final LoggerAdapter logger;

  public DbDrainStore(Database database, LoggerAdapter logger) {
    this.database = database;
    this.logger = logger;
  }

  /** The drain row of one node, whatever epoch wrote it. */
  public Optional<DrainRecord> byNode(String nodeId) {
    if (nodeId == null) {
      return Optional.empty();
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM node_drains WHERE node_id = ?")) {
        ps.setString(1, nodeId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(read(rs)) : Optional.empty();
        }
      } catch (SQLException failed) {
        logger.warning("Could not read the drain row for '" + nodeId + "': " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  /** Every drain row in the network. Read only by the stale-row sweep. */
  public List<DrainRecord> all() {
    List<DrainRecord> rows = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM node_drains ORDER BY node_id");
           ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(read(rs));
        }
      } catch (SQLException failed) {
        logger.warning("Could not read the drain table: " + failed.getMessage());
      }
    }
    return rows;
  }

  /**
   * Record a new drain. False means a row already exists for this node, which the caller must read
   * rather than overwrite — the primary key is what makes two concurrent drain requests converge on
   * one answer instead of two.
   */
  public boolean insert(DrainRecord row) {
    if (row == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "INSERT INTO node_drains (" + COLUMNS + ")"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        bind(ps, row);
        ps.executeUpdate();
        return true;
      } catch (SQLException failed) {
        if (duplicateKey(failed)) {
          return false;
        }
        // NOT "a row already exists": the caller reads false as exactly that and tells the operator
        // to "try again", which is advice that can never work against a missing table or a dead
        // connection. A refusal has to name the failure the operator can act on.
        logger.severe("Could not write the drain row for '" + row.nodeId() + "': "
            + failed.getMessage());
        throw new IllegalStateException(
            "node_drains is unwritable (" + failed.getMessage() + ")", failed);
      }
    }
  }

  /**
   * Write a row back, guarded on the fence.
   *
   * <p>False means this process is no longer the author of that row: it restarted, or a sweeper
   * removed it. The caller must stop driving the drain rather than keep writing into a row somebody
   * else now owns — that is the whole reason the fence is here.</p>
   */
  public boolean update(DrainRecord row) {
    if (row == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE node_drains SET phase = ?, reason = ?, requested_by = ?, deadline = ?,"
              + " worlds_total = ?, worlds_left = ?, players_left = ?, matches_left = ?,"
              + " detail = ?, started_at = ?, updated_at = ?"
              + " WHERE node_id = ? AND node_epoch = ? AND fence = ?")) {
        ps.setString(1, row.phase().name());
        ps.setString(2, row.reason());
        ps.setString(3, row.requestedBy());
        ps.setLong(4, row.deadline());
        ps.setInt(5, row.worldsTotal());
        ps.setInt(6, row.worldsLeft());
        ps.setInt(7, row.playersLeft());
        ps.setInt(8, row.matchesLeft());
        ps.setString(9, row.detail());
        ps.setLong(10, row.startedAt());
        ps.setLong(11, row.updatedAt());
        ps.setString(12, row.nodeId());
        ps.setLong(13, row.nodeEpoch());
        ps.setLong(14, row.fence());
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not update the drain row for '" + row.nodeId() + "': "
            + failed.getMessage());
        return false;
      }
    }
  }

  /** Remove a row, guarded on the fence. The normal end of a drain, and of an undrain. */
  public boolean delete(String nodeId, long nodeEpoch, long fence) {
    return deleteWhere("node_id = ? AND node_epoch = ? AND fence = ?", ps -> {
      ps.setString(1, nodeId);
      ps.setLong(2, nodeEpoch);
      ps.setLong(3, fence);
    }, nodeId);
  }

  /**
   * Remove a row without the fence.
   *
   * <p>Two callers, both of which have established that no live author exists: the stale sweep (the
   * node is DOWN and the row has not moved for its whole stale window) and the boot-time corruption
   * branch (a row claiming this node's <em>current</em> epoch, which is impossible — the epoch is
   * minted fresh on every boot).</p>
   */
  public boolean deleteAny(String nodeId) {
    return deleteWhere("node_id = ?", ps -> ps.setString(1, nodeId), nodeId);
  }

  /**
   * Take over a row left behind by a previous epoch of this same node.
   *
   * <p>A genuine compare-and-swap against {@code node_epoch}: if a sweeper deleted the row, or a
   * concurrent boot got there first, the UPDATE matches nothing and the caller learns it has nothing
   * to reclaim. It cannot be done with {@link #update} because a fresh boot does not know — and must
   * not have to guess — its predecessor's fence.</p>
   */
  public boolean reclaim(String nodeId, long previousEpoch, long newEpoch, long newFence, long now) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE node_drains SET phase = ?, node_epoch = ?, fence = ?, updated_at = ?"
              + " WHERE node_id = ? AND node_epoch = ?")) {
        ps.setString(1, DrainPhase.RECLAIMING.name());
        ps.setLong(2, newEpoch);
        ps.setLong(3, newFence);
        ps.setLong(4, now);
        ps.setString(5, nodeId);
        ps.setLong(6, previousEpoch);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not reclaim the drain row for '" + nodeId + "': " + failed.getMessage());
        return false;
      }
    }
  }

  /**
   * Write {@code network_nodes.state} and nothing else.
   *
   * <p>Deliberately not {@code NodeRegistry.draining()}, which publishes {@code players = 0} and
   * {@code worlds = 0} alongside the state. Those two columns are precisely what an orchestrator
   * polls to decide a node is empty, so zeroing them at the START of a drain would tell it the node
   * was drained while forty players were still standing on it. The heartbeat writes the true counts;
   * this overwrites one column on top of them, on every tick, so the state cannot flap back to UP.</p>
   */
  public boolean markNodeState(String nodeId, String state) {
    if (nodeId == null || state == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE network_nodes SET state = ? WHERE node_id = ?")) {
        ps.setString(1, state);
        ps.setString(2, nodeId);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not mark '" + nodeId + "' as " + state + ": " + failed.getMessage());
        return false;
      }
    }
  }

  /**
   * Move this node's lobby groups to a peer.
   *
   * <p>Membership is already database-backed, so a lobby group "moves" by having one column rewritten
   * — there is no state in a JVM to serialise. Guarded on the current {@code node_id}, which makes it
   * idempotent: a re-run matches nothing.</p>
   *
   * <p>Queue tickets are deliberately NOT rewritten: {@code lobby_queue_tickets} is keyed by
   * {@code lobby_id} and carries no node column at all, so a ticket follows its group by construction.
   * Adding a node column there to "also move" them would create a second, redundant owner record that
   * could disagree with the first.</p>
   *
   * @return how many groups moved
   */
  public int repointLobbyGroups(String fromNodeId, String toNodeId) {
    if (fromNodeId == null || toNodeId == null || fromNodeId.equals(toNodeId)) {
      return 0;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE lobby_groups SET node_id = ?, updated_at = ? WHERE node_id = ?")) {
        ps.setString(1, toNodeId);
        ps.setLong(2, System.currentTimeMillis());
        ps.setString(3, fromNodeId);
        return ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not move the lobby groups of '" + fromNodeId + "' to '" + toNodeId
            + "': " + failed.getMessage());
        return 0;
      }
    }
  }

  /**
   * Whether a failed INSERT means "a row for this node already exists".
   *
   * <p>SQLState class 23 is the SQL standard's integrity-constraint violation and is what MySQL and
   * PostgreSQL report. sqlite-jdbc does not always set one, so its constraint failures are recognised
   * by the wording it does guarantee. Anything else is a broken database, and saying so is the whole
   * point of this method.</p>
   */
  private static boolean duplicateKey(SQLException failed) {
    String state = failed.getSQLState();
    if (state != null && state.startsWith("23")) {
      return true;
    }
    String message = failed.getMessage() == null
        ? "" : failed.getMessage().toLowerCase(java.util.Locale.ROOT);
    return message.contains("constraint") || message.contains("duplicate");
  }

  private interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  private boolean deleteWhere(String where, Binder binder, String nodeId) {
    if (nodeId == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps =
          database.connection().prepareStatement("DELETE FROM node_drains WHERE " + where)) {
        binder.bind(ps);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not delete the drain row for '" + nodeId + "': " + failed.getMessage());
        return false;
      }
    }
  }

  private static void bind(PreparedStatement ps, DrainRecord row) throws SQLException {
    ps.setString(1, row.nodeId());
    ps.setLong(2, row.nodeEpoch());
    ps.setLong(3, row.fence());
    ps.setString(4, row.phase().name());
    ps.setString(5, row.reason());
    ps.setString(6, row.requestedBy());
    ps.setLong(7, row.deadline());
    ps.setInt(8, row.worldsTotal());
    ps.setInt(9, row.worldsLeft());
    ps.setInt(10, row.playersLeft());
    ps.setInt(11, row.matchesLeft());
    ps.setString(12, row.detail());
    ps.setLong(13, row.startedAt());
    ps.setLong(14, row.updatedAt());
  }

  private static DrainRecord read(ResultSet rs) throws SQLException {
    return new DrainRecord(
        rs.getString(1), rs.getLong(2), rs.getLong(3), phaseOf(rs.getString(4)), rs.getString(5),
        rs.getString(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
        rs.getString(12), rs.getLong(13), rs.getLong(14));
  }

  /**
   * A phase name this build does not know reads as {@link DrainPhase#STALLED}, never as NONE.
   *
   * <p>The pessimistic direction is the only safe one. A future build may add a phase; a node that
   * read it as "not draining" would plan worlds onto a node on its way out. Read as STALLED it is
   * excluded, an operator is told to look, and nothing is lost.</p>
   */
  private static DrainPhase phaseOf(String name) {
    if (name == null) {
      return DrainPhase.NONE;
    }
    try {
      return DrainPhase.valueOf(name);
    } catch (IllegalArgumentException unknown) {
      return DrainPhase.STALLED;
    }
  }
}
