package com.sexidium.core.auth;

/**
 * The small value/result types of the auth subsystem, grouped in one place (formerly one file each).
 * Each keeps its original simple name, so a call site only switches its import to
 * {@code com.sexidium.core.auth.AuthResults.<Name>}.
 */
public final class AuthResults {
  private AuthResults() {
  }

  /** A confirmed Discord↔Minecraft account link. */
  public record AuthLink(String discordUserId, String minecraftUuid, String minecraftName) {
  }

  /** Outcome of issuing a /sx auth code. */
  public record AuthCodeResult(Status status, String code, long expiresAt, String discordUserId) {
    public enum Status {
      CREATED,
      ALREADY_LINKED,
      DISABLED
    }
  }

  /** Whether a login is allowed, with a rejection message when not. */
  public record LoginDecision(boolean allowed, String rejectionMessage) {
    public static LoginDecision allow() {
      return new LoginDecision(true, "");
    }

    public static LoginDecision reject(String rejectionMessage) {
      return new LoginDecision(false, rejectionMessage == null ? "" : rejectionMessage);
    }
  }

  /**
   * Outcome of consuming a /sx auth code and linking a Discord account to a Minecraft player. The
   * status tokens intentionally match the strings the Discord bot already switches on.
   */
  public record AuthLinkResult(Status status, String minecraftName) {
    public enum Status {
      LINKED,
      INVALID,
      EXPIRED,
      ALREADY_USED,
      MINECRAFT_ALREADY_LINKED,
      DISCORD_ALREADY_LINKED,
      DISABLED
    }

    public String statusToken() {
      return switch (status) {
        case LINKED -> "linked";
        case INVALID -> "invalid";
        case EXPIRED -> "expired";
        case ALREADY_USED -> "already-used";
        case MINECRAFT_ALREADY_LINKED -> "minecraft-already-linked";
        case DISCORD_ALREADY_LINKED -> "discord-already-linked";
        case DISABLED -> "disabled";
      };
    }
  }

  /**
   * The richer gate answer: allow, allow-but-hold, reject, or reject-after-opening-a-Discord-request.
   *
   * <p>Additive on purpose — {@link LoginDecision} and its two factories are untouched, so every
   * existing call site and test keeps compiling, and {@link #toLoginDecision()} is the one-way door
   * a caller that only understands yes/no walks through.</p>
   */
  public record GateOutcome(Kind kind, String rejectionMessage, String requestId, long expiresAt) {

    public enum Kind {
      /** Let them in. */
      ALLOW,
      /** Let them in, but frozen: a hold is pending on the backend they land on. */
      HOLD,
      /** Refuse, with a final message. */
      REJECT,
      /** Refuse, but a Discord approval is now on its way — reconnecting after it succeeds. */
      REJECT_PENDING_APPROVAL
    }

    public static GateOutcome allow() {
      return new GateOutcome(Kind.ALLOW, "", null, 0L);
    }

    public static GateOutcome hold(String requestId, long expiresAt) {
      return new GateOutcome(Kind.HOLD, "", requestId, expiresAt);
    }

    public static GateOutcome reject(String rejectionMessage) {
      return new GateOutcome(Kind.REJECT, rejectionMessage == null ? "" : rejectionMessage, null, 0L);
    }

    public static GateOutcome pending(String rejectionMessage, String requestId, long expiresAt) {
      return new GateOutcome(
          Kind.REJECT_PENDING_APPROVAL,
          rejectionMessage == null ? "" : rejectionMessage,
          requestId,
          expiresAt);
    }

    public boolean allowed() {
      return kind == Kind.ALLOW || kind == Kind.HOLD;
    }

    /** The yes/no projection. A HOLD is an allow: the player reaches a world, frozen. */
    public LoginDecision toLoginDecision() {
      return allowed() ? LoginDecision.allow() : LoginDecision.reject(rejectionMessage);
    }
  }

  /**
   * Outcome of a Discord Approve/Deny press. The status tokens are what the bot switches on, so they
   * are spelled here once and mirrored in {@code bot/src/types/contract.ts}.
   */
  public record DecisionOutcome(Status status, String minecraftName, int sessionHours) {

    public enum Status {
      APPROVED,
      DENIED,
      EXPIRED,
      ALREADY_DECIDED,
      NOT_FOUND,
      /** The pressing Discord user does not own the request. Never trusted from the bot's claim. */
      FORBIDDEN,
      DISABLED
    }

    public static DecisionOutcome of(Status status, String minecraftName) {
      return new DecisionOutcome(status, minecraftName == null ? "" : minecraftName, 0);
    }

    public String statusToken() {
      return switch (status) {
        case APPROVED -> "approved";
        case DENIED -> "denied";
        case EXPIRED -> "expired";
        case ALREADY_DECIDED -> "already-decided";
        case NOT_FOUND -> "not-found";
        case FORBIDDEN -> "forbidden";
        case DISABLED -> "disabled";
      };
    }
  }

  /** One live session, as Discord's {@code /sessions} shows it. Carries no hash and no raw IP. */
  public record SessionView(
      String sessionId,
      String minecraftName,
      String ipPrefix,
      String device,
      long createdAt,
      long lastSeenAt,
      long expiresAt) {
  }
}
