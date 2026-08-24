package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Party / group membership, shared across nodes.
 *
 * <p>The narrow, load-bearing half of a DB-backed lobby: <b>who is in a group with whom</b>, which is
 * what has to survive a player being transferred to another node. Without it a party dissolves the
 * moment one member is routed to a worker, because membership lives only in the originating JVM's
 * {@code LobbyContext} maps.</p>
 *
 * <p>Deliberately <b>not</b> the whole of {@code LobbyContext}. The countdown state machine stays
 * where it is: it is single-writer by design, ticked once a second by whichever node holds the
 * matchmaker lease, and re-deriving it from rows every tick would be slower and racier than the
 * in-memory version. This class stores the membership facts; the tick keeps owning the timing.</p>
 *
 * <p>{@code lobbyId} is the leader's UUID, matching the existing in-memory model, so this can be
 * populated alongside {@code LobbyContext} without changing how a lobby is identified.</p>
 */
public final class LobbyDirectory {

  public record Member(UUID playerId, String playerName, int selectedTeam) {
  }

  public record Group(
      UUID lobbyId, UUID leaderId, String modeId, String state, String visibility,
      int teamCount, String nodeId) {
  }

  private final Database database;
  private final LoggerAdapter logger;

  public LobbyDirectory(Database database, LoggerAdapter logger) {
    this.database = database;
    this.logger = logger;
  }

  /** Create or update a group. */
  public void upsertGroup(Group group) {
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      try {
        try (PreparedStatement update = database.connection().prepareStatement(
            "UPDATE lobby_groups SET leader_uuid = ?, mode_id = ?, state = ?, visibility = ?,"
                + " team_count = ?, node_id = ?, updated_at = ? WHERE lobby_id = ?")) {
          update.setString(1, group.leaderId().toString());
          update.setString(2, group.modeId());
          update.setString(3, group.state());
          update.setString(4, group.visibility());
          update.setInt(5, group.teamCount());
          update.setString(6, group.nodeId());
          update.setLong(7, now);
          update.setString(8, group.lobbyId().toString());
          if (update.executeUpdate() > 0) {
            return;
          }
        }
        try (PreparedStatement insert = database.connection().prepareStatement(
            "INSERT INTO lobby_groups (lobby_id, leader_uuid, mode_id, state, visibility,"
                + " team_count, node_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
          insert.setString(1, group.lobbyId().toString());
          insert.setString(2, group.leaderId().toString());
          insert.setString(3, group.modeId());
          insert.setString(4, group.state());
          insert.setString(5, group.visibility());
          insert.setInt(6, group.teamCount());
          insert.setString(7, group.nodeId());
          insert.setLong(8, now);
          insert.setLong(9, now);
          insert.executeUpdate();
        }
      } catch (SQLException failed) {
        logger.warning("Could not persist lobby group " + group.lobbyId() + ": " + failed.getMessage());
      }
    }
  }

  /**
   * Add a player to a group.
   *
   * <p>A player belongs to at most one group, so joining removes them from any other first. Without
   * that a stale membership row makes them appear in two parties at once — and the reverse lookup
   * {@link #groupOf} would then return whichever the database happened to order first.</p>
   */
  public void addMember(UUID lobbyId, UUID playerId, String playerName, int selectedTeam) {
    synchronized (database.lock()) {
      try {
        try (PreparedStatement clear = database.connection()
            .prepareStatement("DELETE FROM lobby_members WHERE player_uuid = ? AND lobby_id <> ?")) {
          clear.setString(1, playerId.toString());
          clear.setString(2, lobbyId.toString());
          clear.executeUpdate();
        }
        try (PreparedStatement update = database.connection().prepareStatement(
            "UPDATE lobby_members SET player_name = ?, selected_team = ?"
                + " WHERE lobby_id = ? AND player_uuid = ?")) {
          update.setString(1, playerName);
          update.setInt(2, selectedTeam);
          update.setString(3, lobbyId.toString());
          update.setString(4, playerId.toString());
          if (update.executeUpdate() > 0) {
            return;
          }
        }
        try (PreparedStatement insert = database.connection().prepareStatement(
            "INSERT INTO lobby_members (lobby_id, player_uuid, player_name, selected_team, joined_at)"
                + " VALUES (?, ?, ?, ?, ?)")) {
          insert.setString(1, lobbyId.toString());
          insert.setString(2, playerId.toString());
          insert.setString(3, playerName);
          insert.setInt(4, selectedTeam);
          insert.setLong(5, System.currentTimeMillis());
          insert.executeUpdate();
        }
      } catch (SQLException failed) {
        logger.warning("Could not add " + playerId + " to lobby " + lobbyId + ": " + failed.getMessage());
      }
    }
  }

  public void removeMember(UUID lobbyId, UUID playerId) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection()
          .prepareStatement("DELETE FROM lobby_members WHERE lobby_id = ? AND player_uuid = ?")) {
        ps.setString(1, lobbyId.toString());
        ps.setString(2, playerId.toString());
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not remove " + playerId + " from lobby " + lobbyId + ": " + failed.getMessage());
      }
    }
  }

  /** Delete a group and everyone in it. */
  public void removeGroup(UUID lobbyId) {
    synchronized (database.lock()) {
      try (PreparedStatement members = database.connection()
               .prepareStatement("DELETE FROM lobby_members WHERE lobby_id = ?");
           PreparedStatement group = database.connection()
               .prepareStatement("DELETE FROM lobby_groups WHERE lobby_id = ?");
           PreparedStatement ticket = database.connection()
               .prepareStatement("DELETE FROM lobby_queue_tickets WHERE lobby_id = ?")) {
        members.setString(1, lobbyId.toString());
        members.executeUpdate();
        group.setString(1, lobbyId.toString());
        group.executeUpdate();
        ticket.setString(1, lobbyId.toString());
        ticket.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not remove lobby " + lobbyId + ": " + failed.getMessage());
      }
    }
  }

  public List<Member> members(UUID lobbyId) {
    List<Member> members = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          // player_uuid breaks the tie: two members added in the same millisecond would otherwise
          // come back in an order the database chose, which made a positional assertion flaky.
          "SELECT player_uuid, player_name, selected_team FROM lobby_members"
              + " WHERE lobby_id = ? ORDER BY joined_at, player_uuid")) {
        ps.setString(1, lobbyId.toString());
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            members.add(new Member(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getInt(3)));
          }
        }
      } catch (SQLException | IllegalArgumentException failed) {
        logger.warning("Could not read members of " + lobbyId + ": " + failed.getMessage());
      }
    }
    return members;
  }

  /** The reverse index: which group is this player in, from any node. */
  public Optional<UUID> groupOf(UUID playerId) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection()
          .prepareStatement("SELECT lobby_id FROM lobby_members WHERE player_uuid = ?")) {
        ps.setString(1, playerId.toString());
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
        }
      } catch (SQLException | IllegalArgumentException failed) {
        logger.warning("Could not resolve the group of " + playerId + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  public Optional<Group> group(UUID lobbyId) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT lobby_id, leader_uuid, mode_id, state, visibility, team_count, node_id"
              + " FROM lobby_groups WHERE lobby_id = ?")) {
        ps.setString(1, lobbyId.toString());
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return Optional.empty();
          }
          return Optional.of(new Group(
              UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
              rs.getString(4), rs.getString(5), rs.getInt(6), rs.getString(7)));
        }
      } catch (SQLException | IllegalArgumentException failed) {
        logger.warning("Could not read lobby " + lobbyId + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  /** True when two players are in the same group — the question a worker asks about an experience. */
  public boolean sameGroup(UUID first, UUID second) {
    Optional<UUID> firstGroup = groupOf(first);
    return firstGroup.isPresent() && firstGroup.equals(groupOf(second));
  }
}
