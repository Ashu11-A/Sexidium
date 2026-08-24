package com.sexidium.core.auth;

import com.sexidium.core.platform.ConfigurationAdapter;

/**
 * What an in-world hold is allowed to do, read straight from {@code auth.hold.*}.
 *
 * <p>Pure config, no platform types — the same shape {@link com.sexidium.core.world.LobbyGuardPolicy}
 * has, and for the same reason: the adapter hooks native events and asks this what to cancel, so the
 * decision is testable without a server.</p>
 *
 * <p>This is the only part of the auth design that lets an UNVERIFIED connection reach a world, which
 * is why it is off by default and why every protection below defaults to ON when it is turned on.</p>
 */
public final class AuthHoldPolicy {

  private final ConfigurationAdapter configuration;

  public AuthHoldPolicy(ConfigurationAdapter configuration) {
    this.configuration = configuration;
  }

  public boolean enabled() {
    return configuration.getBoolean("auth.hold.enabled", false);
  }

  /**
   * How long a player may stay frozen before being kicked.
   *
   * <p>Deliberately shorter than the request TTL, so the player lands back on the disconnect screen —
   * which has a Reconnect button — while their approval is still valid.</p>
   */
  public long timeoutMillis() {
    return Math.max(10L, configuration.getLong("auth.hold.timeout-seconds", 120L)) * 1000L;
  }

  public boolean blockMove() {
    return flag("block-move", true);
  }

  public boolean blockChat() {
    return flag("block-chat", true);
  }

  public boolean blockCommands() {
    return flag("block-commands", true);
  }

  /** Mutual invisibility: a held player is neither seen nor sees, i.e. socially inert as well. */
  public boolean hideFromOthers() {
    return flag("hide-from-others", true);
  }

  public boolean spectator() {
    return flag("spectator", true);
  }

  private boolean flag(String key, boolean defaultValue) {
    return configuration.getBoolean("auth.hold." + key, defaultValue);
  }
}
