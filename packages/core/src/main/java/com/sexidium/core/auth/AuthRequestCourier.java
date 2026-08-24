package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthRequestRepository.RequestRow;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.SQLException;
import java.util.List;

/**
 * Carries pending approvals from the shared table to the Discord bot.
 *
 * <p>Runs on the {@code BOT_HOST} node, which is the only one with a live bridge to the bot. Polled
 * rather than pushed for the same reason every other cross-node mechanism here is
 * ({@code player_transfers}, {@code match_handoffs}): the node that WROTE the row is the proxy, and
 * the proxy has no way to reach the bot at all.</p>
 *
 * <p>Claim-then-mark-notified, never select-then-send: a courier that dies between the claim and the
 * Discord call lets its lease lapse and the next poll — on this node or another — picks the row back
 * up. A notifier that throws leaves the row claimed but un-notified, which is the same recoverable
 * state, so a Discord hiccup costs a retry rather than a lost login.</p>
 */
public final class AuthRequestCourier {

  /** How many requests one poll carries. Bounded so a backlog cannot monopolise a tick. */
  private static final int BATCH = 16;

  /**
   * What actually delivers the push. The bridge emitter in production, a recorder in tests.
   *
   * <p>Returns whether the delivery reached a live channel: a request is marked notified ONLY on a
   * true return, so a push that silently disappeared (the bot mid-restart, a dead socket) leaves the
   * row pending and the lease lapsing, which is what re-offers it to the next poll. A notifier that
   * throws is treated the same way — false by virtue of not returning — for the same reason.</p>
   */
  @FunctionalInterface
  public interface Notifier {
    boolean notify(RequestRow row);
  }

  private final AuthRequestRepository requests;
  private final LoggerAdapter logger;
  private final String nodeId;
  private final Notifier notifier;
  private final long leaseMillis;
  /** Injected so the lease lapsing — the retry path — is an assertion rather than a sleep. */
  private final java.time.Clock clock;

  public AuthRequestCourier(AuthRequestRepository requests, LoggerAdapter logger, String nodeId,
      Notifier notifier, long leaseMillis) {
    this(requests, logger, nodeId, notifier, leaseMillis, java.time.Clock.systemUTC());
  }

  public AuthRequestCourier(AuthRequestRepository requests, LoggerAdapter logger, String nodeId,
      Notifier notifier, long leaseMillis, java.time.Clock clock) {
    this.requests = requests;
    this.logger = logger;
    this.nodeId = nodeId;
    this.notifier = notifier;
    this.leaseMillis = leaseMillis;
    this.clock = clock == null ? java.time.Clock.systemUTC() : clock;
  }

  /** Claim and deliver a batch. Safe on a timer, and safe with more than one courier. */
  public void tick() {
    long now = clock.millis();
    List<RequestRow> claimed;
    try {
      claimed = requests.claimPending(nodeId, BATCH, now, leaseMillis);
    } catch (SQLException failed) {
      logger.warning("Could not claim pending auth requests: " + failed.getMessage());
      return;
    }
    for (RequestRow row : claimed) {
      try {
        // Only the row's own guard against double-notification stands between a re-delivery and a
        // second Discord message: markNotified flips state pending->notified atomically, so a row
        // that two polls raced over is sent exactly once. The boolean returned here is what stops a
        // delivery that never reached a live socket from being recorded as sent — the difference
        // between "try again next poll" and "lost forever".
        if (notifier.notify(row)) {
          requests.markNotified(row.requestId(), now);
        }
      } catch (SQLException | RuntimeException failed) {
        // Deliberately NOT marked notified: the lease lapses and the row is offered again.
        logger.warning("Could not deliver auth request " + row.requestId()
            + " for " + row.displayName() + ": " + failed.getMessage());
      }
    }
  }
}
