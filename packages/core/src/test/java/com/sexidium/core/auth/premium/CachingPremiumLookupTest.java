package com.sexidium.core.auth.premium;

import com.sexidium.core.auth.AuthIdentity.PremiumState;
import com.sexidium.core.auth.AuthIdentityRepository;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The premium lookup, with a fake Mojang — no test here touches the network.
 *
 * <p>The invariant under test is one-directional: an unverified connection must never be able to
 * take a premium name. Every outage case below is therefore checked for which way it fails.</p>
 */
class CachingPremiumLookupTest {

  /** A scriptable Mojang. Counts calls, so the caches can be proven to be caches. */
  private static final class FakeMojangApiClient implements MojangApiClient {
    private String uuid;
    private boolean unreachable;
    private int calls;

    @Override
    public Optional<String> uuidForName(String name) throws IOException {
      calls++;
      if (unreachable) {
        throw new IOException("Mojang is down");
      }
      return Optional.ofNullable(uuid);
    }
  }

  private static final class MovableClock extends Clock {
    private long millis = 1_000_000L;

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

  private static final CachingPremiumLookup.Settings SETTINGS =
      new CachingPremiumLookup.Settings(6_000L, 2_000L, 100_000L, 3);

  private Database db;
  private AuthIdentityRepository identities;
  private FakeMojangApiClient api;
  private MovableClock clock;
  private CachingPremiumLookup lookup;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("premium-test");
    db = new Database(dir.resolve("premium.db").toFile());
    identities = new AuthIdentityRepository(db);
    api = new FakeMojangApiClient();
    clock = new MovableClock();
    lookup = new CachingPremiumLookup(api, identities, new StdoutLoggerAdapter("T"), clock, SETTINGS);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("a name Mojang knows is premium, and its uuid comes back with it")
  void premiumVerdict() throws Exception {
    identities.resolveOrCreate("Notch", clock.millis());
    api.uuid = "069a79f4";

    PremiumLookup.Verdict verdict = lookup.lookup("Notch");

    assertEquals(PremiumLookup.State.PREMIUM, verdict.state());
    assertEquals("069a79f4", verdict.premiumUuid());
    assertEquals(PremiumState.PREMIUM, identities.find("notch").premiumState());
  }

  @Test
  @DisplayName("a name Mojang does not know is cracked, and that is recorded too")
  void crackedVerdict() throws Exception {
    identities.resolveOrCreate("Steve", clock.millis());
    api.uuid = null;

    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("Steve").state());
    assertEquals(PremiumState.CRACKED, identities.find("steve").premiumState());
  }

  @Test
  @DisplayName("a name that is not a Java name at all is never sent to Mojang")
  void invalidNamesAreNeverLookedUp() {
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup(".BedrockPlayer").state());
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("ab").state());
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("this-name-is-far-too-long").state());
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup(null).state());
    assertEquals(0, api.calls, "spending the token bucket to learn what the regex already said");
  }

  @Test
  @DisplayName("the in-memory cache absorbs a reconnect storm")
  void l1CacheAbsorbsRepeats() throws Exception {
    identities.resolveOrCreate("Notch", clock.millis());
    api.uuid = "069a79f4";

    lookup.lookup("Notch");
    lookup.lookup("notch");
    lookup.lookup("NOTCH");

    assertEquals(1, api.calls);
  }

  @Test
  @DisplayName("the negative cache expires sooner than the positive one")
  void ttlsAreSeparate() throws Exception {
    identities.resolveOrCreate("Steve", clock.millis());
    api.uuid = null;
    lookup.lookup("Steve");
    assertEquals(1, api.calls);

    clock.advance(3_000L);
    // Past the 2s negative TTL, but the DURABLE cracked verdict is still inside the re-check
    // window, so this is still answered without a call.
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("Steve").state());
    assertEquals(1, api.calls);
  }

  @Test
  @DisplayName("past the re-check window the name is asked about again")
  void recheckWindowExpires() throws Exception {
    identities.resolveOrCreate("Steve", clock.millis());
    api.uuid = null;
    lookup.lookup("Steve");

    clock.advance(200_000L);
    lookup.lookup("Steve");

    assertEquals(2, api.calls);
  }

  @Test
  @DisplayName("a durable PREMIUM verdict wins during an outage — the conservative direction")
  void durablePremiumSurvivesAnOutage() throws Exception {
    identities.resolveOrCreate("Notch", clock.millis());
    identities.recordPremium("notch", "069a79f4", PremiumState.PREMIUM, clock.millis());
    api.unreachable = true;
    clock.advance(200_000L);

    PremiumLookup.Verdict verdict = lookup.lookup("Notch");

    assertEquals(PremiumLookup.State.PREMIUM, verdict.state());
    assertEquals("069a79f4", verdict.premiumUuid());
  }

  @Test
  @DisplayName("a durable CRACKED verdict wins during an outage too")
  void durableCrackedSurvivesAnOutage() throws Exception {
    identities.resolveOrCreate("Steve", clock.millis());
    identities.recordPremium("steve", null, PremiumState.CRACKED, clock.millis());
    api.unreachable = true;
    clock.advance(200_000L);

    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("Steve").state());
  }

  @Test
  @DisplayName("a name we have NEVER resolved is UNAVAILABLE during an outage, never 'cracked'")
  void unknownDuringAnOutageIsUnavailable() throws Exception {
    identities.resolveOrCreate("Stranger", clock.millis());
    api.unreachable = true;

    assertEquals(PremiumLookup.State.UNAVAILABLE, lookup.lookup("Stranger").state(),
        "collapsing UNAVAILABLE into CRACKED is how an impostor takes a name during an outage");
  }

  @Test
  @DisplayName("an exhausted token bucket reads as UNAVAILABLE, so the gate applies its outage policy")
  void exhaustedBucketIsUnavailable() {
    api.uuid = null;

    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("NameOne").state());
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("NameTwo").state());
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("NameSix").state());
    assertEquals(PremiumLookup.State.UNAVAILABLE, lookup.lookup("NameFour").state());
    assertEquals(3, api.calls);
  }

  @Test
  @DisplayName("the bucket refills a minute later")
  void bucketRefills() {
    api.uuid = null;
    lookup.lookup("NameOne");
    lookup.lookup("NameTwo");
    lookup.lookup("NameSix");
    assertEquals(PremiumLookup.State.UNAVAILABLE, lookup.lookup("NameFour").state());

    clock.advance(60_000L);
    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("NameFour").state());
  }

  @Test
  @DisplayName("an exhausted bucket still answers from the durable layer")
  void exhaustedBucketFallsBackToDurableState() throws Exception {
    identities.resolveOrCreate("Notch", clock.millis());
    // Recorded long enough ago that the FRESH durable read does not apply; only the outage
    // fallback, which is the path under test, can answer it.
    identities.recordPremium("notch", "069a79f4", PremiumState.PREMIUM, clock.millis() - 200_000L);
    api.uuid = null;
    lookup.lookup("NameOne");
    lookup.lookup("NameTwo");
    lookup.lookup("NameSix");

    assertEquals(PremiumLookup.State.PREMIUM, lookup.lookup("Notch").state());
    assertEquals(3, api.calls, "the leash held: the durable layer answered, Mojang was not asked");
  }

  @Test
  @DisplayName("a bucket of zero disables the leash rather than blocking every lookup")
  void zeroBucketIsUnlimited() {
    CachingPremiumLookup unleashed = new CachingPremiumLookup(api, identities,
        new StdoutLoggerAdapter("T"), clock,
        new CachingPremiumLookup.Settings(6_000L, 2_000L, 100_000L, 0));
    api.uuid = null;

    for (int i = 0; i < 10; i++) {
      assertEquals(PremiumLookup.State.CRACKED, unleashed.lookup("Name" + (char) ('a' + i)).state());
    }
    assertEquals(10, api.calls);
  }

  @Test
  @DisplayName("with no durable layer attached the lookup still answers, it just asks more often")
  void worksWithoutTheDurableLayer() {
    CachingPremiumLookup detached = new CachingPremiumLookup(api, null,
        new StdoutLoggerAdapter("T"), null, null);
    api.uuid = "069a79f4";

    assertEquals(PremiumLookup.State.PREMIUM, detached.lookup("Notch").state());

    api.unreachable = true;
    detached.attachIdentities(identities);
    assertNotNull(detached.lookup("Somebody"));
  }

  @Test
  @DisplayName("a database that will not answer degrades to a live lookup, not to a crash")
  void aClosedDatabaseIsSurvivable() throws Exception {
    identities.resolveOrCreate("Steve", clock.millis());
    db.close();
    api.uuid = null;

    assertEquals(PremiumLookup.State.CRACKED, lookup.lookup("Steve").state());
  }

  @Test
  @DisplayName("the Java-name guard is the second net behind the Bedrock check")
  void javaNameGuard() {
    assertTrue(CachingPremiumLookup.looksLikeJavaName("Notch"));
    assertTrue(CachingPremiumLookup.looksLikeJavaName("a_b_1"));
    assertFalse(CachingPremiumLookup.looksLikeJavaName(".Bedrock"));
    assertFalse(CachingPremiumLookup.looksLikeJavaName("has space"));
    assertFalse(CachingPremiumLookup.looksLikeJavaName(null));
  }

  @Test
  @DisplayName("the default settings are the documented ones")
  void defaults() {
    CachingPremiumLookup.Settings defaults = CachingPremiumLookup.Settings.defaults();
    assertEquals(21_600_000L, defaults.positiveTtlMillis());
    assertEquals(1_800_000L, defaults.negativeTtlMillis());
    assertEquals(120, defaults.maxLookupsPerMinute());
  }
}
