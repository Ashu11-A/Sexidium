package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import com.sexidium.core.lib.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class AuthRepository {

    private final Database db;

    public AuthRepository(Database db) {
        this.db = db;
    }

    public String linkedDiscordId(String minecraftUuid) throws SQLException {
        synchronized (db.lock()) {
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    SELECT discord_user_id
                    FROM discord_accounts
                    WHERE minecraft_uuid = ?
                    LIMIT 1""")) {
                ps.setString(1, minecraftUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString("discord_user_id") : null;
                }
            }
        }
    }

    public AuthLink linkByMinecraftName(String minecraftName) throws SQLException {
        synchronized (db.lock()) {
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    SELECT da.discord_user_id, da.minecraft_uuid, da.minecraft_name
                    FROM discord_accounts da
                    JOIN players p ON p.uuid = da.minecraft_uuid
                    WHERE LOWER(p.name) = LOWER(?)
                    LIMIT 1""")) {
                ps.setString(1, minecraftName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new AuthLink(
                            rs.getString("discord_user_id"),
                            rs.getString("minecraft_uuid"),
                            rs.getString("minecraft_name"));
                }
            }
        }
    }

    /** The pre-session shape, kept so every existing caller and test compiles untouched. */
    public void createPendingCode(String codeHash, String minecraftUuid, String minecraftName, long now, long expiresAt)
            throws SQLException {
        createPendingCode(codeHash, minecraftUuid, minecraftName, null, now, expiresAt);
    }

    /**
     * Issues a pending code, remembering the NETWORK it was asked for.
     *
     * <p>{@code ipHash} is what lets consuming the code mint that network's first session on the
     * spot — which is the difference between "link, then reconnect, then approve" and "link, then
     * reconnect" on a player's very first join.</p>
     */
    public void createPendingCode(String codeHash, String minecraftUuid, String minecraftName, String ipHash,
            long now, long expiresAt) throws SQLException {
        synchronized (db.lock()) {
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    DELETE FROM auth_codes
                    WHERE minecraft_uuid = ?
                      AND consumed_at IS NULL""")) {
                ps.setString(1, minecraftUuid);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    INSERT INTO auth_codes(code_hash, minecraft_uuid, minecraft_name, ip_hash, created_at, expires_at)
                    VALUES(?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, codeHash);
                ps.setString(2, minecraftUuid);
                ps.setString(3, minecraftName);
                ps.setString(4, ipHash);
                ps.setLong(5, now);
                ps.setLong(6, expiresAt);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement(db.dialect().upsert(
                    "players",
                    new String[] {"uuid", "name", "points", "level", "wins", "kills", "games", "updated_at"},
                    new String[] {"uuid"},
                    new String[] {"name", "updated_at"}))) {
                ps.setString(1, minecraftUuid);
                ps.setString(2, minecraftName);
                ps.setInt(3, 0);
                ps.setInt(4, 0);
                ps.setInt(5, 0);
                ps.setInt(6, 0);
                ps.setInt(7, 0);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
        }
    }

    /**
     * Consumes a pending auth code and links the Discord account to the Minecraft player. Runs
     * entirely on the Java JDBC connection (the single writer) so the Discord bot no longer rewrites
     * the SQLite file out-of-band and clobbers concurrent rank/auth writes.
     */
    public AuthLinkResult consumeCode(
            String codeHash,
            String discordUserId,
            String discordUsername,
            String discordGlobalName,
            String discordAvatar,
            long now) throws SQLException {
        synchronized (db.lock()) {
            String minecraftUuid;
            String minecraftName;
            long expiresAt;
            boolean alreadyConsumed;
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    SELECT minecraft_uuid, minecraft_name, expires_at, consumed_at
                    FROM auth_codes
                    WHERE code_hash = ?
                    LIMIT 1""")) {
                ps.setString(1, codeHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new AuthLinkResult(AuthLinkResult.Status.INVALID, null);
                    }
                    minecraftUuid = rs.getString("minecraft_uuid");
                    minecraftName = rs.getString("minecraft_name");
                    expiresAt = rs.getLong("expires_at");
                    rs.getLong("consumed_at");
                    alreadyConsumed = !rs.wasNull();
                }
            }
            if (alreadyConsumed) {
                return new AuthLinkResult(AuthLinkResult.Status.ALREADY_USED, minecraftName);
            }
            if (expiresAt < now) {
                return new AuthLinkResult(AuthLinkResult.Status.EXPIRED, minecraftName);
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "SELECT 1 FROM discord_accounts WHERE minecraft_uuid = ? LIMIT 1")) {
                ps.setString(1, minecraftUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new AuthLinkResult(AuthLinkResult.Status.MINECRAFT_ALREADY_LINKED, minecraftName);
                    }
                }
            }
            // One Discord account may link to MANY Minecraft names (one-to-many): we deliberately do
            // NOT reject when this Discord user already owns other names — we just add another row.
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    INSERT INTO discord_accounts(minecraft_uuid, discord_user_id, minecraft_name,
                                                 discord_username, discord_global_name, discord_avatar,
                                                 created_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)""")) {
                ps.setString(1, minecraftUuid);
                ps.setString(2, discordUserId);
                ps.setString(3, minecraftName);
                ps.setString(4, blankToNull(discordUsername));
                ps.setString(5, blankToNull(discordGlobalName));
                ps.setString(6, blankToNull(discordAvatar));
                ps.setLong(7, now);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement(db.dialect().upsert(
                    "players",
                    new String[] {"uuid", "name", "discord_user_id", "points", "level", "wins", "kills", "games", "updated_at"},
                    new String[] {"uuid"},
                    new String[] {"name", "discord_user_id", "updated_at"}))) {
                ps.setString(1, minecraftUuid);
                ps.setString(2, minecraftName);
                ps.setString(3, discordUserId);
                ps.setInt(4, 0);
                ps.setInt(5, 0);
                ps.setInt(6, 0);
                ps.setInt(7, 0);
                ps.setInt(8, 0);
                ps.setLong(9, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    UPDATE auth_codes
                    SET consumed_at = ?, consumed_discord_user_id = ?
                    WHERE code_hash = ?""")) {
                ps.setLong(1, now);
                ps.setString(2, discordUserId);
                ps.setString(3, codeHash);
                ps.executeUpdate();
            }
            return new AuthLinkResult(AuthLinkResult.Status.LINKED, minecraftName);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Which player, and which network, a code was issued for. Read BEFORE the code is consumed. */
    public CodeBinding bindingOf(String codeHash) throws SQLException {
        synchronized (db.lock()) {
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    SELECT minecraft_uuid, minecraft_name, ip_hash
                    FROM auth_codes
                    WHERE code_hash = ?
                    LIMIT 1""")) {
                ps.setString(1, codeHash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new CodeBinding(
                            rs.getString("minecraft_uuid"),
                            rs.getString("minecraft_name"),
                            rs.getString("ip_hash"));
                }
            }
        }
    }

    /** What {@link #bindingOf(String)} answers. {@code ipHash} is null for a pre-session code. */
    public record CodeBinding(String minecraftUuid, String minecraftName, String ipHash) {
    }

    public AuthLink unlinkByMinecraftName(String minecraftName) throws SQLException {
        synchronized (db.lock()) {
            AuthLink link;
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    SELECT da.discord_user_id, da.minecraft_uuid, da.minecraft_name
                    FROM discord_accounts da
                    JOIN players p ON p.uuid = da.minecraft_uuid
                    WHERE LOWER(p.name) = LOWER(?)
                    LIMIT 1""")) {
                ps.setString(1, minecraftName);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    link = new AuthLink(
                            rs.getString("discord_user_id"),
                            rs.getString("minecraft_uuid"),
                            rs.getString("minecraft_name"));
                }
            }
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    DELETE FROM discord_accounts
                    WHERE minecraft_uuid = ?""")) {
                ps.setString(1, link.minecraftUuid());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement("""
                    UPDATE players
                    SET discord_user_id = NULL
                    WHERE uuid = ?""")) {
                ps.setString(1, link.minecraftUuid());
                ps.executeUpdate();
            }
            // Unlinking must take the sessions with it. A session is "this Discord user approved this
            // network", so leaving one alive after the approval is withdrawn would let the network
            // keep walking in on an account that is no longer anybody's.
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "DELETE FROM auth_sessions WHERE identity_id = ?")) {
                ps.setString(1, link.minecraftUuid());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = db.connection().prepareStatement(
                    "DELETE FROM auth_requests WHERE identity_id = ?")) {
                ps.setString(1, link.minecraftUuid());
                ps.executeUpdate();
            }
            return link;
        }
    }
}
