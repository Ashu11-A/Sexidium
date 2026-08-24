package com.sexidium.core.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * One human, addressed by the lowercase spelling of their Minecraft name.
 *
 * <p>The anchor exists because an offline UUID is {@code nameUUIDFromBytes("OfflinePlayer:" + name)}
 * — derived from the name, <em>case-sensitively</em>. So {@code Foo} and {@code foo} were two rows in
 * {@code players} with two point balances, and the same human arriving through Mojang would carry a
 * third uuid that matches nothing anywhere. One row per lowercase name fixes both at once:
 * {@link #identityId()} is decided ONCE (adopted from an existing {@code players} row when there is
 * one, so nothing is rewritten) and every connection is pinned to it at the proxy.</p>
 *
 * <p>{@link #premiumUuid()} is recorded and never keyed on. It is there for the audit trail, for
 * name-squat protection, and to leave a future "go fully online-mode" migration something to read.</p>
 */
public record AuthIdentity(
    String nameLower,
    String identityId,
    String displayName,
    String premiumUuid,
    String bedrockXuid,
    String accountType,
    PremiumState premiumState,
    long premiumCheckedAt) {

  /** What we know about whether the name belongs to a Mojang account. */
  public enum PremiumState {
    /** Never successfully asked. The only state an outage may not guess at; see the gate. */
    UNKNOWN,
    PREMIUM,
    CRACKED;

    public String token() {
      return name().toLowerCase(Locale.ROOT);
    }

    public static PremiumState parse(String raw) {
      if (raw == null) {
        return UNKNOWN;
      }
      return switch (raw.trim().toLowerCase(Locale.ROOT)) {
        case "premium" -> PREMIUM;
        case "cracked" -> CRACKED;
        default -> UNKNOWN;
      };
    }
  }

  /** Account type tokens, matching the column's default and the Discord card's wording. */
  public static final String TYPE_CRACKED = "cracked";
  public static final String TYPE_PREMIUM = "premium";
  public static final String TYPE_BEDROCK = "bedrock";

  /**
   * The offline uuid of a name, as Minecraft derives it — but always of the LOWERCASE spelling, which
   * is the whole point: it is what makes {@code Foo} and {@code foo} one account from here on.
   */
  public static String offlineUuidOf(String nameLower) {
    String name = nameLower == null ? "" : nameLower.trim().toLowerCase(Locale.ROOT);
    return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** The canonical uuid as a {@link UUID}, or null when the stored value is not one. */
  public UUID identityUuid() {
    try {
      return identityId == null ? null : UUID.fromString(identityId);
    } catch (IllegalArgumentException notAUuid) {
      return null;
    }
  }
}
