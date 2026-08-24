package com.sexidium.core.auth.premium;

/**
 * "Does this name belong to a Mojang account?"
 *
 * <p>Three answers, and the third is not a failure to be swallowed: {@link State#UNAVAILABLE} means
 * we do not know, and the gate has a documented, deliberately conservative policy for exactly that
 * case ({@code auth.premium.unknown-on-outage}). Collapsing it into {@code CRACKED} is how an
 * impostor takes a premium name during an outage.</p>
 */
@FunctionalInterface
public interface PremiumLookup {

  enum State {
    PREMIUM,
    CRACKED,
    UNAVAILABLE
  }

  /** The verdict, plus Mojang's uuid when there is one. */
  record Verdict(State state, String premiumUuid) {

    public static Verdict premium(String premiumUuid) {
      return new Verdict(State.PREMIUM, premiumUuid);
    }

    public static Verdict cracked() {
      return new Verdict(State.CRACKED, null);
    }

    public static Verdict unavailable() {
      return new Verdict(State.UNAVAILABLE, null);
    }
  }

  Verdict lookup(String username);
}
