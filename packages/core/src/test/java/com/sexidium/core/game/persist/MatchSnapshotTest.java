package com.sexidium.core.game.persist;

import com.sexidium.core.game.GameState;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MatchSnapshotTest {

  @Test
  void constructor_setsMatchIdAndModeId() {
    UUID id = UUID.randomUUID();
    MatchSnapshot snap = new MatchSnapshot(id, "combat");
    assertEquals(id, snap.matchId);
    assertEquals("combat", snap.modeId);
  }

  @Test
  void defaultState_isRunning() {
    MatchSnapshot snap = new MatchSnapshot(UUID.randomUUID(), "m");
    assertEquals(GameState.RUNNING, snap.state);
  }

  @Test
  void player_whenAbsent_returnsNull() {
    MatchSnapshot snap = new MatchSnapshot(UUID.randomUUID(), "m");
    assertNull(snap.player(UUID.randomUUID()));
  }

  @Test
  void getOrCreatePlayer_createsNewPlayer() {
    MatchSnapshot snap = new MatchSnapshot(UUID.randomUUID(), "m");
    UUID playerId = UUID.randomUUID();
    PlayerSnapshot ps = snap.getOrCreatePlayer(playerId, "Steve", "fighter");
    assertNotNull(ps);
    assertEquals(playerId, ps.playerId);
    assertEquals("Steve", ps.playerName);
    assertEquals("fighter", ps.role);
  }

  @Test
  void getOrCreatePlayer_returnsExistingPlayer() {
    MatchSnapshot snap = new MatchSnapshot(UUID.randomUUID(), "m");
    UUID playerId = UUID.randomUUID();
    PlayerSnapshot first = snap.getOrCreatePlayer(playerId, "Steve", "fighter");
    PlayerSnapshot second = snap.getOrCreatePlayer(playerId, "OtherName", "other");
    assertSame(first, second);
  }

  @Test
  void player_findsAddedPlayer() {
    MatchSnapshot snap = new MatchSnapshot(UUID.randomUUID(), "m");
    UUID playerId = UUID.randomUUID();
    snap.getOrCreatePlayer(playerId, "Alex", "runner");
    assertNotNull(snap.player(playerId));
  }

  @Test
  void data_props_isReadWrite() {
    MatchSnapshot snap = new MatchSnapshot(UUID.randomUUID(), "m");
    snap.data.set("round", 2);
    assertEquals(2, snap.data.getInt("round", 0));
  }
}
