package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import com.sexidium.core.lib.data.Database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

public final class AuthService {

    private static final char[] DEFAULT_CODE_CHARS = "23456789".toCharArray();

    private final AuthRepository repo;
    private final SecureRandom random = new SecureRandom();
    private final BooleanSupplier enabled;
    /**
     * Set after construction, because the session service is built FROM this one — consuming a code
     * mints the first session, and a constructor cycle is not worth the tidiness.
     */
    private volatile AuthSessionService sessionService;

    public AuthService(Database db, BooleanSupplier enabled) {
        this.repo = new AuthRepository(db);
        this.enabled = enabled;
    }

    /** Attach the session gate, so linking an account also remembers the network it linked from. */
    public void setSessionService(AuthSessionService sessionService) {
        this.sessionService = sessionService;
    }

    public AuthCodeResult createCode(
            String minecraftUuid,
            String minecraftName,
            int length,
            long ttlMillis,
            String configuredCharacters)
            throws SQLException {
        return createCode(minecraftUuid, minecraftName, length, ttlMillis, configuredCharacters, null);
    }

    /** The same, remembering which network asked — see {@link AuthRepository#createPendingCode}. */
    public AuthCodeResult createCode(
            String minecraftUuid,
            String minecraftName,
            int length,
            long ttlMillis,
            String configuredCharacters,
            String ipHash)
            throws SQLException {
        if (!enabled.getAsBoolean()) return new AuthCodeResult(AuthCodeResult.Status.DISABLED, null, 0, null);

        String linked = repo.linkedDiscordId(minecraftUuid);
        if (linked != null && !linked.isBlank()) {
            return new AuthCodeResult(AuthCodeResult.Status.ALREADY_LINKED, null, 0, linked);
        }

        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(1_000L, ttlMillis);
        int safeLength = Math.max(4, Math.min(16, length));
        SQLException lastError = null;
        char[] characters = codeCharacters(configuredCharacters);
        for (int attempt = 0; attempt < 8; attempt++) {
            String code = generateCode(safeLength, characters);
            try {
                repo.createPendingCode(hashCode(code), minecraftUuid, minecraftName, ipHash, now, expiresAt);
                return new AuthCodeResult(AuthCodeResult.Status.CREATED, code, expiresAt, null);
            } catch (SQLException ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new SQLException("Could not create auth code") : lastError;
    }

    public String linkedDiscordId(String minecraftUuid) throws SQLException {
        if (!enabled.getAsBoolean()) return null;
        return repo.linkedDiscordId(minecraftUuid);
    }

    /**
     * Verifies a /sx auth code and links the Discord account, performed on the single Java writer
     * (called by the HTTP bridge so the bot never opens the SQLite file directly).
     */
    public AuthLinkResult consumeCode(
            String code,
            String discordUserId,
            String discordUsername,
            String discordGlobalName,
            String discordAvatar)
            throws SQLException {
        if (!enabled.getAsBoolean()) {
            return new AuthLinkResult(AuthLinkResult.Status.DISABLED, null);
        }
        if (code == null || code.isBlank() || discordUserId == null || discordUserId.isBlank()) {
            return new AuthLinkResult(AuthLinkResult.Status.INVALID, null);
        }
        String codeHash = hashCode(code);
        // Read the binding BEFORE consuming: the row is still there, and it carries the network the
        // player asked from, which is what the first session is minted against.
        AuthRepository.CodeBinding binding = repo.bindingOf(codeHash);
        AuthLinkResult result = repo.consumeCode(
                codeHash, discordUserId, discordUsername, discordGlobalName, discordAvatar,
                System.currentTimeMillis());
        if (result.status() == AuthLinkResult.Status.LINKED) {
            mintFirstSession(binding, discordUserId);
        }
        return result;
    }

    /**
     * Turn a freshly linked account into a live session for the network that linked it.
     *
     * <p>This is what removes a step from a player's very first join: they reconnect once after
     * running {@code /auth <code>} and are simply in, instead of reconnecting and then being asked to
     * approve the same network they just proved they were on.</p>
     */
    private void mintFirstSession(AuthRepository.CodeBinding binding, String discordUserId) {
        AuthSessionService sessions = sessionService;
        if (sessions == null || binding == null || binding.ipHash() == null || !sessions.enabled()) {
            return;
        }
        try {
            AuthIdentity identity = sessions.identities().find(
                    binding.minecraftName() == null ? "" : binding.minecraftName());
            if (identity != null) {
                sessions.mintSession(identity, binding.ipHash(), null, "java", discordUserId);
            }
        } catch (SQLException ignored) {
            // One extra approval later is the whole cost of failing here.
        }
    }

    public AuthLink unlinkByMinecraftName(String minecraftName) throws SQLException {
        return repo.unlinkByMinecraftName(minecraftName);
    }

    private String generateCode(int length, char[] characters) {
        StringBuilder code = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            code.append(characters[random.nextInt(characters.length)]);
        }
        return code.toString();
    }

    private char[] codeCharacters(String configuredCharacters) {
        String cleaned = normalizeCode(configuredCharacters).replaceAll("[^A-Z0-9]", "");
        if (cleaned.length() < 2) return DEFAULT_CODE_CHARS;
        return cleaned.toCharArray();
    }

    public static String hashCode(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizeCode(code).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static String normalizeCode(String code) {
        return (code == null ? "" : code).trim().replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
