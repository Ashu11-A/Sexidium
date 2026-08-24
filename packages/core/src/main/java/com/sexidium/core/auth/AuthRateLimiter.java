package com.sexidium.core.auth;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sliding-window bounds on the two things a stranger can make this system do for free: run a gate
 * query, and send someone a Discord message.
 *
 * <p><b>In-memory, and correct for exactly one gate node.</b> The proxy is the only login gate in
 * this topology, so a per-JVM window is a per-network window. A second proxy would need these counts
 * in the database — a small, well-isolated change, called out here rather than discovered later.</p>
 *
 * <p>The clock is injected so the window rollover is a test rather than a sleep.</p>
 */
public final class AuthRateLimiter {

  /** One counter and the window it belongs to. Replaced wholesale when the window rolls over. */
  private record Window(long startedAt, AtomicInteger count) {
  }

  private final Clock clock;
  private final Map<String, Window> loginsPerIp = new ConcurrentHashMap<>();
  private final Map<String, Window> requestsPerIdentity = new ConcurrentHashMap<>();
  private final Map<String, Window> requestsPerIp = new ConcurrentHashMap<>();

  private volatile int maxLoginsPerMinutePerIp = 10;
  private volatile int maxRequestsPerHourPerIdentity = 6;
  private volatile int maxRequestsPerHourPerIp = 20;

  /**
   * When the maps were last swept of stale windows. Pruning is piggy-backed on {@link #allowLogin}
   * rather than on a scheduler so every gate node self-maintains without a task of its own; a
   * volatile long is enough because the worst case of a missed race is two prunes in one window.
   */
  private volatile long lastPruneMillis;
  private static final long PRUNE_INTERVAL_MILLIS = 60_000L;

  public AuthRateLimiter() {
    this(Clock.systemUTC());
  }

  public AuthRateLimiter(Clock clock) {
    this.clock = clock == null ? Clock.systemUTC() : clock;
  }

  public AuthRateLimiter limits(int loginsPerMinutePerIp, int requestsPerHourPerIdentity,
      int requestsPerHourPerIp) {
    this.maxLoginsPerMinutePerIp = loginsPerMinutePerIp;
    this.maxRequestsPerHourPerIdentity = requestsPerHourPerIdentity;
    this.maxRequestsPerHourPerIp = requestsPerHourPerIp;
    return this;
  }

  /** Whether another gate evaluation from this network is allowed right now. */
  public boolean allowLogin(String ipHash) {
    maybePrune();
    return allow(loginsPerIp, ipHash, maxLoginsPerMinutePerIp, 60_000L);
  }

  /**
   * Whether another Discord approval may be sent for this (identity, network).
   *
   * <p>Both bounds are consulted, and BOTH are consumed only when both pass — a request refused by
   * the identity bound must not also burn the network's quota, or one noisy name would lock out
   * every other player behind the same address.</p>
   */
  public boolean allowRequest(String identityId, String ipHash) {
    if (peek(requestsPerIdentity, identityId, maxRequestsPerHourPerIdentity, 3_600_000L) < 0) {
      return false;
    }
    if (peek(requestsPerIp, ipHash, maxRequestsPerHourPerIp, 3_600_000L) < 0) {
      return false;
    }
    allow(requestsPerIdentity, identityId, maxRequestsPerHourPerIdentity, 3_600_000L);
    allow(requestsPerIp, ipHash, maxRequestsPerHourPerIp, 3_600_000L);
    return true;
  }

  /** Seconds until the login window rolls over, for the "wait and try again" message. */
  public long loginRetrySeconds(String ipHash) {
    Window window = loginsPerIp.get(key(ipHash));
    if (window == null) {
      return 0L;
    }
    long remaining = 60_000L - (clock.millis() - window.startedAt());
    return Math.max(1L, (remaining + 999L) / 1000L);
  }

  /** Forget every window. Used by tests and by a config reload that changes the bounds. */
  public void reset() {
    loginsPerIp.clear();
    requestsPerIdentity.clear();
    requestsPerIp.clear();
    lastPruneMillis = clock.millis();
  }

  /**
   * Drop entries whose window has elapsed, so a flood of distinct IPs or identities cannot grow these
   * maps without bound. A rolled-over window is re-created on its next access, so an entry that is
   * never touched again is simply garbage once its window has passed — which is exactly what this
   * removes. Invoked at most once per {@link #PRUNE_INTERVAL_MILLIS} from the hot path.
   */
  private void maybePrune() {
    long now = clock.millis();
    long cutoff = lastPruneMillis;
    if (now - cutoff < PRUNE_INTERVAL_MILLIS) {
      return;
    }
    lastPruneMillis = now;
    prune(loginsPerIp, now, 60_000L);
    prune(requestsPerIdentity, now, 3_600_000L);
    prune(requestsPerIp, now, 3_600_000L);
  }

  private static void prune(Map<String, Window> windows, long now, long windowMillis) {
    windows.entrySet().removeIf(entry -> {
      Window window = entry.getValue();
      return window == null || now - window.startedAt() >= windowMillis;
    });
  }

  private boolean allow(Map<String, Window> windows, String rawKey, int max, long windowMillis) {
    if (max <= 0) {
      return true; // 0 or less disables the bound outright, which is what an operator means by it.
    }
    String key = key(rawKey);
    long now = clock.millis();
    Window window = windows.compute(key, (ignored, current) ->
        current == null || now - current.startedAt() >= windowMillis
            ? new Window(now, new AtomicInteger())
            : current);
    return window.count().incrementAndGet() <= max;
  }

  /** How much headroom is left, or -1 when the bound is already spent. Consumes nothing. */
  private int peek(Map<String, Window> windows, String rawKey, int max, long windowMillis) {
    if (max <= 0) {
      return Integer.MAX_VALUE;
    }
    Window window = windows.get(key(rawKey));
    if (window == null || clock.millis() - window.startedAt() >= windowMillis) {
      return max;
    }
    int remaining = max - window.count().get();
    return remaining <= 0 ? -1 : remaining;
  }

  private static String key(String rawKey) {
    return rawKey == null ? "" : rawKey;
  }
}
