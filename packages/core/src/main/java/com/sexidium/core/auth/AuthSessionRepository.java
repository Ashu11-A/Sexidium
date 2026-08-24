package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthResults.SessionView;
import com.sexidium.core.lib.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes {@code auth_sessions}: "this name was approved from this network recently".
 *
 * <p>The primary key is {@code (identity_id, ip_hash)} and that IS the security property — an IP with
 * no matching identity authorises nothing, which is the answer to a shared CGNAT address. There is
 * no client-side token, so there is nothing here to steal in transit; the price is that a neighbour
 * behind the same public IPv4 who knows the exact name matches the row, which is why the TTL is
 * hours and why the absolute cap cannot be sled past.</p>
 */
public final class AuthSessionRepository {

  /** One row, with both TTLs kept apart so the sliding renewal can be clamped by the hard cap. */
  public record SessionRow(
      String identityId,
      String ipHash,
      String sessionId,
      String nameLower,
      String ipPrefix,
      String device,
      String originNode,
      String approvedBy,
      long createdAt,
      long lastSeenAt,
      long expiresAt,
      long absoluteExpiresAt,
      Long revokedAt) {

    public boolean live(long now) {
      return revokedAt == null && expiresAt > now && absoluteExpiresAt > now;
    }

    public SessionView view() {
      return new SessionView(sessionId, nameLower, ipPrefix, device, createdAt, lastSeenAt, expiresAt);
    }
  }

  private static final String COLUMNS =
      "identity_id, ip_hash, session_id, name_lower, ip_prefix, device, origin_node, approved_by,"
          + " created_at, last_seen_at, expires_at, absolute_expires_at, revoked_at";

  private final Database db;

  public AuthSessionRepository(Database db) {
    this.db = db;
  }

  public SessionRow find(String identityId, String ipHash) throws SQLException {
    if (identityId == null || ipHash == null) {
      return null;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT " + COLUMNS + " FROM auth_sessions WHERE identity_id = ? AND ip_hash = ? LIMIT 1")) {
        ps.setString(1, identityId);
        ps.setString(2, ipHash);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? read(rs) : null;
        }
      }
    }
  }

  /**
   * Write or refresh a session.
   *
   * <p>{@code absolute_expires_at} is deliberately NOT in the update list: a sliding renewal must
   * never be able to push the hard cap forward, or "after 72 hours you approve again" would be a
   * promise the code does not keep.</p>
   */
  public void upsert(SessionRow row) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(db.dialect().upsert(
          "auth_sessions",
          new String[] {"identity_id", "ip_hash", "session_id", "name_lower", "ip_prefix", "device",
              "origin_node", "approved_by", "created_at", "last_seen_at", "expires_at",
              "absolute_expires_at", "revoked_at"},
          new String[] {"identity_id", "ip_hash"},
          new String[] {"session_id", "name_lower", "ip_prefix", "device", "origin_node",
              "approved_by", "last_seen_at", "expires_at", "revoked_at"}))) {
        ps.setString(1, row.identityId());
        ps.setString(2, row.ipHash());
        ps.setString(3, row.sessionId());
        ps.setString(4, row.nameLower());
        ps.setString(5, row.ipPrefix());
        ps.setString(6, row.device());
        ps.setString(7, row.originNode());
        ps.setString(8, row.approvedBy());
        ps.setLong(9, row.createdAt());
        ps.setLong(10, row.lastSeenAt());
        ps.setLong(11, row.expiresAt());
        ps.setLong(12, row.absoluteExpiresAt());
        if (row.revokedAt() == null) {
          ps.setNull(13, java.sql.Types.BIGINT);
        } else {
          ps.setLong(13, row.revokedAt());
        }
        ps.executeUpdate();
      }
    }
  }

  /** Slide one session's TTL forward, clamped by its own absolute cap. */
  public void renew(String identityId, String ipHash, long now, long expiresAt) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE auth_sessions
          SET last_seen_at = ?,
              expires_at = CASE WHEN ? < absolute_expires_at THEN ? ELSE absolute_expires_at END
          WHERE identity_id = ? AND ip_hash = ?""")) {
        ps.setLong(1, now);
        ps.setLong(2, expiresAt);
        ps.setLong(3, expiresAt);
        ps.setString(4, identityId);
        ps.setString(5, ipHash);
        ps.executeUpdate();
      }
    }
  }

  public List<SessionRow> byIdentity(String identityId) throws SQLException {
    return query("SELECT " + COLUMNS + " FROM auth_sessions WHERE identity_id = ?"
        + " ORDER BY last_seen_at DESC", identityId);
  }

  /**
   * Every live session of every Minecraft name a Discord user owns.
   *
   * <p>Joined through {@code discord_accounts} rather than trusting a caller-supplied identity list:
   * the bot asserts who is asking, and that assertion is only ever an input to this query, never a
   * substitute for it.</p>
   */
  public List<SessionRow> byDiscordUser(String discordUserId, long now) throws SQLException {
    List<SessionRow> rows = new ArrayList<>();
    if (discordUserId == null || discordUserId.isBlank()) {
      return rows;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          SELECT s.identity_id, s.ip_hash, s.session_id, s.name_lower, s.ip_prefix, s.device,
                 s.origin_node, s.approved_by, s.created_at, s.last_seen_at, s.expires_at,
                 s.absolute_expires_at, s.revoked_at
          FROM auth_sessions s
          JOIN player_identities i ON i.identity_id = s.identity_id
          JOIN discord_accounts d ON d.minecraft_uuid = i.identity_id
          WHERE d.discord_user_id = ?
            AND s.revoked_at IS NULL
            AND s.expires_at > ?
          ORDER BY s.last_seen_at DESC""")) {
        ps.setString(1, discordUserId);
        ps.setLong(2, now);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            rows.add(read(rs));
          }
        }
      }
    }
    return rows;
  }

  /** Whether a Discord user owns this session. Checked server-side; the bot's claim is only input. */
  public boolean ownedBy(String sessionId, String discordUserId) throws SQLException {
    if (sessionId == null || sessionId.isBlank() || discordUserId == null || discordUserId.isBlank()) {
      return false;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          SELECT 1
          FROM auth_sessions s
          JOIN discord_accounts d ON d.minecraft_uuid = s.identity_id
          WHERE s.session_id = ? AND d.discord_user_id = ?
          LIMIT 1""")) {
        ps.setString(1, sessionId);
        ps.setString(2, discordUserId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next();
        }
      }
    }
  }

  public int deleteExpired(long now) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "DELETE FROM auth_sessions WHERE expires_at < ? OR absolute_expires_at < ?"
              + " OR revoked_at IS NOT NULL")) {
        ps.setLong(1, now);
        ps.setLong(2, now);
        return ps.executeUpdate();
      }
    }
  }

  /** Kill every session of one identity — what the Deny button and {@code /sx auth unlink} do. */
  public int revokeAll(String identityId) throws SQLException {
    if (identityId == null) {
      return 0;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "DELETE FROM auth_sessions WHERE identity_id = ?")) {
        ps.setString(1, identityId);
        return ps.executeUpdate();
      }
    }
  }

  public int revokeOne(String sessionId) throws SQLException {
    if (sessionId == null || sessionId.isBlank()) {
      return 0;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "DELETE FROM auth_sessions WHERE session_id = ?")) {
        ps.setString(1, sessionId);
        return ps.executeUpdate();
      }
    }
  }

  /**
   * Keep at most {@code max} sessions per identity, evicting the least recently used.
   *
   * <p>Deletes by primary key rather than with a correlated subquery: MySQL refuses to delete from a
   * table it is also selecting from in the same statement, and doing it in two steps is portable.</p>
   */
  public int trimTo(String identityId, int max) throws SQLException {
    if (identityId == null || max <= 0) {
      return 0;
    }
    List<SessionRow> rows = byIdentity(identityId);
    if (rows.size() <= max) {
      return 0;
    }
    int removed = 0;
    for (SessionRow row : rows.subList(max, rows.size())) {
      synchronized (db.lock()) {
        try (PreparedStatement ps = db.connection().prepareStatement(
            "DELETE FROM auth_sessions WHERE identity_id = ? AND ip_hash = ?")) {
          ps.setString(1, row.identityId());
          ps.setString(2, row.ipHash());
          removed += ps.executeUpdate();
        }
      }
    }
    return removed;
  }

  private List<SessionRow> query(String sql, String argument) throws SQLException {
    List<SessionRow> rows = new ArrayList<>();
    if (argument == null) {
      return rows;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
        ps.setString(1, argument);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            rows.add(read(rs));
          }
        }
      }
    }
    return rows;
  }

  private static SessionRow read(ResultSet rs) throws SQLException {
    long revoked = rs.getLong("revoked_at");
    Long revokedAt = rs.wasNull() ? null : revoked;
    return new SessionRow(
        rs.getString("identity_id"),
        rs.getString("ip_hash"),
        rs.getString("session_id"),
        rs.getString("name_lower"),
        rs.getString("ip_prefix"),
        rs.getString("device"),
        rs.getString("origin_node"),
        rs.getString("approved_by"),
        rs.getLong("created_at"),
        rs.getLong("last_seen_at"),
        rs.getLong("expires_at"),
        rs.getLong("absolute_expires_at"),
        revokedAt);
  }
}
