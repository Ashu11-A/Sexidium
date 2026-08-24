package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A minigame roster being assembled on one node from players arriving from others.
 *
 * <p>The problem this solves: {@code GameLauncher} needs every participant to be online in the same
 * JVM, but on a network the roster is scattered and arrives over the next second or two. Something
 * has to hold the match open for players still in flight, and then decide at a deadline whether
 * enough of them made it.</p>
 *
 * <p>The state machine is deliberately durable rather than in-memory:</p>
 *
 * <pre>
 *   RESERVED ──(proxy moves them)──▶ TRANSFERRING ──(they connect)──▶ ARRIVED
 *        └────────────────────(deadline passes)───────────────────▶ ABANDONED
 * </pre>
 *
 * <p><b>The row exists before the player does.</b> That is what makes a handoff survivable: a worker
 * that restarts mid-assembly can read what it was waiting for, and a player who never arrives is
 * resolved by the deadline rather than hanging the match forever.</p>
 */
public final class MatchHandoffService {

  public static final String STATE_RESERVED = "RESERVED";
  public static final String STATE_TRANSFERRING = "TRANSFERRING";
  public static final String STATE_ARRIVED = "ARRIVED";
  public static final String STATE_ABANDONED = "ABANDONED";

  public record Handoff(
      String matchId, UUID playerId, String nodeId, long nodeEpoch,
      String modeId, String worldKey, String state, long deadline) {
  }

  /** What the worker should do when the deadline arrives. */
  public record Assembly(String matchId, List<UUID> arrived, List<UUID> missing, boolean viable) {
  }

  private final Database database;
  private final LoggerAdapter logger;

  public MatchHandoffService(Database database, LoggerAdapter logger) {
    this.database = database;
    this.logger = logger;
  }

  /**
   * Reserve a roster on a node. Written as one batch so a capacity refusal can delete the whole
   * reservation rather than leaving half a match pending.
   */
  public void reserve(
      String matchId, Collection<UUID> roster, String nodeId, long nodeEpoch,
      String modeId, String worldKey, long deadline) {
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "INSERT INTO match_handoffs (match_id, player_uuid, node_id, node_epoch, mode_id,"
              + " world_key, state, deadline, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
        for (UUID playerId : roster) {
          ps.setString(1, matchId);
          ps.setString(2, playerId.toString());
          ps.setString(3, nodeId);
          ps.setLong(4, nodeEpoch);
          ps.setString(5, modeId);
          ps.setString(6, worldKey);
          ps.setString(7, STATE_RESERVED);
          ps.setLong(8, deadline);
          ps.setLong(9, now);
          ps.addBatch();
        }
        ps.executeBatch();
      } catch (SQLException failed) {
        logger.warning("Could not reserve match " + matchId + ": " + failed.getMessage());
      }
    }
  }

  // experienceHandoffId / isExperienceHandoff used to live here. An experience transfer was smuggled
  // through this table behind a synthetic "experience:" match id, so one table held two unrelated
  // things -- a minigame roster and a world rendezvous -- and the arrival gate told them apart with
  // String.startsWith. That is the proximate cause of the live transfer loop: the untargeted SELECT
  // below picked whichever of a player's rows came back first, and the two kinds mean entirely
  // different things. Experiences have a first-class TransferTicket now; this table is a roster again.

  /**
   * Expect one player on a node, replacing whatever was expected of them there before.
   *
   * <p>{@link #reserve} inserts and would collide on the primary key the second time a player enters
   * the same world — which is the normal case, not an edge one. Delete-then-insert keeps the row a
   * statement of the <em>current</em> intent, with a fresh deadline.</p>
   */
  public void expect(
      String matchId, UUID playerId, String nodeId, long nodeEpoch,
      String modeId, String worldKey, long deadline) {
    forget(matchId, playerId);
    reserve(matchId, List.of(playerId), nodeId, nodeEpoch, modeId, worldKey, deadline);
  }

  /** Drop one player's expectation, e.g. once it has been acted on at the destination. */
  public void forget(String matchId, UUID playerId) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "DELETE FROM match_handoffs WHERE match_id = ? AND player_uuid = ?")) {
        ps.setString(1, matchId);
        ps.setString(2, playerId.toString());
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not clear the handoff of " + playerId + ": " + failed.getMessage());
      }
    }
  }

  public void markTransferring(String matchId, UUID playerId) {
    setState(matchId, playerId, STATE_TRANSFERRING);
  }

  /** Record that a player actually connected to the hosting node. */
  public void markArrived(String matchId, UUID playerId) {
    setState(matchId, playerId, STATE_ARRIVED);
  }

  private void setState(String matchId, UUID playerId, String state) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE match_handoffs SET state = ? WHERE match_id = ? AND player_uuid = ?"
              + " AND state <> ?")) {
        ps.setString(1, state);
        ps.setString(2, matchId);
        ps.setString(3, playerId.toString());
        // Never move a player back out of ABANDONED: the match already decided without them.
        ps.setString(4, STATE_ABANDONED);
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not set handoff state for " + playerId + ": " + failed.getMessage());
      }
    }
  }

  /**
   * Which match on THIS node, if any, is expecting this player on arrival.
   *
   * <p>Addressed, ordered and bounded. It used to be
   * {@code WHERE player_uuid = ? AND state <> 'ABANDONED' AND deadline > ?} — no {@code node_id}, no
   * {@code node_epoch}, no {@code ORDER BY}, no {@code LIMIT 1} — taking whichever row came back
   * first. Live, a player standing on worker-2 would act on a handoff addressed to worker-1, ask the
   * placement gate, be told worker-1 owned the world, and be routed back: a loop that crossed a node
   * which was not even involved.</p>
   */
  public Optional<Handoff> expectedMatchOf(UUID playerId, String nodeId, long nodeEpoch) {
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT match_id, player_uuid, node_id, node_epoch, mode_id, world_key, state, deadline"
              + " FROM match_handoffs WHERE player_uuid = ? AND state <> ? AND deadline > ?"
              + " AND node_id = ? AND (node_epoch = 0 OR node_epoch = ?)"
              + " ORDER BY deadline DESC")) {
        ps.setString(1, playerId.toString());
        ps.setString(2, STATE_ABANDONED);
        ps.setLong(3, now);
        ps.setString(4, nodeId == null ? "" : nodeId);
        // A zero epoch on the ROW is "written before epochs were recorded" and is still accepted; a
        // zero epoch on OUR side is a standalone or not-yet-registered node, which matches anything.
        ps.setLong(5, nodeEpoch);
        try (ResultSet rs = ps.executeQuery()) {
          // Newest first, then exactly one: a stale row must never beat a fresh one, and a second
          // row must never be silently available to the next join.
          if (!rs.next()) {
            return Optional.empty();
          }
          return Optional.of(new Handoff(
              rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4),
              rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
        }
      } catch (SQLException | IllegalArgumentException failed) {
        logger.warning("Could not resolve the expected match of " + playerId + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  public List<Handoff> handoffs(String matchId) {
    List<Handoff> handoffs = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT match_id, player_uuid, node_id, node_epoch, mode_id, world_key, state, deadline"
              + " FROM match_handoffs WHERE match_id = ?")) {
        ps.setString(1, matchId);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            handoffs.add(new Handoff(
                rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getLong(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8)));
          }
        }
      } catch (SQLException | IllegalArgumentException failed) {
        logger.warning("Could not read handoffs for " + matchId + ": " + failed.getMessage());
      }
    }
    return handoffs;
  }

  /**
   * Decide a match at its deadline.
   *
   * <p>Proceeds with whoever arrived when that is at least {@code minPlayers} — matching
   * {@code GameLauncher}'s existing behaviour, which already re-sanitizes its roster and aborts
   * below the minimum. Everyone who did not arrive is marked ABANDONED so a late connection does not
   * walk into a match that started without them.</p>
   */
  public Assembly assemble(String matchId, int minPlayers) {
    List<UUID> arrived = new ArrayList<>();
    List<UUID> missing = new ArrayList<>();
    for (Handoff handoff : handoffs(matchId)) {
      if (STATE_ARRIVED.equals(handoff.state())) {
        arrived.add(handoff.playerId());
      } else {
        missing.add(handoff.playerId());
      }
    }
    for (UUID playerId : missing) {
      setStateForce(matchId, playerId, STATE_ABANDONED);
    }
    return new Assembly(matchId, List.copyOf(arrived), List.copyOf(missing), arrived.size() >= minPlayers);
  }

  private void setStateForce(String matchId, UUID playerId, String state) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE match_handoffs SET state = ? WHERE match_id = ? AND player_uuid = ?")) {
        ps.setString(1, state);
        ps.setString(2, matchId);
        ps.setString(3, playerId.toString());
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not abandon handoff for " + playerId + ": " + failed.getMessage());
      }
    }
  }

  /** Matches whose deadline has passed and which have not been decided yet. */
  public List<String> dueMatches() {
    long now = System.currentTimeMillis();
    List<String> due = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT DISTINCT match_id FROM match_handoffs WHERE deadline <= ? AND state <> ?")) {
        ps.setLong(1, now);
        ps.setString(2, STATE_ABANDONED);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            due.add(rs.getString(1));
          }
        }
      } catch (SQLException failed) {
        logger.warning("Could not list due matches: " + failed.getMessage());
      }
    }
    return due;
  }

  /** Drop a finished or cancelled match's rows. */
  public void clear(String matchId) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection()
          .prepareStatement("DELETE FROM match_handoffs WHERE match_id = ?")) {
        ps.setString(1, matchId);
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not clear match " + matchId + ": " + failed.getMessage());
      }
    }
  }
}
