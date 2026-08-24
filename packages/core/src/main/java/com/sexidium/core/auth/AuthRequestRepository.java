package com.sexidium.core.auth;

import com.sexidium.core.lib.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@code auth_requests}: one pending "is this you?" push, end to end.
 *
 * <p>The row is written by the node holding the connection (the proxy, or a backend during a hold),
 * claimed by the {@code BOT_HOST} node's courier, decided by the bot through the RPC bridge, and
 * observed again by the waiting node. Claim-then-complete, the same shape {@code player_transfers}
 * uses and for the same reason: a courier that dies mid-notification must free the row instead of
 * swallowing it.</p>
 */
public final class AuthRequestRepository {

  public static final String STATE_PENDING = "pending";
  public static final String STATE_NOTIFIED = "notified";
  public static final String STATE_APPROVED = "approved";
  public static final String STATE_DENIED = "denied";
  public static final String STATE_EXPIRED = "expired";
  public static final String STATE_CONSUMED = "consumed";

  public static final String KIND_SESSION = "session";
  public static final String KIND_PREMIUM_CONFLICT = "premium-conflict";

  /** One request row. {@code hold} records whether a player is being frozen in-world waiting on it. */
  public record RequestRow(
      String requestId,
      String identityId,
      String nameLower,
      String displayName,
      String discordUserId,
      String ipHash,
      String ipPrefix,
      String kind,
      String state,
      boolean hold,
      String waitingNode,
      String claimedBy,
      long claimExpiresAt,
      Long notifiedAt,
      Long decidedAt,
      String decidedBy,
      int attempts,
      long createdAt,
      long expiresAt,
      String detail) {

    public boolean live(long now) {
      return expiresAt > now && (STATE_PENDING.equals(state) || STATE_NOTIFIED.equals(state));
    }

    /** Whether this is the first request ever recorded for this identity's network. */
    public boolean firstSeen() {
      return attempts <= 1;
    }
  }

  private static final String COLUMNS =
      "request_id, identity_id, name_lower, display_name, discord_user_id, ip_hash, ip_prefix, kind,"
          + " state, hold, waiting_node, claimed_by, claim_expires_at, notified_at, decided_at,"
          + " decided_by, attempts, created_at, expires_at, detail";

  private final Database db;

  public AuthRequestRepository(Database db) {
    this.db = db;
  }

  public void insert(RequestRow row) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          INSERT INTO auth_requests(request_id, identity_id, name_lower, display_name,
                                    discord_user_id, ip_hash, ip_prefix, kind, state, hold,
                                    waiting_node, claimed_by, claim_expires_at, notified_at,
                                    decided_at, decided_by, attempts, created_at, expires_at, detail)
          VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, 0, NULL, NULL, NULL, ?, ?, ?, ?)""")) {
        ps.setString(1, row.requestId());
        ps.setString(2, row.identityId());
        ps.setString(3, row.nameLower());
        ps.setString(4, row.displayName());
        ps.setString(5, row.discordUserId());
        ps.setString(6, row.ipHash());
        ps.setString(7, row.ipPrefix());
        ps.setString(8, row.kind());
        ps.setString(9, row.state());
        ps.setLong(10, row.hold() ? 1L : 0L);
        ps.setString(11, row.waitingNode());
        ps.setInt(12, row.attempts());
        ps.setLong(13, row.createdAt());
        ps.setLong(14, row.expiresAt());
        ps.setString(15, row.detail());
        ps.executeUpdate();
      }
    }
  }

  public RequestRow byId(String requestId) throws SQLException {
    if (requestId == null || requestId.isBlank()) {
      return null;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM auth_requests WHERE request_id = ? LIMIT 1")) {
        ps.setString(1, requestId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? read(rs) : null;
        }
      }
    }
  }

  /**
   * A live pending/notified request for this exact (identity, network), if one is already open.
   *
   * <p>Reused rather than duplicated, which is the anti-spam measure that matters: reconnecting ten
   * times must send one Discord message, not ten.</p>
   */
  public RequestRow findLivePending(String identityId, String ipHash, long now) throws SQLException {
    if (identityId == null || ipHash == null) {
      return null;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM auth_requests"
              + " WHERE identity_id = ? AND ip_hash = ? AND state IN ('pending', 'notified')"
              + " AND expires_at > ? ORDER BY created_at DESC LIMIT 1")) {
        ps.setString(1, identityId);
        ps.setString(2, ipHash);
        ps.setLong(3, now);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? read(rs) : null;
        }
      }
    }
  }

  /** The newest hold-flagged request for an identity, whatever its state — the backend's release cue. */
  public RequestRow pendingHoldFor(String identityId, long now) throws SQLException {
    if (identityId == null) {
      return null;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM auth_requests"
              + " WHERE identity_id = ? AND hold = 1 AND expires_at > ?"
              + " ORDER BY created_at DESC LIMIT 1")) {
        ps.setString(1, identityId);
        ps.setLong(2, now);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? read(rs) : null;
        }
      }
    }
  }

  /**
   * Take ownership of up to {@code limit} un-notified requests for {@code leaseMillis}.
   *
   * <p>Select-then-conditional-UPDATE, guarded on the claim we read, so two couriers racing over one
   * row leave exactly one winner and the loser simply sees zero rows updated. A courier that dies
   * before marking the row notified lets the lease lapse and someone else picks it up — which is why
   * the notifier throwing is not a lost request.</p>
   */
  public List<RequestRow> claimPending(String claimant, int limit, long now, long leaseMillis)
      throws SQLException {
    List<RequestRow> claimed = new ArrayList<>();
    List<RequestRow> candidates = new ArrayList<>();
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM auth_requests"
              + " WHERE state = ? AND claim_expires_at < ? AND expires_at > ?"
              + " ORDER BY created_at LIMIT " + Math.max(1, limit))) {
        ps.setString(1, STATE_PENDING);
        ps.setLong(2, now);
        ps.setLong(3, now);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            candidates.add(read(rs));
          }
        }
      }
    }
    for (RequestRow candidate : candidates) {
      synchronized (db.lock()) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
            UPDATE auth_requests
            SET claimed_by = ?, claim_expires_at = ?, attempts = attempts + 1
            WHERE request_id = ? AND state = ? AND claim_expires_at = ?""")) {
          ps.setString(1, claimant);
          ps.setLong(2, now + Math.max(1_000L, leaseMillis));
          ps.setString(3, candidate.requestId());
          ps.setString(4, STATE_PENDING);
          ps.setLong(5, candidate.claimExpiresAt());
          if (ps.executeUpdate() > 0) {
            claimed.add(candidate);
          }
        }
      }
    }
    return claimed;
  }

  /** The Discord message went out. Stops the courier re-sending it on the next poll. */
  public boolean markNotified(String requestId, long now) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE auth_requests
          SET state = ?, notified_at = ?
          WHERE request_id = ? AND state = ?""")) {
        ps.setString(1, STATE_NOTIFIED);
        ps.setLong(2, now);
        ps.setString(3, requestId);
        ps.setString(4, STATE_PENDING);
        return ps.executeUpdate() > 0;
      }
    }
  }

  /**
   * Approve or deny, once.
   *
   * <p>The {@code state IN ('pending','notified')} predicate is the whole replay defence: a second
   * press of the same button affects zero rows and the caller reports {@code already-decided}.
   * Stripping the buttons from the Discord message is cosmetic next to this.</p>
   */
  public boolean decide(String requestId, String state, String decidedBy, long now) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE auth_requests
          SET state = ?, decided_at = ?, decided_by = ?
          WHERE request_id = ? AND state IN ('pending', 'notified')""")) {
        ps.setString(1, state);
        ps.setLong(2, now);
        ps.setString(3, decidedBy);
        ps.setString(4, requestId);
        return ps.executeUpdate() > 0;
      }
    }
  }

  /** Mark a decided request as acted upon, so a held player is not released twice. */
  public boolean consume(String requestId, long now) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE auth_requests
          SET state = ?, decided_at = COALESCE(decided_at, ?)
          WHERE request_id = ? AND state IN ('approved', 'denied')""")) {
        ps.setString(1, STATE_CONSUMED);
        ps.setLong(2, now);
        ps.setString(3, requestId);
        return ps.executeUpdate() > 0;
      }
    }
  }

  /** Time out anything nobody answered, then delete what is long past being interesting. */
  public int expireStale(long now, long retentionMillis) throws SQLException {
    int touched;
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE auth_requests
          SET state = ?
          WHERE expires_at < ? AND state IN ('pending', 'notified')""")) {
        ps.setString(1, STATE_EXPIRED);
        ps.setLong(2, now);
        touched = ps.executeUpdate();
      }
      try (PreparedStatement ps = db.connection().prepareStatement(
          "DELETE FROM auth_requests WHERE expires_at < ?")) {
        ps.setLong(1, now - Math.max(0L, retentionMillis));
        ps.executeUpdate();
      }
    }
    return touched;
  }

  /** Drop every request of one identity — part of the unlink cascade. */
  public int deleteByIdentity(String identityId) throws SQLException {
    if (identityId == null) {
      return 0;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "DELETE FROM auth_requests WHERE identity_id = ?")) {
        ps.setString(1, identityId);
        return ps.executeUpdate();
      }
    }
  }

  /** How many requests this identity opened since {@code since} — the per-identity spam bound. */
  public int countSince(String column, String value, long since) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT COUNT(*) FROM auth_requests WHERE " + column + " = ? AND created_at >= ?")) {
        ps.setString(1, value);
        ps.setLong(2, since);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getInt(1) : 0;
        }
      }
    }
  }

  private static RequestRow read(ResultSet rs) throws SQLException {
    long notified = rs.getLong("notified_at");
    Long notifiedAt = rs.wasNull() ? null : notified;
    long decided = rs.getLong("decided_at");
    Long decidedAt = rs.wasNull() ? null : decided;
    return new RequestRow(
        rs.getString("request_id"),
        rs.getString("identity_id"),
        rs.getString("name_lower"),
        rs.getString("display_name"),
        rs.getString("discord_user_id"),
        rs.getString("ip_hash"),
        rs.getString("ip_prefix"),
        rs.getString("kind"),
        rs.getString("state"),
        rs.getLong("hold") != 0L,
        rs.getString("waiting_node"),
        rs.getString("claimed_by"),
        rs.getLong("claim_expires_at"),
        notifiedAt,
        decidedAt,
        rs.getString("decided_by"),
        rs.getInt("attempts"),
        rs.getLong("created_at"),
        rs.getLong("expires_at"),
        rs.getString("detail"));
  }
}
