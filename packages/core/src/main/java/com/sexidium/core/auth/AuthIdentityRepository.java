package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthIdentity.PremiumState;
import com.sexidium.core.lib.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/** Reads and writes {@code player_identities}, the anchor described on {@link AuthIdentity}. */
public final class AuthIdentityRepository {

  private final Database db;

  public AuthIdentityRepository(Database db) {
    this.db = db;
  }

  public AuthIdentity find(String nameLower) throws SQLException {
    String key = normalize(nameLower);
    if (key.isEmpty()) {
      return null;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          SELECT name_lower, identity_id, display_name, premium_uuid, bedrock_xuid,
                 account_type, premium_state, premium_checked_at
          FROM player_identities
          WHERE name_lower = ?
          LIMIT 1""")) {
        ps.setString(1, key);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? read(rs) : null;
        }
      }
    }
  }

  /**
   * The identity for a connecting name, created on first sight and never recomputed afterwards.
   *
   * <p><b>Adoption is the migration.</b> When a {@code players} row already exists whose name matches
   * this one case-insensitively, its uuid becomes the identity: zero rows move, zero data is
   * rewritten, and every existing Discord link, experience, friendship and point balance keeps
   * working. Only a name nobody has ever played gets a freshly derived uuid.</p>
   *
   * <p>Two legacy rows for one lowercase name (the case-aliasing bug this table closes) resolve to
   * the most recently updated one — the account the human is actually using. Merging the other is a
   * staff action, deliberately not automated here.</p>
   */
  public AuthIdentity resolveOrCreate(String username, long now) throws SQLException {
    String key = normalize(username);
    if (key.isEmpty()) {
      return null;
    }
    AuthIdentity existing = find(key);
    if (existing != null) {
      touch(key, username, now);
      return new AuthIdentity(
          existing.nameLower(), existing.identityId(), username == null ? existing.displayName() : username,
          existing.premiumUuid(), existing.bedrockXuid(), existing.accountType(),
          existing.premiumState(), existing.premiumCheckedAt());
    }
    String identityId = adoptExistingPlayerUuid(key);
    if (identityId == null) {
      identityId = AuthIdentity.offlineUuidOf(key);
    }
    String display = username == null || username.isBlank() ? key : username;
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(db.dialect().upsert(
          "player_identities",
          new String[] {"name_lower", "identity_id", "display_name", "account_type", "premium_state",
              "premium_checked_at", "first_seen_at", "last_seen_at", "updated_at"},
          new String[] {"name_lower"},
          new String[] {"display_name", "last_seen_at", "updated_at"}))) {
        ps.setString(1, key);
        ps.setString(2, identityId);
        ps.setString(3, display);
        ps.setString(4, AuthIdentity.TYPE_CRACKED);
        ps.setString(5, PremiumState.UNKNOWN.token());
        ps.setLong(6, 0L);
        ps.setLong(7, now);
        ps.setLong(8, now);
        ps.setLong(9, now);
        ps.executeUpdate();
      }
    }
    // Re-read rather than construct: a racing node may have inserted first, and its identity_id is
    // the one every other table is about to be keyed by.
    AuthIdentity stored = find(key);
    return stored != null
        ? stored
        : new AuthIdentity(key, identityId, display, null, null, AuthIdentity.TYPE_CRACKED,
            PremiumState.UNKNOWN, 0L);
  }

  /** Refresh the spelling a player currently uses, and when they were last seen. */
  public void touch(String nameLower, String displayName, long now) throws SQLException {
    String key = normalize(nameLower);
    if (key.isEmpty()) {
      return;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE player_identities
          SET display_name = ?, last_seen_at = ?, updated_at = ?
          WHERE name_lower = ?""")) {
        ps.setString(1, displayName == null || displayName.isBlank() ? key : displayName);
        ps.setLong(2, now);
        ps.setLong(3, now);
        ps.setString(4, key);
        ps.executeUpdate();
      }
    }
  }

  /**
   * Record what Mojang said about a name, durably.
   *
   * <p>This is the layer that survives a restart, which is why the proxy does not re-hammer the
   * Mojang API for every returning player after a reboot — and why an outage still has an answer for
   * a name we have already resolved.</p>
   */
  public void recordPremium(String nameLower, String premiumUuid, PremiumState state, long checkedAt)
      throws SQLException {
    String key = normalize(nameLower);
    if (key.isEmpty()) {
      return;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE player_identities
          SET premium_uuid = COALESCE(?, premium_uuid),
              premium_state = ?,
              premium_checked_at = ?,
              account_type = ?,
              updated_at = ?
          WHERE name_lower = ?""")) {
        ps.setString(1, blankToNull(premiumUuid));
        ps.setString(2, (state == null ? PremiumState.UNKNOWN : state).token());
        ps.setLong(3, checkedAt);
        ps.setString(4, state == PremiumState.PREMIUM ? AuthIdentity.TYPE_PREMIUM : AuthIdentity.TYPE_CRACKED);
        ps.setLong(5, checkedAt);
        ps.setString(6, key);
        ps.executeUpdate();
      }
    }
  }

  /** Record the Floodgate XUID behind a Bedrock connection, when the API exposes one. */
  public void recordBedrock(String nameLower, String xuid, long now) throws SQLException {
    String key = normalize(nameLower);
    if (key.isEmpty()) {
      return;
    }
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          UPDATE player_identities
          SET bedrock_xuid = COALESCE(?, bedrock_xuid), account_type = ?, updated_at = ?
          WHERE name_lower = ?""")) {
        ps.setString(1, blankToNull(xuid));
        ps.setString(2, AuthIdentity.TYPE_BEDROCK);
        ps.setLong(3, now);
        ps.setString(4, key);
        ps.executeUpdate();
      }
    }
  }

  /**
   * The uuid an existing {@code players} row already uses for this name, newest first.
   *
   * <p>{@code LOWER(name) = ?} rather than a join or a collation trick, because that comparison is
   * the one form valid on all three dialects (see {@link com.sexidium.core.lib.data.SqlDialect}).</p>
   */
  private String adoptExistingPlayerUuid(String nameLower) throws SQLException {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement("""
          SELECT uuid
          FROM players
          WHERE LOWER(name) = ?
          ORDER BY updated_at DESC
          LIMIT 1""")) {
        ps.setString(1, nameLower);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? rs.getString("uuid") : null;
        }
      }
    }
  }

  private static AuthIdentity read(ResultSet rs) throws SQLException {
    return new AuthIdentity(
        rs.getString("name_lower"),
        rs.getString("identity_id"),
        rs.getString("display_name"),
        rs.getString("premium_uuid"),
        rs.getString("bedrock_xuid"),
        rs.getString("account_type"),
        PremiumState.parse(rs.getString("premium_state")),
        rs.getLong("premium_checked_at"));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
