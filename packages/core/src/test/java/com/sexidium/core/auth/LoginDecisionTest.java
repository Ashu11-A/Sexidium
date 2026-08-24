package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginDecisionTest {

  @Test
  void allow_isAllowed() {
    LoginDecision d = LoginDecision.allow();
    assertTrue(d.allowed());
    assertEquals("", d.rejectionMessage());
  }

  @Test
  void reject_isNotAllowed() {
    LoginDecision d = LoginDecision.reject("Please link Discord.");
    assertFalse(d.allowed());
    assertEquals("Please link Discord.", d.rejectionMessage());
  }

  @Test
  void reject_nullMessage_becomesEmpty() {
    LoginDecision d = LoginDecision.reject(null);
    assertFalse(d.allowed());
    assertEquals("", d.rejectionMessage());
  }

  @Test
  void equality_byValue() {
    assertEquals(LoginDecision.allow(), LoginDecision.allow());
    assertEquals(LoginDecision.reject("x"), LoginDecision.reject("x"));
    assertNotEquals(LoginDecision.allow(), LoginDecision.reject("x"));
  }
}
