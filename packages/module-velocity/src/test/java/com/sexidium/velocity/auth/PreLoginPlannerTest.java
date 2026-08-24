package com.sexidium.velocity.auth;

import com.sexidium.core.auth.premium.PremiumLookup;
import com.sexidium.velocity.auth.PreLoginPlanner.Plan;
import com.sexidium.velocity.auth.PreLoginPlanner.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every branch of the pre-login decision.
 *
 * <p>The Velocity API is {@code compileOnly}, so a listener cannot be instantiated in a test — which
 * is precisely why the decision lives here as data and {@code VelocityAuthGate} is a four-way
 * mapping with no branching of its own.</p>
 *
 * <p>The invariant every case below is checked against runs one way: an unverified connection must
 * never be able to take a premium name.</p>
 */
class PreLoginPlannerTest {

  private static final Settings PREMIUM_ON = Settings.of(true, true, true, "deny");
  private static final Settings PREMIUM_OFF = Settings.of(false, true, true, "deny");

  @Test
  @DisplayName("with premium off the proxy's own online-mode decides, as it does today")
  void premiumDisabledLeavesItAlone() {
    assertEquals(Plan.ALLOW, PreLoginPlanner.plan("Notch", false, false,
        PremiumLookup.Verdict.premium("069a79f4"), PREMIUM_OFF));
  }

  @Test
  @DisplayName("a name Mojang holds is forced through the session handshake")
  void premiumIsForcedOnline() {
    assertEquals(Plan.ONLINE, PreLoginPlanner.plan("Notch", false, false,
        PremiumLookup.Verdict.premium("069a79f4"), PREMIUM_ON));
  }

  @Test
  @DisplayName("a name Mojang does not hold is offline, which is the ordinary cracked player")
  void crackedIsForcedOffline() {
    assertEquals(Plan.OFFLINE, PreLoginPlanner.plan("Steve", false, false,
        PremiumLookup.Verdict.cracked(), PREMIUM_ON));
  }

  @Test
  @DisplayName("Bedrock short-circuits everything: Xbox Live already authenticated them")
  void bedrockIsAlwaysOffline() {
    assertEquals(Plan.OFFLINE, PreLoginPlanner.plan(".Phone", true, true,
        PremiumLookup.Verdict.premium("069a79f4"), PREMIUM_ON),
        "forcing online mode on a Bedrock account would disconnect every Bedrock player");
  }

  @Test
  @DisplayName("with the Bedrock path off, a prefixed name still never reaches Mojang")
  void floodgatePrefixIsASecondNet() {
    Settings bedrockOff = Settings.of(true, true, false, "deny");
    assertEquals(Plan.OFFLINE, PreLoginPlanner.plan(".Phone", true, false, null, bedrockOff));
  }

  @Test
  @DisplayName("a name Mojang could not hold anyway is offline without a lookup")
  void invalidJavaNamesAreOffline() {
    assertEquals(Plan.OFFLINE, PreLoginPlanner.plan("ab", false, false, null, PREMIUM_ON));
    assertEquals(Plan.OFFLINE, PreLoginPlanner.plan("a-name-with-dashes", false, false, null, PREMIUM_ON));
    assertEquals(Plan.OFFLINE, PreLoginPlanner.plan(null, false, false, null, PREMIUM_ON));
  }

  @Test
  @DisplayName("during an outage a name we KNOW is premium is still forced online")
  void outageOnAKnownPremiumNameStaysOnline() {
    assertEquals(Plan.ONLINE, PreLoginPlanner.plan("Notch", false, true,
        PremiumLookup.Verdict.unavailable(), PREMIUM_ON),
        "the conservative direction: a real owner still authenticates, an impostor cannot");
  }

  @Test
  @DisplayName("during an outage a name we have NEVER resolved is refused, not admitted")
  void outageOnAnUnknownNameDenies() {
    assertEquals(Plan.DENY, PreLoginPlanner.plan("Stranger", false, false,
        PremiumLookup.Verdict.unavailable(), PREMIUM_ON),
        "admitting it is the only outcome that can hand an unknown name to an impostor");
  }

  @Test
  @DisplayName("unknown-on-outage: offline trades that safety for availability, as documented")
  void outagePolicyOfflineIsHonoured() {
    Settings lenient = Settings.of(true, true, true, "offline");
    assertEquals(Plan.OFFLINE, PreLoginPlanner.plan("Stranger", false, false,
        PremiumLookup.Verdict.unavailable(), lenient));
  }

  @Test
  @DisplayName("with name protection off, an outage on a known-premium name degrades to the policy")
  void protectionOffChangesTheOutageAnswer() {
    Settings unprotected = Settings.of(true, false, true, "deny");
    assertEquals(Plan.DENY, PreLoginPlanner.plan("Notch", false, true,
        PremiumLookup.Verdict.unavailable(), unprotected));
  }

  @Test
  @DisplayName("a missing verdict is treated as an outage, never as 'cracked'")
  void aNullVerdictIsAnOutage() {
    assertEquals(Plan.DENY, PreLoginPlanner.plan("Stranger", false, false, null, PREMIUM_ON));
  }

  @Test
  @DisplayName("the outage policy parses case-insensitively and defaults to deny")
  void settingsParsing() {
    assertTrue(Settings.of(true, true, true, null).denyOnUnknownOutage());
    assertTrue(Settings.of(true, true, true, "").denyOnUnknownOutage());
    assertTrue(Settings.of(true, true, true, "nonsense").denyOnUnknownOutage());
    assertFalse(Settings.of(true, true, true, "  OFFLINE  ").denyOnUnknownOutage());
  }
}
