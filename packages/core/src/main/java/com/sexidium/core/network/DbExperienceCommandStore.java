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
 * The {@code experience_commands} table, and the only thing that writes it.
 *
 * <p>Every mutation past the insert is a conditional UPDATE on the state the caller believes the row
 * is in. That is the whole of the dedupe: two deliveries of the same bus message race on
 * {@code state = 'PENDING'} and exactly one of them wins, so a replayed DELETE cannot run twice, and a
 * node that answered before it restarted cannot answer again with a different verdict.</p>
 */
public final class DbExperienceCommandStore implements ExperienceCommandStore {

  /**
   * The {@code experience_commands} column list — this protocol's wire surface.
   *
   * <p>Public for the same reason {@link DbDrainStore#COLUMNS} is: {@code ProtocolContractTest}
   * digests it, so renaming a column a mixed-build fleet reads cannot happen quietly.</p>
   */
  public static final String COLUMNS =
      "id, experience_id, world_key, op, args, requested_by, target_node, state, detail,"
          + " deadline, created_at, updated_at";

  private final Database database;
  private final LoggerAdapter logger;

  public DbExperienceCommandStore(Database database, LoggerAdapter logger) {
    this.database = database;
    this.logger = logger;
  }

  @Override
  public boolean insert(Command command) {
    if (command == null || command.id() == null || command.id().isBlank()) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "INSERT INTO experience_commands (" + COLUMNS + ")"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        ps.setString(1, command.id());
        ps.setString(2, command.experienceId());
        ps.setString(3, command.worldKey());
        ps.setString(4, command.op());
        ps.setString(5, command.args());
        ps.setString(6, command.requestedBy());
        ps.setString(7, command.targetNode());
        ps.setString(8, command.state().name());
        ps.setString(9, command.detail());
        ps.setLong(10, command.deadline());
        ps.setLong(11, command.createdAt());
        ps.setLong(12, command.updatedAt());
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        // A request that cannot be written must NOT be published: the owner would be told their map
        // was deleted with nothing anywhere recording that anyone was ever asked.
        logger.severe("Could not record the " + command.op() + " of experience "
            + command.experienceId() + ": " + failed.getMessage());
        return false;
      }
    }
  }

  @Override
  public Optional<Command> byId(String id) {
    if (id == null) {
      return Optional.empty();
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM experience_commands WHERE id = ?")) {
        ps.setString(1, id);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(read(rs)) : Optional.empty();
        }
      } catch (SQLException failed) {
        logger.warning("Could not read experience command " + id + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  @Override
  public Optional<Command> claim(String id, String nodeId) {
    if (id == null || nodeId == null) {
      return Optional.empty();
    }
    synchronized (database.lock()) {
      long now = System.currentTimeMillis();
      // PENDING, or a RUNNING row this node abandoned long enough ago that the attempt holding it is
      // gone. Without the second half a node killed mid-delete leaves the request in RUNNING, which
      // neither the drain nor the expiry sweep looks at -- the delete is lost, and the owner was
      // already told it stands.
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE experience_commands SET state = ?, updated_at = ?"
              + " WHERE id = ? AND target_node = ?"
              + " AND (state = ? OR (state = ? AND updated_at < ?))")) {
        ps.setString(1, State.RUNNING.name());
        ps.setLong(2, now);
        ps.setString(3, id);
        ps.setString(4, nodeId);
        ps.setString(5, State.PENDING.name());
        ps.setString(6, State.RUNNING.name());
        ps.setLong(7, now - RECLAIM_AFTER_MILLIS);
        if (ps.executeUpdate() == 0) {
          return Optional.empty(); // somebody already has it, or it was never ours
        }
      } catch (SQLException failed) {
        logger.warning("Could not claim experience command " + id + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
    return byId(id);
  }

  @Override
  public boolean complete(String id, boolean ok, String detail) {
    if (id == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE experience_commands SET state = ?, detail = ?, updated_at = ?"
              + " WHERE id = ? AND state = ?")) {
        ps.setString(1, ok ? State.DONE.name() : State.FAILED.name());
        ps.setString(2, detail);
        ps.setLong(3, System.currentTimeMillis());
        ps.setString(4, id);
        ps.setString(5, State.RUNNING.name());
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not answer experience command " + id + ": " + failed.getMessage());
        return false;
      }
    }
  }

  @Override
  public boolean touch(String id, String nodeId, long now) {
    if (id == null || nodeId == null) {
      return false;
    }
    synchronized (database.lock()) {
      // RUNNING and still ours. A row somebody else has taken, or one that has already been answered,
      // is not ours to keep alive -- and re-stamping it would hide a genuinely abandoned attempt from
      // the reclaim window this column exists to feed.
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE experience_commands SET updated_at = ?"
              + " WHERE id = ? AND target_node = ? AND state = ?")) {
        ps.setLong(1, now);
        ps.setString(2, id);
        ps.setString(3, nodeId);
        ps.setString(4, State.RUNNING.name());
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        // Not fatal on its own: the next heartbeat re-stamps it, and the worst case is the row this
        // node is still working on being reclaimed by its own drain.
        logger.warning("Could not renew the claim on experience command " + id + ": "
            + failed.getMessage());
        return false;
      }
    }
  }

  @Override
  public boolean retarget(String id, String nodeId) {
    if (id == null || nodeId == null || nodeId.isBlank()) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE experience_commands SET target_node = ?, updated_at = ?"
              + " WHERE id = ? AND state = ?")) {
        ps.setString(1, nodeId);
        ps.setLong(2, System.currentTimeMillis());
        ps.setString(3, id);
        ps.setString(4, State.PENDING.name());
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not re-address experience command " + id + " to '" + nodeId + "': "
            + failed.getMessage());
        return false;
      }
    }
  }

  @Override
  public List<Command> pendingFor(String nodeId, long now) {
    List<Command> rows = new ArrayList<>();
    if (nodeId == null) {
      return rows;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM experience_commands"
              + " WHERE target_node = ? AND (state = ? OR (state = ? AND updated_at < ?))"
              + " ORDER BY created_at")) {
        ps.setString(1, nodeId);
        ps.setString(2, State.PENDING.name());
        ps.setString(3, State.RUNNING.name());
        ps.setLong(4, now - RECLAIM_AFTER_MILLIS);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            rows.add(read(rs));
          }
        }
      } catch (SQLException failed) {
        logger.warning("Could not read the experience commands addressed to '" + nodeId + "': "
            + failed.getMessage());
      }
    }
    return rows;
  }

  @Override
  public int expire(long now) {
    synchronized (database.lock()) {
      // RUNNING as well as PENDING: a node killed mid-delete leaves its row claimed, and a row nobody
      // will ever come back for has to reach a terminal state some way other than being read.
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE experience_commands SET state = ?, detail = ?, updated_at = ?"
              + " WHERE (state = ? OR state = ?) AND deadline > 0 AND deadline < ?")) {
        ps.setString(1, State.EXPIRED.name());
        ps.setString(2, "the node holding this world never answered");
        ps.setLong(3, now);
        ps.setString(4, State.PENDING.name());
        ps.setString(5, State.RUNNING.name());
        ps.setLong(6, now);
        return ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not expire stale experience commands: " + failed.getMessage());
        return 0;
      }
    }
  }

  private static Command read(ResultSet rs) throws SQLException {
    return new Command(
        rs.getString("id"),
        rs.getString("experience_id"),
        rs.getString("world_key"),
        rs.getString("op"),
        rs.getString("args"),
        rs.getString("requested_by"),
        rs.getString("target_node"),
        State.parse(rs.getString("state")),
        rs.getString("detail"),
        rs.getLong("deadline"),
        rs.getLong("created_at"),
        rs.getLong("updated_at"));
  }
}
