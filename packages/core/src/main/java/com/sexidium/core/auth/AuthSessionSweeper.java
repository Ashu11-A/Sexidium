package com.sexidium.core.auth;

import com.sexidium.core.platform.LoggerAdapter;

import java.sql.SQLException;

/**
 * Deletes what has expired: sessions past their TTL, requests nobody answered, and Deny blocks that
 * have served their day.
 *
 * <p>Runs on the {@code QUEUE_AUTHORITY} node, of which there is exactly one, because four nodes each
 * sweeping the same three tables every minute is four times the work for the same result.</p>
 */
public final class AuthSessionSweeper {

  /** How long a decided/expired request row is kept for the audit trail before it is deleted. */
  private static final long RETENTION_MILLIS = 7L * 86_400_000L;

  private final AuthSessionRepository sessions;
  private final AuthRequestRepository requests;
  private final AuthIpBlockRepository blocks;
  private final LoggerAdapter logger;

  public AuthSessionSweeper(AuthSessionRepository sessions, AuthRequestRepository requests,
      AuthIpBlockRepository blocks, LoggerAdapter logger) {
    this.sessions = sessions;
    this.requests = requests;
    this.blocks = blocks;
    this.logger = logger;
  }

  public void tick() {
    long now = System.currentTimeMillis();
    try {
      sessions.deleteExpired(now);
      requests.expireStale(now, RETENTION_MILLIS);
      blocks.deleteExpired(now);
    } catch (SQLException failed) {
      // A missed sweep costs table growth, never correctness: every reader already filters on the
      // same timestamps this deletes by.
      logger.warning("Auth sweep failed: " + failed.getMessage());
    }
  }
}
