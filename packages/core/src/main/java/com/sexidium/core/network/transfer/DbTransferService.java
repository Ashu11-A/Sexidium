package com.sexidium.core.network.transfer;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The whole transfer protocol, over {@code player_transfers} and {@code transfer_attempts}.
 *
 * <p>One class implements all four seams because they are four views of two tables, and splitting them
 * across four implementations would put the invariants in four places. What each view is for is
 * documented on its interface; what they share is here.</p>
 *
 * <p>The primary key is {@code player_uuid}. That is invariant I6 expressed in the schema rather than
 * in code: a player cannot have two live transfers, however many times anything asks.</p>
 */
public final class DbTransferService
    implements TransferDispatcher, TransferExecutor, ArrivalGate, TransferCircuitBreaker {

  /** Columns in the order every read below expects them. */
  private static final String COLUMNS =
      "player_uuid, token, source_node, target_node, target_epoch, reason, world_key, state,"
          + " attempts, claimed_by, expires_at, detail";

  private final Database database;
  private final LoggerAdapter logger;
  private final String nodeId;
  private final long ticketTtlMillis;
  private final int maxAttempts;
  private final long breakerWindowMillis;
  private final int breakerMaxPerNode;
  private final AtomicLong trips = new AtomicLong();

  public DbTransferService(Database database, LoggerAdapter logger, String nodeId,
      long ticketTtlMillis, int maxAttempts, long breakerWindowMillis, int breakerMaxPerNode) {
    this.database = database;
    this.logger = logger;
    this.nodeId = nodeId == null ? "standalone" : nodeId;
    this.ticketTtlMillis = ticketTtlMillis;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.breakerWindowMillis = Math.max(1_000L, breakerWindowMillis);
    this.breakerMaxPerNode = Math.max(1, breakerMaxPerNode);
  }

  // ===== TransferDispatcher ====================================================================

  @Override
  public Optional<TransferTicket> request(UUID playerId, String targetNode, long targetEpoch,
      TransferReason reason, String worldKey) {
    if (playerId == null || targetNode == null || targetNode.isBlank()) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    // AN EXIT IS NEVER REFUSED, and is never counted.
    //
    // Refusing to let a player LEAVE cannot break a loop — a loop needs somewhere to send them, and
    // the lobby is where a stuck player is trying to get back to. It can only strand them, and it did:
    // once the (player, lobby) window filled, `/leave` on a worker had nowhere to route, the worker
    // has no lobby world of its own to fall back to, and every attempt answered "You are not in a
    // match" while the player sat on a node they could not get off.
    //
    // Bounding the ENTRY leg alone is sufficient: a ping-pong needs both halves, so stopping the
    // inbound one stops the loop and leaves the player somewhere they can act.
    boolean bounded = reason != TransferReason.LOBBY;
    synchronized (database.lock()) {
      Optional<TransferTicket> existing = read(playerId, now);
      if (existing.isPresent() && existing.get().state().live()) {
        TransferTicket live = existing.get();
        if (live.sameDestination(targetNode, reason, worldKey)) {
          // IDEMPOTENT. Re-asking for a move that is already in flight returns the same ticket and
          // moves nobody. This one rule collapses the observed eight transfers into one, because the
          // lobby re-running the entry no longer produces a second, indistinguishable intent.
          return existing;
        }
        // A DIFFERENT destination while one is live is the shape of a ping-pong, so the breaker
        // decides rather than the newest intent simply winning.
        if (bounded && !allow(playerId, targetNode)) {
          return Optional.empty();
        }
      } else if (bounded && !allow(playerId, targetNode)) {
        return Optional.empty();
      }

      TransferTicket ticket = new TransferTicket(
          UUID.randomUUID().toString(), playerId, nodeId, targetNode, targetEpoch, reason, worldKey,
          TransferState.PENDING, 0, null, now + ticketTtlMillis, null);
      if (!upsert(ticket, now)) {
        return Optional.empty();
      }
      if (bounded) {
        recordAttempt(playerId, targetNode, now);
      }
      return Optional.of(ticket);
    }
  }

  @Override
  public Optional<TransferTicket> statusOf(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM player_transfers WHERE token = ?")) {
        ps.setString(1, token);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
        }
      } catch (SQLException | IllegalArgumentException failed) {
        logger.warning("Could not read transfer " + token + ": " + failed.getMessage());
        return Optional.empty();
      }
    }
  }

  @Override
  public void cancel(UUID playerId) {
    if (playerId == null) {
      return;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection()
          .prepareStatement("DELETE FROM player_transfers WHERE player_uuid = ?")) {
        ps.setString(1, playerId.toString());
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not cancel the transfer of " + playerId + ": " + failed.getMessage());
      }
    }
  }

  @Override
  public boolean inFlight(UUID playerId) {
    if (playerId == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      return read(playerId, now).filter(ticket -> ticket.state().live()).isPresent();
    }
  }

  // ===== TransferExecutor ======================================================================

  @Override
  public List<TransferTicket> claim(String executorId, int max, long leaseMillis) {
    long now = System.currentTimeMillis();
    List<TransferTicket> claimed = new ArrayList<>();
    synchronized (database.lock()) {
      expireOverdue(now);
      List<TransferTicket> candidates = actionable(now, Math.max(1, max));
      for (TransferTicket candidate : candidates) {
        // A GUARDED update, not a delete. Whoever wins the CAS owns the ticket for leaseMillis; a
        // proxy that dies holding one leaves it reclaimable rather than losing the player's intent.
        try (PreparedStatement ps = database.connection().prepareStatement(
            "UPDATE player_transfers SET state = ?, claimed_by = ?, claim_expires_at = ?,"
                + " attempts = attempts + 1, updated_at = ?"
                + " WHERE token = ? AND claim_expires_at <= ? AND state IN (?, ?)")) {
          ps.setString(1, TransferState.DISPATCHED.name());
          ps.setString(2, executorId);
          // The caller sizes the lease; it knows its own poll interval. Clamped only above zero,
          // because a zero lease would make a claim instantly stealable by the next poller.
          ps.setLong(3, now + Math.max(1L, leaseMillis));
          ps.setLong(4, now);
          ps.setString(5, candidate.token());
          ps.setLong(6, now);
          ps.setString(7, TransferState.PENDING.name());
          ps.setString(8, TransferState.DISPATCHED.name());
          if (ps.executeUpdate() > 0) {
            claimed.add(new TransferTicket(candidate.token(), candidate.playerId(),
                candidate.sourceNode(), candidate.targetNode(), candidate.targetEpoch(),
                candidate.reason(), candidate.worldKey(), TransferState.DISPATCHED,
                candidate.attempts() + 1, executorId, candidate.expiresAt(), candidate.detail()));
          }
        } catch (SQLException failed) {
          logger.warning("Could not claim transfer " + candidate.token() + ": " + failed.getMessage());
        }
      }
    }
    return claimed;
  }

  @Override
  public void complete(String token, TransferState terminal, String detail) {
    if (token == null || terminal == null || !terminal.terminal()) {
      return;
    }
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      Optional<TransferTicket> ticket = statusOfLocked(token);
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE player_transfers SET state = ?, claimed_by = NULL, claim_expires_at = 0,"
              + " detail = ?, updated_at = ? WHERE token = ?")) {
        ps.setString(1, terminal.name());
        ps.setString(2, detail);
        ps.setLong(3, now);
        ps.setString(4, token);
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not complete transfer " + token + ": " + failed.getMessage());
      }
      ticket.ifPresent(value -> observe(value.playerId(), value.targetNode(), terminal));
    }
  }

  @Override
  public void retry(String token, String detail) {
    if (token == null) {
      return;
    }
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      Optional<TransferTicket> ticket = statusOfLocked(token);
      if (ticket.isEmpty()) {
        return;
      }
      if (ticket.get().attempts() >= maxAttempts) {
        // Bounded retry. Without a bound "keep trying" is the same failure the loop breaker exists
        // for, one layer down.
        complete(token, TransferState.FAILED,
            "gave up after " + ticket.get().attempts() + " attempt(s): " + detail);
        return;
      }
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE player_transfers SET state = ?, claimed_by = NULL, claim_expires_at = 0,"
              + " detail = ?, updated_at = ? WHERE token = ?")) {
        ps.setString(1, TransferState.PENDING.name());
        ps.setString(2, detail);
        ps.setLong(3, now);
        ps.setString(4, token);
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Could not requeue transfer " + token + ": " + failed.getMessage());
      }
    }
  }

  // ===== ArrivalGate ===========================================================================

  @Override
  public Optional<TransferTicket> claimArrival(UUID playerId, String myNode, long myEpoch) {
    if (playerId == null || myNode == null) {
      return Optional.empty();
    }
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      Optional<TransferTicket> ticket = read(playerId, now);
      if (ticket.isEmpty()) {
        return Optional.empty();
      }
      TransferTicket found = ticket.get();
      if (!found.addressedTo(myNode, myEpoch)) {
        // NOT ours. Acting on a ticket addressed elsewhere is how a player on worker-2 acted on a
        // handoff meant for worker-1, asked the gate, was told worker-1 owned it, and was routed
        // back -- a loop that crossed a node that was not even involved.
        logger.info("Ignoring the transfer of " + playerId + ": it is addressed to '"
            + found.targetNode() + "' epoch " + found.targetEpoch() + ", not to '" + myNode
            + "' epoch " + myEpoch + ".");
        return Optional.empty();
      }
      if (!found.state().live()) {
        // Already consumed, already failed, or timed out unread. A stale expectation re-firing on a
        // later join is how a player gets dragged into a world they have left.
        return Optional.empty();
      }
      // Guarded on the token, so two concurrent claimers cannot both win.
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE player_transfers SET state = ?, claimed_by = NULL, claim_expires_at = 0,"
              + " detail = ?, updated_at = ? WHERE token = ? AND state <> ?")) {
        ps.setString(1, TransferState.LANDED.name());
        ps.setString(2, "arrived on " + myNode);
        ps.setLong(3, now);
        ps.setString(4, found.token());
        ps.setString(5, TransferState.LANDED.name());
        if (ps.executeUpdate() == 0) {
          return Optional.empty();
        }
      } catch (SQLException failed) {
        logger.warning("Could not claim the arrival of " + playerId + ": " + failed.getMessage());
        return Optional.empty();
      }
      observe(playerId, found.targetNode(), TransferState.LANDED);
      return Optional.of(new TransferTicket(found.token(), found.playerId(), found.sourceNode(),
          found.targetNode(), found.targetEpoch(), found.reason(), found.worldKey(),
          TransferState.LANDED, found.attempts(), null, found.expiresAt(), found.detail()));
    }
  }

  // ===== TransferCircuitBreaker ================================================================

  @Override
  public boolean allow(UUID playerId, String targetNode) {
    if (playerId == null || targetNode == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT window_start, attempts, blocked_until FROM transfer_attempts"
              + " WHERE player_uuid = ? AND target_node = ?")) {
        ps.setString(1, playerId.toString());
        ps.setString(2, targetNode);
        try (ResultSet rs = ps.executeQuery()) {
          if (!rs.next()) {
            return true;
          }
          long windowStart = rs.getLong(1);
          int attempts = rs.getInt(2);
          long blockedUntil = rs.getLong(3);
          if (blockedUntil > now) {
            return false;
          }
          if (now - windowStart >= breakerWindowMillis) {
            return true; // the window has rolled over
          }
          return attempts < breakerMaxPerNode;
        }
      } catch (SQLException failed) {
        // A breaker that cannot read must not become a breaker that refuses everything: a database
        // blip would then strand every player on whatever node they happened to be on.
        logger.warning("Could not read the transfer breaker for " + playerId + ": " + failed.getMessage());
        return true;
      }
    }
  }

  @Override
  public void observe(UUID playerId, String targetNode, TransferState outcome) {
    if (playerId == null || targetNode == null || outcome == null || !outcome.terminal()) {
      return;
    }
    // Deliberately NOT "a LANDED transfer clears the window".
    //
    // That looks obviously right and is wrong: in the loop this breaker was built for, BOTH legs
    // landed. The player really did connect to the worker, the experience really did start, and they
    // were then ejected -- so forgiving a successful landing would forgive the exact shape being
    // guarded against. Nor can rate tell the two apart: the observed loop ran at ~11 transfers/minute
    // and a person testing entries by hand runs at about the same.
    //
    // What separates them is DIRECTION, and that is handled in allow(): an entry is bounded, an exit
    // never is (see below).
  }

  @Override
  public long trips() {
    return trips.get();
  }

  /** Counts one attempt in the rolling window, and trips the breaker when the bound is reached. */
  private void recordAttempt(UUID playerId, String targetNode, long now) {
    long windowStart = now;
    int attempts = 1;
    try (PreparedStatement ps = database.connection().prepareStatement(
        "SELECT window_start, attempts FROM transfer_attempts"
            + " WHERE player_uuid = ? AND target_node = ?")) {
      ps.setString(1, playerId.toString());
      ps.setString(2, targetNode);
      try (ResultSet rs = ps.executeQuery()) {
        if (rs.next()) {
          long storedStart = rs.getLong(1);
          if (now - storedStart < breakerWindowMillis) {
            windowStart = storedStart;
            attempts = rs.getInt(2) + 1;
          }
        }
      }
    } catch (SQLException failed) {
      logger.warning("Could not read the transfer window for " + playerId + ": " + failed.getMessage());
    }
    long blockedUntil = attempts >= breakerMaxPerNode ? windowStart + breakerWindowMillis : 0L;
    if (blockedUntil > 0L) {
      trips.incrementAndGet();
      logger.severe("TRANSFER LOOP BREAKER: " + playerId + " has been sent from '" + nodeId + "' to '"
          + targetNode + "' " + attempts + " time(s) in " + (breakerWindowMillis / 1000)
          + "s. Refusing further transfers to that node until "
          + (blockedUntil - now) / 1000 + "s from now. Something is bouncing this player; check the"
          + " placement row for their world and both nodes' logs.");
    }
    try (PreparedStatement ps = database.connection().prepareStatement(
        database.dialect().upsert("transfer_attempts",
            new String[] {"player_uuid", "target_node", "window_start", "attempts", "blocked_until"},
            new String[] {"player_uuid", "target_node"},
            new String[] {"window_start", "attempts", "blocked_until"}))) {
      ps.setString(1, playerId.toString());
      ps.setString(2, targetNode);
      ps.setLong(3, windowStart);
      ps.setInt(4, attempts);
      ps.setLong(5, blockedUntil);
      ps.executeUpdate();
    } catch (SQLException failed) {
      logger.warning("Could not record a transfer attempt for " + playerId + ": " + failed.getMessage());
    }
  }

  // ===== internals =============================================================================

  private Optional<TransferTicket> statusOfLocked(String token) {
    try (PreparedStatement ps = database.connection().prepareStatement(
        "SELECT " + COLUMNS + " FROM player_transfers WHERE token = ?")) {
      ps.setString(1, token);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(readRow(rs)) : Optional.empty();
      }
    } catch (SQLException | IllegalArgumentException failed) {
      return Optional.empty();
    }
  }

  /** The one live row for a player, with an expiry that has passed reported as EXPIRED. */
  private Optional<TransferTicket> read(UUID playerId, long now) {
    try (PreparedStatement ps = database.connection().prepareStatement(
        "SELECT " + COLUMNS + " FROM player_transfers WHERE player_uuid = ?")) {
      ps.setString(1, playerId.toString());
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        TransferTicket ticket = readRow(rs);
        if (ticket.state().live() && ticket.expiresAt() <= now) {
          return Optional.of(new TransferTicket(ticket.token(), ticket.playerId(),
              ticket.sourceNode(), ticket.targetNode(), ticket.targetEpoch(), ticket.reason(),
              ticket.worldKey(), TransferState.EXPIRED, ticket.attempts(), null,
              ticket.expiresAt(), ticket.detail()));
        }
        return Optional.of(ticket);
      }
    } catch (SQLException | IllegalArgumentException failed) {
      logger.warning("Could not read the transfer of " + playerId + ": " + failed.getMessage());
      return Optional.empty();
    }
  }

  private List<TransferTicket> actionable(long now, int max) {
    List<TransferTicket> tickets = new ArrayList<>();
    try (PreparedStatement ps = database.connection().prepareStatement(
        "SELECT " + COLUMNS + " FROM player_transfers"
            + " WHERE state IN (?, ?) AND expires_at > ? AND claim_expires_at <= ?"
            + " ORDER BY created_at")) {
      ps.setString(1, TransferState.PENDING.name());
      ps.setString(2, TransferState.DISPATCHED.name());
      ps.setLong(3, now);
      ps.setLong(4, now);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next() && tickets.size() < max) {
          tickets.add(readRow(rs));
        }
      }
    } catch (SQLException | IllegalArgumentException failed) {
      logger.warning("Could not list actionable transfers: " + failed.getMessage());
    }
    return tickets;
  }

  private void expireOverdue(long now) {
    try (PreparedStatement ps = database.connection().prepareStatement(
        "UPDATE player_transfers SET state = ?, claimed_by = NULL, claim_expires_at = 0,"
            + " detail = ?, updated_at = ? WHERE expires_at <= ? AND state IN (?, ?)")) {
      ps.setString(1, TransferState.EXPIRED.name());
      ps.setString(2, "nobody acted on it before it expired");
      ps.setLong(3, now);
      ps.setLong(4, now);
      ps.setString(5, TransferState.PENDING.name());
      ps.setString(6, TransferState.DISPATCHED.name());
      ps.executeUpdate();
    } catch (SQLException failed) {
      logger.warning("Could not expire overdue transfers: " + failed.getMessage());
    }
  }

  private boolean upsert(TransferTicket ticket, long now) {
    try (PreparedStatement ps = database.connection().prepareStatement(
        database.dialect().upsert("player_transfers",
            new String[] {"player_uuid", "token", "source_node", "target_node", "target_epoch",
                "reason", "world_key", "state", "attempts", "claimed_by", "claim_expires_at",
                "created_at", "expires_at", "updated_at", "detail"},
            new String[] {"player_uuid"},
            new String[] {"token", "source_node", "target_node", "target_epoch", "reason",
                "world_key", "state", "attempts", "claimed_by", "claim_expires_at", "created_at",
                "expires_at", "updated_at", "detail"}))) {
      ps.setString(1, ticket.playerId().toString());
      ps.setString(2, ticket.token());
      ps.setString(3, ticket.sourceNode());
      ps.setString(4, ticket.targetNode());
      ps.setLong(5, ticket.targetEpoch());
      ps.setString(6, ticket.reason().name());
      ps.setString(7, ticket.worldKey());
      ps.setString(8, ticket.state().name());
      ps.setInt(9, ticket.attempts());
      ps.setString(10, null);
      ps.setLong(11, 0L);
      ps.setLong(12, now);
      ps.setLong(13, ticket.expiresAt());
      ps.setLong(14, now);
      ps.setString(15, ticket.detail());
      ps.executeUpdate();
      return true;
    } catch (SQLException failed) {
      logger.warning("Could not write a transfer for " + ticket.playerId() + ": " + failed.getMessage());
      return false;
    }
  }

  private static TransferTicket readRow(ResultSet rs) throws SQLException {
    return new TransferTicket(
        rs.getString(2),
        UUID.fromString(rs.getString(1)),
        rs.getString(3),
        rs.getString(4),
        rs.getLong(5),
        TransferReason.parse(rs.getString(6)),
        rs.getString(7),
        TransferState.parse(rs.getString(8)),
        rs.getInt(9),
        rs.getString(10),
        rs.getLong(11),
        rs.getString(12));
  }

  /** Every live ticket, newest first. For the admin surface and the janitor. */
  public List<TransferTicket> inFlight() {
    long now = System.currentTimeMillis();
    List<TransferTicket> tickets = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM player_transfers WHERE state IN (?, ?) AND expires_at > ?"
              + " ORDER BY created_at DESC")) {
        ps.setString(1, TransferState.PENDING.name());
        ps.setString(2, TransferState.DISPATCHED.name());
        ps.setLong(3, now);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            tickets.add(readRow(rs));
          }
        }
      } catch (SQLException | IllegalArgumentException failed) {
        logger.warning("Could not list in-flight transfers: " + failed.getMessage());
      }
    }
    return tickets;
  }
}
