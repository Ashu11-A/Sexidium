package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthResults.DecisionOutcome;
import com.sexidium.core.auth.AuthResults.GateOutcome;
import com.sexidium.core.auth.AuthResults.SessionView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gate's answer type. The load-bearing part is that a HOLD is an ALLOW: a held player reaches a
 * world (frozen), and a caller that only understands yes/no must not read it as a refusal.
 */
class AuthGateOutcomeTest {

  @Test
  @DisplayName("allow carries no message and no request")
  void allow() {
    GateOutcome outcome = GateOutcome.allow();
    assertEquals(GateOutcome.Kind.ALLOW, outcome.kind());
    assertTrue(outcome.allowed());
    assertEquals("", outcome.rejectionMessage());
    assertNull(outcome.requestId());
    assertEquals(0L, outcome.expiresAt());
  }

  @Test
  @DisplayName("a hold is an ALLOW, because the player does reach a world")
  void holdIsAllowed() {
    GateOutcome outcome = GateOutcome.hold("req-1", 9_000L);
    assertEquals(GateOutcome.Kind.HOLD, outcome.kind());
    assertTrue(outcome.allowed());
    assertEquals("req-1", outcome.requestId());
    assertEquals(9_000L, outcome.expiresAt());
    assertTrue(outcome.toLoginDecision().allowed());
  }

  @Test
  @DisplayName("a rejection carries its message, and never a null one")
  void reject() {
    assertFalse(GateOutcome.reject("nope").allowed());
    assertEquals("nope", GateOutcome.reject("nope").rejectionMessage());
    assertEquals("", GateOutcome.reject(null).rejectionMessage());
  }

  @Test
  @DisplayName("a pending approval is a rejection that knows which request the player is waiting on")
  void pending() {
    GateOutcome outcome = GateOutcome.pending("check discord", "req-1", 9_000L);
    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL, outcome.kind());
    assertFalse(outcome.allowed());
    assertEquals("req-1", outcome.requestId());
    assertEquals("", GateOutcome.pending(null, "req-1", 1L).rejectionMessage());
  }

  @Test
  @DisplayName("the yes/no projection keeps the message, which is what the disconnect screen shows")
  void toLoginDecisionCarriesTheMessage() {
    assertFalse(GateOutcome.reject("because").toLoginDecision().allowed());
    assertEquals("because", GateOutcome.reject("because").toLoginDecision().rejectionMessage());
    assertEquals("", GateOutcome.allow().toLoginDecision().rejectionMessage());
  }

  @Test
  @DisplayName("every decision status has a distinct token, because the bot switches on them")
  void decisionTokensAreDistinct() {
    java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
    for (DecisionOutcome.Status status : DecisionOutcome.Status.values()) {
      tokens.add(new DecisionOutcome(status, "Steve", 0).statusToken());
    }
    assertEquals(DecisionOutcome.Status.values().length, tokens.size());
    assertTrue(tokens.contains("already-decided"));
    assertTrue(tokens.contains("not-found"));
    assertTrue(tokens.contains("forbidden"));
  }

  @Test
  @DisplayName("a decision with no name reports an empty one rather than a null over the wire")
  void decisionNameIsNeverNull() {
    assertEquals("", DecisionOutcome.of(DecisionOutcome.Status.NOT_FOUND, null).minecraftName());
    assertEquals(0, DecisionOutcome.of(DecisionOutcome.Status.DENIED, "Steve").sessionHours());
  }

  @Test
  @DisplayName("every link status has a distinct token too, for the same reason")
  void linkTokensAreDistinct() {
    java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
    for (AuthResults.AuthLinkResult.Status status : AuthResults.AuthLinkResult.Status.values()) {
      tokens.add(new AuthResults.AuthLinkResult(status, "Steve").statusToken());
    }
    assertEquals(AuthResults.AuthLinkResult.Status.values().length, tokens.size());
    assertTrue(tokens.contains("minecraft-already-linked"));
    assertTrue(tokens.contains("discord-already-linked"));
  }

  @Test
  @DisplayName("a session view is exactly what may be shown in Discord and nothing more")
  void sessionView() {
    SessionView view = new SessionView("s1", "steve", "187.61.*.*", "java", 1L, 2L, 3L);
    assertEquals("s1", view.sessionId());
    assertEquals("187.61.*.*", view.ipPrefix());
    assertEquals(3L, view.expiresAt());
  }
}
