package com.sexidium.core.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two bounds a stranger runs into: gate evaluations per network, and Discord messages per
 * account and per network. The clock is injected so a window rollover is an assertion, not a sleep.
 */
class AuthRateLimiterTest {

  /** A clock the test moves by hand. */
  private static final class MovableClock extends Clock {
    private long millis;

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
      return millis;
    }

    void advance(long by) {
      millis += by;
    }
  }

  @Test
  @DisplayName("logins are bounded per network and the bound is exact, not approximate")
  void loginBound() {
    MovableClock clock = new MovableClock();
    AuthRateLimiter limiter = new AuthRateLimiter(clock).limits(3, 6, 20);

    assertTrue(limiter.allowLogin("hash-a"));
    assertTrue(limiter.allowLogin("hash-a"));
    assertTrue(limiter.allowLogin("hash-a"));
    assertFalse(limiter.allowLogin("hash-a"));
  }

  @Test
  @DisplayName("one flooding network does not spend another network's quota")
  void loginBoundsAreIndependentPerNetwork() {
    AuthRateLimiter limiter = new AuthRateLimiter(new MovableClock()).limits(1, 6, 20);

    assertTrue(limiter.allowLogin("hash-a"));
    assertFalse(limiter.allowLogin("hash-a"));
    assertTrue(limiter.allowLogin("hash-b"));
  }

  @Test
  @DisplayName("the window rolls over and the quota comes back")
  void windowRollsOver() {
    MovableClock clock = new MovableClock();
    AuthRateLimiter limiter = new AuthRateLimiter(clock).limits(1, 6, 20);

    assertTrue(limiter.allowLogin("hash-a"));
    assertFalse(limiter.allowLogin("hash-a"));

    clock.advance(60_000L);
    assertTrue(limiter.allowLogin("hash-a"));
  }

  @Test
  @DisplayName("the retry hint counts down inside the window and is zero before it opens")
  void retrySecondsCountDown() {
    MovableClock clock = new MovableClock();
    AuthRateLimiter limiter = new AuthRateLimiter(clock).limits(1, 6, 20);

    assertEquals(0L, limiter.loginRetrySeconds("hash-a"));
    limiter.allowLogin("hash-a");
    assertEquals(60L, limiter.loginRetrySeconds("hash-a"));

    clock.advance(30_000L);
    assertEquals(30L, limiter.loginRetrySeconds("hash-a"));
  }

  @Test
  @DisplayName("approval bounds are per account AND per network, and both must pass")
  void requestBoundsAreBothConsulted() {
    AuthRateLimiter limiter = new AuthRateLimiter(new MovableClock()).limits(100, 2, 3);

    assertTrue(limiter.allowRequest("id-1", "hash-a"));
    assertTrue(limiter.allowRequest("id-1", "hash-a"));
    assertFalse(limiter.allowRequest("id-1", "hash-a"), "the per-identity bound is spent");

    // A different account behind the same address still has its own quota, up to the network bound.
    assertTrue(limiter.allowRequest("id-2", "hash-a"));
    assertFalse(limiter.allowRequest("id-3", "hash-a"), "the per-network bound is spent");
  }

  @Test
  @DisplayName("a request refused by one bound does not burn the other one's quota")
  void arefusedRequestSpendsNothing() {
    AuthRateLimiter limiter = new AuthRateLimiter(new MovableClock()).limits(100, 1, 5);

    assertTrue(limiter.allowRequest("noisy", "hash-a"));
    assertFalse(limiter.allowRequest("noisy", "hash-a"));
    assertFalse(limiter.allowRequest("noisy", "hash-a"));

    // Four neighbours can still be asked: the noisy account's refusals cost the network nothing.
    assertTrue(limiter.allowRequest("id-2", "hash-a"));
    assertTrue(limiter.allowRequest("id-3", "hash-a"));
    assertTrue(limiter.allowRequest("id-4", "hash-a"));
    assertTrue(limiter.allowRequest("id-5", "hash-a"));
    assertFalse(limiter.allowRequest("id-6", "hash-a"));
  }

  @Test
  @DisplayName("a bound of zero or less disables it, which is what an operator means by writing 0")
  void zeroDisablesTheBound() {
    AuthRateLimiter limiter = new AuthRateLimiter(new MovableClock()).limits(0, 0, 0);

    for (int attempt = 0; attempt < 50; attempt++) {
      assertTrue(limiter.allowLogin("hash-a"));
      assertTrue(limiter.allowRequest("id-1", "hash-a"));
    }
    assertEquals(0L, limiter.loginRetrySeconds("hash-a"));
  }

  @Test
  @DisplayName("null keys are a key, not a crash on a connection with no address")
  void nullKeysAreTolerated() {
    AuthRateLimiter limiter = new AuthRateLimiter(null).limits(1, 1, 1);

    assertTrue(limiter.allowLogin(null));
    assertFalse(limiter.allowLogin(null));
    assertTrue(limiter.allowRequest(null, null));
  }

  @Test
  @DisplayName("reset forgets every window, which is what a config reload needs")
  void reset() {
    AuthRateLimiter limiter = new AuthRateLimiter(new MovableClock()).limits(1, 1, 1);

    limiter.allowLogin("hash-a");
    assertFalse(limiter.allowLogin("hash-a"));

    limiter.reset();
    assertTrue(limiter.allowLogin("hash-a"));
  }

  @Test
  @DisplayName("stale windows are pruned on the hot path, so a flood of distinct networks cannot leak")
  void staleWindowsArePruned() {
    MovableClock clock = new MovableClock();
    AuthRateLimiter limiter = new AuthRateLimiter(clock).limits(1, 1, 1);

    limiter.allowLogin("hash-a");
    // A stale-but-present window still answers the retry hint (with 1s, its floor).
    clock.advance(61_000L);
    assertEquals(1L, limiter.loginRetrySeconds("hash-a"));

    // The next login past the prune interval sweeps stale entries out. "hash-a" is gone, so its
    // retry hint collapses to zero rather than holding a dead window in memory forever.
    limiter.allowLogin("hash-b");
    assertEquals(0L, limiter.loginRetrySeconds("hash-a"));
  }
}
