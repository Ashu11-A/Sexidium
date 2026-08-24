package com.sexidium.core.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The node-local register of frozen players — and the promise that nobody is kicked twice. */
class AuthHoldServiceTest {

  private AuthHoldService holds;
  private UUID player;

  @BeforeEach
  void setUp() {
    holds = new AuthHoldService();
    player = UUID.randomUUID();
  }

  @Test
  @DisplayName("a held player is remembered with everything release will need to undo")
  void holdRemembersTheRestoreState() {
    holds.hold(player, "id-1", "req-1", "SURVIVAL", 10_000L);

    AuthHoldService.Hold hold = holds.of(player).orElseThrow();
    assertEquals("id-1", hold.identityId());
    assertEquals("req-1", hold.requestId());
    assertEquals("SURVIVAL", hold.previousGameMode());
    assertEquals(10_000L, hold.deadline());
    assertTrue(holds.isHeld(player));
  }

  @Test
  @DisplayName("a player nobody is holding is simply not held")
  void unknownPlayersAreNotHeld() {
    assertFalse(holds.isHeld(UUID.randomUUID()));
    assertFalse(holds.isHeld(null));
    assertTrue(holds.of(null).isEmpty());
    assertTrue(holds.of(UUID.randomUUID()).isEmpty());
  }

  @Test
  @DisplayName("a null player id is ignored rather than stored under a null key")
  void nullPlayerIsIgnored() {
    holds.hold(null, "id-1", "req-1", "SURVIVAL", 1L);
    assertTrue(holds.all().isEmpty());
  }

  @Test
  @DisplayName("the deciding node addresses a hold by identity, because that is all it knows")
  void byIdentity() {
    holds.hold(player, "id-1", "req-1", "SURVIVAL", 10_000L);

    assertEquals(player, holds.byIdentity("id-1").orElseThrow().playerId());
    assertTrue(holds.byIdentity("id-2").isEmpty());
    assertTrue(holds.byIdentity(null).isEmpty());
  }

  @Test
  @DisplayName("release hands back what was held, so the caller can restore it")
  void releaseReturnsTheHold() {
    holds.hold(player, "id-1", "req-1", "CREATIVE", 10_000L);

    assertEquals("CREATIVE", holds.release(player).orElseThrow().previousGameMode());
    assertFalse(holds.isHeld(player));
    assertTrue(holds.release(player).isEmpty());
    assertTrue(holds.release(null).isEmpty());
  }

  @Test
  @DisplayName("expiry returns each entry exactly once, so a one-second tick cannot kick twice")
  void tickExpiredIsExactlyOnce() {
    UUID other = UUID.randomUUID();
    holds.hold(player, "id-1", "req-1", "SURVIVAL", 5_000L);
    holds.hold(other, "id-2", "req-2", "SURVIVAL", 50_000L);

    List<AuthHoldService.Hold> first = holds.tickExpired(10_000L);
    assertEquals(1, first.size());
    assertEquals(player, first.get(0).playerId());

    assertTrue(holds.tickExpired(10_000L).isEmpty());
    assertTrue(holds.isHeld(other));
  }

  @Test
  @DisplayName("a deadline exactly on the tick has passed, not 'nearly'")
  void deadlineIsInclusive() {
    holds.hold(player, "id-1", "req-1", "SURVIVAL", 5_000L);
    assertEquals(1, holds.tickExpired(5_000L).size());
  }

  @Test
  @DisplayName("clear drops everything, which is what a shutdown needs")
  void clear() {
    holds.hold(player, "id-1", "req-1", "SURVIVAL", 10_000L);
    holds.clear();
    assertTrue(holds.all().isEmpty());
  }
}
