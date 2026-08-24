package com.sexidium.core.auth;

import java.util.UUID;

/**
 * One connection attempt, as much of it as the gate is allowed to know.
 *
 * <p>Deliberately a platform-free value: the proxy fills it from {@code InboundConnection}, a Paper
 * backend from {@code AsyncPlayerPreLoginEvent}, and a test from nothing at all. That is what lets
 * one decision matrix ({@link AuthSessionService#authorize}) serve both gates instead of two copies
 * that drift.</p>
 *
 * <p>{@code connectionUuid} is what the client arrived as, which is NOT the canonical identity —
 * see {@link AuthIdentity}. It is kept only so the legacy per-uuid link lookup keeps working and so
 * a log line can say what actually connected.</p>
 *
 * @param premiumVerified true only when Mojang session encryption has already succeeded for this
 *                        connection. Never inferred from the name, because inferring it is exactly
 *                        how an impostor takes a premium name.
 */
public record AuthConnection(
    String username,
    UUID connectionUuid,
    String ip,
    String virtualHost,
    int protocolVersion,
    boolean bedrock,
    boolean premiumVerified,
    String premiumUuid) {

  /** The shape the pre-session gate had: a uuid and a name, no network context. */
  public static AuthConnection legacy(UUID connectionUuid, String username) {
    return new AuthConnection(username, connectionUuid, null, null, 0, false, false, null);
  }

  /** A plain cracked/unknown Java connection from an address. */
  public static AuthConnection of(UUID connectionUuid, String username, String ip) {
    return new AuthConnection(username, connectionUuid, ip, null, 0, false, false, null);
  }

  /** The name, lowercased — the key every identity, session and rate limit is addressed by. */
  public String nameLower() {
    return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
  }
}
