package com.sexidium.core.auth;

import com.sexidium.core.lib.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Reads and writes {@code auth_ip_blocks}: the durable half of pressing <b>Deny</b>.
 *
 * <p>Blocks are per {@code (identity, network)}, never per network alone. Blocking an address
 * outright would, under CGNAT, punish every stranger who happens to share it with whoever tried to
 * take the name.</p>
 */
public final class AuthIpBlockRepository {

  private final Database db;

  public AuthIpBlockRepository(Database db) {
    this.db = db;
  }

  public boolean blocked(String identityId, String ipHash, long now) throws SQLException {
    if (identityId == null || ipHash == null) {
      return false;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT 1 FROM auth_ip_blocks WHERE identity_id = ? AND ip_hash = ? AND expires_at > ? LIMIT 1")) {
        ps.setString(1, identityId);
        ps.setString(2, ipHash);
        ps.setLong(3, now);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next();
        }
      }
    }
  }

  public void block(String identityId, String ipHash, String reason, long now, long expiresAt)
      throws SQLException {
    if (identityId == null || ipHash == null) {
      return;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(db.dialect().upsert(
          "auth_ip_blocks",
          new String[] {"identity_id", "ip_hash", "reason", "created_at", "expires_at"},
          new String[] {"identity_id", "ip_hash"},
          new String[] {"reason", "created_at", "expires_at"}))) {
        ps.setString(1, identityId);
        ps.setString(2, ipHash);
        ps.setString(3, reason);
        ps.setLong(4, now);
        ps.setLong(5, expiresAt);
        ps.executeUpdate();
      }
    }
  }

  public int deleteExpired(long now) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "DELETE FROM auth_ip_blocks WHERE expires_at < ?")) {
        ps.setLong(1, now);
        return ps.executeUpdate();
      }
    }
  }

  /** Clear every block on one identity — how staff undo a Deny that was not the player's fault. */
  public int clear(String identityId) throws SQLException {
    if (identityId == null) {
      return 0;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "DELETE FROM auth_ip_blocks WHERE identity_id = ?")) {
        ps.setString(1, identityId);
        return ps.executeUpdate();
      }
    }
  }
}
