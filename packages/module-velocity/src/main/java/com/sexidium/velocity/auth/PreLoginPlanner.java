package com.sexidium.velocity.auth;

import com.sexidium.core.auth.premium.CachingPremiumLookup;
import com.sexidium.core.auth.premium.PremiumLookup;

import java.util.Locale;

/**
 * Decides, as pure data, what a {@code PreLoginEvent} should become.
 *
 * <p>Separated from the listener deliberately: the Velocity API is {@code compileOnly}, so nothing
 * that touches {@code PreLoginComponentResult} can be instantiated in a test. Keeping every branch
 * here means the untestable half ({@link VelocityAuthGate}) is a straight four-way mapping with no
 * decisions of its own.</p>
 */
public final class PreLoginPlanner {

  /** What the proxy should do with a connection before the Mojang handshake begins. */
  public enum Plan {
    /** Force online mode: the client must prove ownership to Mojang. */
    ONLINE,
    /** Force offline mode: no Mojang session is possible or wanted. */
    OFFLINE,
    /** Leave the proxy's own {@code online-mode} to decide. */
    ALLOW,
    /** Refuse now, because letting this through could hand a verified name to an impostor. */
    DENY
  }

  /** The handful of {@code auth.premium.*} answers this decision needs. */
  public record Settings(
      boolean premiumEnabled,
      boolean protectVerifiedNames,
      boolean bedrockEnabled,
      /** {@code deny} (default) or {@code offline}: what to do when Mojang is unreachable and the
       *  name has never been resolved. */
      boolean denyOnUnknownOutage) {

    public static Settings of(boolean premiumEnabled, boolean protectVerifiedNames,
        boolean bedrockEnabled, String unknownOnOutage) {
      String policy = unknownOnOutage == null ? "deny" : unknownOnOutage.trim().toLowerCase(Locale.ROOT);
      return new Settings(premiumEnabled, protectVerifiedNames, bedrockEnabled, !"offline".equals(policy));
    }
  }

  private PreLoginPlanner() {
  }

  /**
   * The whole pre-login decision.
   *
   * @param knownPremium whether {@code player_identities} already records this name as premium,
   *                     which is what makes the outage answer safe rather than a guess
   */
  public static Plan plan(String username, boolean bedrock, boolean knownPremium,
      PremiumLookup.Verdict verdict, Settings settings) {
    // Bedrock first, and it short-circuits everything: a Bedrock account has no Mojang session, so
    // forcing online mode would disconnect every Bedrock player on the network. Their names are
    // never sent to Mojang either.
    if (bedrock && settings.bedrockEnabled()) {
      return Plan.OFFLINE;
    }
    if (!settings.premiumEnabled()) {
      return Plan.ALLOW;
    }
    // A name Mojang could not hold anyway (too short, too long, or Floodgate-prefixed). The regex is
    // a second, independent net behind the Bedrock check above.
    if (!CachingPremiumLookup.looksLikeJavaName(username)) {
      return Plan.OFFLINE;
    }
    PremiumLookup.Verdict resolved = verdict == null ? PremiumLookup.Verdict.unavailable() : verdict;
    return switch (resolved.state()) {
      case PREMIUM -> Plan.ONLINE;
      case CRACKED -> Plan.OFFLINE;
      case UNAVAILABLE -> unavailable(knownPremium, settings);
    };
  }

  /**
   * Mojang is not answering.
   *
   * <p>A name we already know is premium is forced ONLINE regardless — that is the conservative
   * direction, because a real owner's client authenticates against a different Mojang service that
   * is often up when the name API is down, and an impostor simply cannot. A name we have never
   * resolved is refused, since letting it through is the only outcome that can hand it away.</p>
   */
  private static Plan unavailable(boolean knownPremium, Settings settings) {
    if (knownPremium && settings.protectVerifiedNames()) {
      return Plan.ONLINE;
    }
    return settings.denyOnUnknownOutage() ? Plan.DENY : Plan.OFFLINE;
  }
}
