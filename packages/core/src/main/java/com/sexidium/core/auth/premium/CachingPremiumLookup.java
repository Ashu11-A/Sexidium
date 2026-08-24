package com.sexidium.core.auth.premium;

import com.sexidium.core.auth.AuthIdentity;
import com.sexidium.core.auth.AuthIdentity.PremiumState;
import com.sexidium.core.auth.AuthIdentityRepository;
import com.sexidium.core.platform.LoggerAdapter;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Two caches and a leash in front of {@link MojangApiClient}.
 *
 * <ul>
 *   <li><b>L1, in memory</b> — bounded LRU with separate positive/negative TTLs. Absorbs a
 *       reconnect storm.</li>
 *   <li><b>L2, durable</b> — {@code player_identities.premium_state} + {@code premium_checked_at}.
 *       Survives a restart, so a proxy reboot does not re-ask Mojang about every returning player,
 *       and — the part that matters — it is what an OUTAGE falls back on.</li>
 *   <li><b>Token bucket</b> — Mojang documents roughly 600 requests per 10 minutes per source IP;
 *       the default 120/minute keeps a deliberate 2x margin. An exhausted bucket is reported as
 *       {@link State#UNAVAILABLE}, not as {@code CRACKED}, so the gate applies its outage policy
 *       rather than handing a name away.</li>
 * </ul>
 *
 * <p>The failure direction is fixed and one-way: an unverified connection must never take a premium
 * name. A name we already know is premium stays premium through an outage; a name we know is cracked
 * stays cracked; a name we have never resolved is {@code UNAVAILABLE} and the gate decides.</p>
 */
public final class CachingPremiumLookup implements PremiumLookup {

  /** Names above this are dropped oldest-first; 10k covers any realistic connection burst. */
  private static final int MAX_CACHED = 10_000;
  /** A Java name Mojang could possibly know. A Floodgate-prefixed name never matches, by design. */
  private static final java.util.regex.Pattern JAVA_NAME =
      java.util.regex.Pattern.compile("^[A-Za-z0-9_]{3,16}$");

  /** Cache settings, read once at wiring time from {@code auth.premium.*}. */
  public record Settings(
      long positiveTtlMillis,
      long negativeTtlMillis,
      long recheckMillis,
      int maxLookupsPerMinute) {

    public static Settings defaults() {
      return new Settings(21_600_000L, 1_800_000L, 30L * 86_400_000L, 120);
    }
  }

  private record Cached(Verdict verdict, long expiresAt) {
  }

  private final MojangApiClient api;
  /**
   * The durable layer, attached after construction on the proxy.
   *
   * <p>{@code AuthSessionService} owns the repository and takes this lookup as a constructor
   * argument, so one of the two has to be wired second. It is this one, because a lookup with no
   * durable layer still answers correctly — it just re-asks Mojang more often.</p>
   */
  private volatile AuthIdentityRepository identities;
  private final LoggerAdapter logger;
  private final Clock clock;
  private final Settings settings;

  private final Map<String, Cached> cache = Collections.synchronizedMap(
      new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
          return size() > MAX_CACHED;
        }
      });

  private final Object bucketLock = new Object();
  private long bucketStartedAt;
  private int bucketSpent;

  public CachingPremiumLookup(MojangApiClient api, AuthIdentityRepository identities,
      LoggerAdapter logger, Clock clock, Settings settings) {
    this.api = api;
    this.identities = identities;
    this.logger = logger;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.settings = settings == null ? Settings.defaults() : settings;
  }

  /** Attach the durable layer once the service that owns it exists. See the field's javadoc. */
  public void attachIdentities(AuthIdentityRepository identities) {
    this.identities = identities;
  }

  /** Whether a name is even shaped like something Mojang could hold. Never queried when it is not. */
  public static boolean looksLikeJavaName(String username) {
    return username != null && JAVA_NAME.matcher(username).matches();
  }

  @Override
  public Verdict lookup(String username) {
    if (!looksLikeJavaName(username)) {
      // Not a Java name at all (too short, too long, or Floodgate-prefixed). Asking Mojang about it
      // spends the bucket to be told what the regex already said.
      return Verdict.cracked();
    }
    String key = username.toLowerCase(Locale.ROOT);
    long now = clock.millis();

    Cached hit = cache.get(key);
    if (hit != null && hit.expiresAt() > now) {
      return hit.verdict();
    }

    Verdict durable = durableVerdict(key, now, true);
    if (durable != null) {
      remember(key, durable, now);
      return durable;
    }

    if (!takeToken(now)) {
      // Over the leash. Fall back to whatever we know durably, however stale; only a name we have
      // never resolved becomes UNAVAILABLE.
      Verdict stale = durableVerdict(key, now, false);
      return stale == null ? Verdict.unavailable() : stale;
    }

    try {
      Optional<String> premiumUuid = api.uuidForName(username);
      Verdict verdict = premiumUuid.map(Verdict::premium).orElseGet(Verdict::cracked);
      record(key, verdict, now);
      remember(key, verdict, now);
      return verdict;
    } catch (IOException unreachable) {
      if (logger != null) {
        logger.warning("Mojang lookup for " + username + " failed: " + unreachable.getMessage());
      }
      Verdict stale = durableVerdict(key, now, false);
      return stale == null ? Verdict.unavailable() : stale;
    }
  }

  /**
   * What {@code player_identities} already knows.
   *
   * @param fresh when true, only a verdict inside the re-check window counts; when false, any
   *              recorded verdict counts — which is precisely what an outage needs.
   */
  private Verdict durableVerdict(String nameLower, long now, boolean fresh) {
    AuthIdentityRepository repository = identities;
    if (repository == null) {
      return null;
    }
    try {
      AuthIdentity identity = repository.find(nameLower);
      if (identity == null || identity.premiumState() == PremiumState.UNKNOWN) {
        return null;
      }
      if (fresh && now - identity.premiumCheckedAt() >= settings.recheckMillis()) {
        return null;
      }
      return identity.premiumState() == PremiumState.PREMIUM
          ? Verdict.premium(identity.premiumUuid())
          : Verdict.cracked();
    } catch (SQLException unavailable) {
      if (logger != null) {
        logger.warning("Could not read the durable premium state for " + nameLower
            + ": " + unavailable.getMessage());
      }
      return null;
    }
  }

  private void record(String nameLower, Verdict verdict, long now) {
    AuthIdentityRepository repository = identities;
    if (repository == null) {
      return;
    }
    try {
      repository.recordPremium(
          nameLower,
          verdict.premiumUuid(),
          verdict.state() == State.PREMIUM ? PremiumState.PREMIUM : PremiumState.CRACKED,
          now);
    } catch (SQLException failed) {
      // A verdict we could not persist is still a verdict we may use for this connection.
      if (logger != null) {
        logger.warning("Could not persist the premium state for " + nameLower + ": " + failed.getMessage());
      }
    }
  }

  private void remember(String nameLower, Verdict verdict, long now) {
    long ttl = verdict.state() == State.PREMIUM ? settings.positiveTtlMillis() : settings.negativeTtlMillis();
    cache.put(nameLower, new Cached(verdict, now + Math.max(1_000L, ttl)));
  }

  private boolean takeToken(long now) {
    int max = settings.maxLookupsPerMinute();
    if (max <= 0) {
      return true;
    }
    synchronized (bucketLock) {
      if (now - bucketStartedAt >= 60_000L) {
        bucketStartedAt = now;
        bucketSpent = 0;
      }
      if (bucketSpent >= max) {
        return false;
      }
      bucketSpent++;
      return true;
    }
  }
}
