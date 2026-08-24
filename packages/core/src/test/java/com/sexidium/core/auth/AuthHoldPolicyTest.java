package com.sexidium.core.auth;

import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The hold's config contract. Every protection defaults ON and the feature itself defaults OFF,
 * which is the only combination that is safe to ship: this is the one path that lets an unverified
 * connection reach a world.
 */
class AuthHoldPolicyTest {

  private PropertiesConfigurationAdapter config;
  private AuthHoldPolicy policy;

  @BeforeEach
  void setUp() {
    config = new PropertiesConfigurationAdapter();
    policy = new AuthHoldPolicy(config);
  }

  @Test
  @DisplayName("the hold is off until an operator turns it on, deliberately")
  void disabledByDefault() {
    assertFalse(policy.enabled());
    config.set("auth.hold.enabled", "true");
    assertTrue(policy.enabled());
  }

  @Test
  @DisplayName("every protection is on by default, so enabling the hold cannot half-enable the freeze")
  void protectionsDefaultOn() {
    assertTrue(policy.blockMove());
    assertTrue(policy.blockChat());
    assertTrue(policy.blockCommands());
    assertTrue(policy.hideFromOthers());
    assertTrue(policy.spectator());
  }

  @Test
  @DisplayName("each protection can be turned off independently")
  void protectionsAreIndividuallyOverridable() {
    config.set("auth.hold.block-move", "false");
    config.set("auth.hold.block-chat", "false");
    config.set("auth.hold.block-commands", "false");
    config.set("auth.hold.hide-from-others", "false");
    config.set("auth.hold.spectator", "false");

    assertFalse(policy.blockMove());
    assertFalse(policy.blockChat());
    assertFalse(policy.blockCommands());
    assertFalse(policy.hideFromOthers());
    assertFalse(policy.spectator());
  }

  @Test
  @DisplayName("the timeout is two minutes by default, shorter than the request TTL on purpose")
  void timeoutDefault() {
    assertEquals(120_000L, policy.timeoutMillis());
  }

  @Test
  @DisplayName("a configured timeout is honoured, and a nonsense one is floored rather than obeyed")
  void timeoutIsFloored() {
    config.set("auth.hold.timeout-seconds", "300");
    assertEquals(300_000L, policy.timeoutMillis());

    config.set("auth.hold.timeout-seconds", "0");
    assertEquals(10_000L, policy.timeoutMillis(),
        "a zero-second hold would kick the player before the Discord message arrived");
  }
}
