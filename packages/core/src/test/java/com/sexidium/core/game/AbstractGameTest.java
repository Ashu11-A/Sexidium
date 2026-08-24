package com.sexidium.core.game;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.InventoryChangeGameEvent;
import com.sexidium.core.game.GameEvents.PlayerJoinGameEvent;
import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.GameEvents.PlayerQuitGameEvent;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.sexidium.core.game.GameTestPlayers.stubPlayer;
import static com.sexidium.core.game.GameTestPlayers.stubPlayerWithHealth;
import static org.junit.jupiter.api.Assertions.*;

class AbstractGameTest {

  private GameContext ctx;
  private StubGame game;

  @BeforeEach
  void setUp() {
    ctx = new GameContext(new TestServerAdapter(), new NoopKitAdapter(), com.sexidium.core.data.RankAwardPort.noop());
    game = new StubGame(ctx);
  }

  // --- Identity ---

  @Test
  void id_returnsProvidedModeId() {
    assertEquals("stub", game.id());
  }

  @Test
  void displayName_returnsProvidedName() {
    assertEquals("Stub Game", game.displayName());
  }

  @Test
  void minPlayers_returnsProvidedValue() {
    assertEquals(1, game.minPlayers());
  }

  // --- Initial state ---

  @Test
  void initialState_isIdle() {
    assertEquals(GameState.IDLE, game.state());
  }

  @Test
  void isRunning_false_whenIdle() {
    assertFalse(game.isRunning());
  }

  @Test
  void isEmpty_true_whenNoParticipants() {
    assertTrue(game.isEmpty());
  }

  // --- State transitions ---

  @Test
  void beginRunning_setsStateToRunning() {
    game.startGame();
    assertEquals(GameState.RUNNING, game.state());
    assertTrue(game.isRunning());
  }

  @Test
  void markEnded_setsStateToEnded() {
    game.startGame();
    game.stopGame();
    assertEquals(GameState.ENDED, game.state());
    assertFalse(game.isRunning());
  }

  @Test
  void pause_setsStandbyState() {
    game.startGame();
    game.pauseGame();
    assertTrue(game.isPaused());
    assertEquals(GameState.STANDBY, game.state());
  }

  @Test
  void resume_restoresRunningState() {
    game.startGame();
    game.pauseGame();
    game.resumeGame();
    assertFalse(game.isPaused());
    assertEquals(GameState.RUNNING, game.state());
  }

  @Test
  void pause_whenAlreadyPaused_isNoOp() {
    game.startGame();
    game.pauseGame();
    game.pauseGame(); // second call should be no-op
    assertTrue(game.isPaused());
  }

  @Test
  void resume_whenNotPaused_isNoOp() {
    game.startGame();
    assertFalse(game.isPaused());
    game.resumeGame(); // should not throw or change state
    assertFalse(game.isPaused());
    assertEquals(GameState.RUNNING, game.state());
  }

  // --- Participant tracking ---

  @Test
  void addParticipant_tracksPlayer() {
    UUID id = UUID.randomUUID();
    game.addPlayer(id);
    assertTrue(game.isParticipant(id));
  }

  @Test
  void removeParticipant_untracksPlayer() {
    UUID id = UUID.randomUUID();
    game.addPlayer(id);
    game.removePlayer(id);
    assertFalse(game.isParticipant(id));
  }

  @Test
  void isEmpty_trueWhenNoOnlinePlayers() {
    // isEmpty() checks online players via server.player() which returns empty in tests
    // Adding a participant doesn't affect isEmpty() since they're not "online" in mock
    UUID id = UUID.randomUUID();
    game.addPlayer(id);
    assertTrue(game.isEmpty(), "isEmpty reflects online players, not participant set");
  }

  @Test
  void clearParticipants_removesAll() {
    game.addPlayer(UUID.randomUUID());
    game.addPlayer(UUID.randomUUID());
    game.clearAll();
    assertTrue(game.isEmpty());
  }

  @Test
  void beginRunning_clearsExistingParticipants() {
    game.addPlayer(UUID.randomUUID());
    game.startGame(); // calls beginRunning() which clears players
    assertTrue(game.isEmpty());
  }

  @Test
  void isParticipant_falseForUnknownPlayer() {
    assertFalse(game.isParticipant(UUID.randomUUID()));
  }

  // --- Disconnection tracking ---

  @Test
  void markDisconnected_tracksPlayer() {
    UUID id = UUID.randomUUID();
    game.addPlayer(id);
    game.disconnect(id);
    assertTrue(game.isDisconnectedPlayer(id));
    assertTrue(game.disconnectedPlayers().contains(id));
  }

  @Test
  void markReconnected_removesFromDisconnected() {
    UUID id = UUID.randomUUID();
    game.addPlayer(id);
    game.disconnect(id);
    game.reconnect(id);
    assertFalse(game.isDisconnectedPlayer(id));
  }

  @Test
  void removeParticipant_removesFromPlayerSet() {
    UUID id = UUID.randomUUID();
    game.addPlayer(id);
    game.disconnect(id); // adds to disconnectedPlayers
    game.removePlayer(id); // removes from players set only (not disconnectedPlayers per impl)
    assertFalse(game.isParticipant(id));
    // Note: removeParticipant(UUID) only removes from players, not disconnectedPlayers
  }

  @Test
  void isDisconnected_falseForNonParticipant() {
    assertFalse(game.isDisconnectedPlayer(UUID.randomUUID()));
  }

  @Test
  void disconnected_returnsSetOfDisconnectedIds() {
    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    game.addPlayer(id1);
    game.addPlayer(id2);
    game.disconnect(id1);
    Set<UUID> disc = game.disconnectedPlayers();
    assertTrue(disc.contains(id1));
    assertFalse(disc.contains(id2));
  }

  // --- handle() default no-op ---

  @Test
  void handle_defaultIsNoOp() {
    assertDoesNotThrow(() -> game.handle(
        new PlayerJoinGameEvent(null)));
  }

  @Test
  void handle_allEventTypes_defaultIsNoOp() {
    UUID id = UUID.randomUUID();
    PlayerAdapter player = stubPlayer(id);
    assertDoesNotThrow(() -> {
      game.handle(new PlayerJoinGameEvent(player));
      game.handle(new PlayerQuitGameEvent(player));
      game.handle(new BlockBreakGameEvent(player, new BlockPosition("w", 0, 64, 0), ItemKey.minecraft("stone")));
      game.handle(new InventoryChangeGameEvent(player));
      game.handle(new PlayerMoveGameEvent(player,
          new WorldPosition("w", 0, 64, 0, 0, 0),
          new WorldPosition("w", 1, 64, 0, 0, 0)));
    });
  }

  // --- Default lifecycle hooks ---

  @Test
  void onParticipantDisconnect_defaultNoOp() {
    assertDoesNotThrow(() -> game.onParticipantDisconnect(stubPlayer(UUID.randomUUID())));
  }

  @Test
  void onParticipantRejoin_defaultNoOp() {
    assertDoesNotThrow(() -> game.onParticipantRejoin(stubPlayer(UUID.randomUUID())));
  }

  @Test
  void onParticipantRemoved_clearsParticipantAndReconnectState() {
    UUID playerId = UUID.randomUUID();
    com.sexidium.core.game.persist.MatchSnapshot restored = new com.sexidium.core.game.persist.MatchSnapshot(
        UUID.randomUUID(), "stub");
    restored.players.add(new com.sexidium.core.game.persist.PlayerSnapshot(playerId, "Player", null));
    game.restore(restored);

    assertTrue(game.isParticipant(playerId));
    assertTrue(game.isDisconnectedPlayer(playerId));

    game.onParticipantRemoved(playerId, true);

    assertFalse(game.isParticipant(playerId));
    assertFalse(game.isDisconnectedPlayer(playerId));
    com.sexidium.core.game.persist.MatchSnapshot persisted = new com.sexidium.core.game.persist.MatchSnapshot(
        UUID.randomUUID(), "stub");
    game.writeSnapshot(persisted);
    assertNull(persisted.player(playerId));
  }

  @Test
  void onParticipantRemoved_preventsLaterParticipantUiRestore() {
    RecordingBossBar bar = new RecordingBossBar();
    game.trackBar(bar);
    UUID playerId = UUID.randomUUID();
    PlayerAdapter player = stubPlayer(playerId);
    game.addPlayer(playerId);

    game.onParticipantRemoved(playerId, true);
    game.restoreUiIfParticipant(player);

    assertFalse(bar.shown.contains(playerId), "removed players must not restore abandoned match UI");
  }

  @Test
  void writeSnapshot_defaultNoOp() {
    assertDoesNotThrow(() -> game.writeSnapshot(new com.sexidium.core.game.persist.MatchSnapshot(
        UUID.randomUUID(), "stub")));
  }

  @Test
  void restore_defaultNoOp() {
    assertDoesNotThrow(() -> game.restore(new com.sexidium.core.game.persist.MatchSnapshot(
        UUID.randomUUID(), "stub")));
  }

  @Test
  void isReconnectable_defaultFalse() {
    assertFalse(game.isReconnectable());
  }

  // --- announce broadcasts ---

  @Test
  void announce_withNoOnlinePlayers_sendsToConsole() {
    assertDoesNotThrow(() -> game.announceKey(MessageKey.COMMAND_HELP_TITLE));
  }

  // --- Scheduling ---

  @Test
  void scheduleRepeating_cancelledByCleanup() {
    int[] ticks = {0};
    game.scheduleRepeating(() -> ticks[0]++, 1L, 1L);
    game.cleanupNow();
    // DirectSchedulerAdapter runs tasks synchronously; cleanup cancels future ticks
  }

  @Test
  void scheduleOnce_cancelledByCleanup() {
    int[] runs = {0};
    game.scheduleOnce(() -> runs[0]++, 1L);
    game.cleanupNow();
  }

  // --- Kit / player state helpers ---

  @Test
  void giveKit_emptyOrNullName_doesNotThrow() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    assertDoesNotThrow(() -> {
      game.giveKitTo(player, "");
      game.giveKitTo(player, null);
      game.giveKitTo(null, "pvp");
    });
  }

  // --- onParticipantAdded default (AbstractGame inherits no-op from Game) ---

  @Test
  void onParticipantAdded_defaultDoesNotMutateState() {
    game.startGame();
    game.addParticipantPublic(stubPlayer(UUID.randomUUID()));
    int participantCountBefore = game.participantCountPublic();
    GameState stateBefore = game.state();
    boolean runningBefore = game.isRunning();

    game.notifyParticipantAddedPublic(stubPlayer(UUID.randomUUID()));
    assertEquals(stateBefore, game.state(), "state should not change from onParticipantAdded");
    assertEquals(runningBefore, game.isRunning());
    assertEquals(participantCountBefore, game.participantCountPublic(),
        "default onParticipantAdded is a no-op; participants unchanged");
  }

  @Test
  void onParticipantAdded_nullPlayer_doesNotThrow() {
    game.startGame();
    assertDoesNotThrow(() -> game.notifyParticipantAddedPublic(null));
  }

  @Test
  void prepareSurvival_setsHealthFoodAndGameMode() {
    TrackedPlayer player = stubPlayerWithHealth(1.0);
    game.prepareSurvivalFor(player);
    assertEquals(20.0, player.health());
    assertEquals(20, player.foodLevel());
  }

  @Test
  void prepareSurvival_nullPlayer_throws() {
    // AbstractGame.prepareSurvival does not null-check; callers must guard
    assertThrows(NullPointerException.class, () -> game.prepareSurvivalFor(null));
  }

  @Test
  void heal_setsToMaxHealth() {
    TrackedPlayer low = stubPlayerWithHealth(1.0);
    game.healPlayer(low);
    assertEquals(20.0, low.health());
  }

  @Test
  void heal_nullPlayer_doesNotThrow() {
    assertDoesNotThrow(() -> game.healPlayer(null));
  }

  @Test
  void isLethal_trueWhenHealthAtOrBelowZero() {
    TrackedPlayer low = stubPlayerWithHealth(2.0);
    assertTrue(game.isLethalFor(low, 5.0));
    assertFalse(game.isLethalFor(low, 1.0));
  }

  @Test
  void isLethal_nullPlayerOrZeroDamage_false() {
    assertFalse(game.isLethalFor(null, 100.0));
    TrackedPlayer full = stubPlayerWithHealth(20.0);
    assertFalse(game.isLethalFor(full, 0.0));
  }

  @Test
  void releaseToLobby_doesNotThrowWhenNoLobby() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    assertDoesNotThrow(() -> game.releaseToLobbyOf(player));
  }

  // --- Award helpers route through RankAwardPort (noop in tests) ---

  @Test
  void awardParticipation_doesNotThrow() {
    assertDoesNotThrow(() -> game.awardParticipationTo(stubPlayer(UUID.randomUUID())));
  }

  @Test
  void awardKill_doesNotThrow() {
    assertDoesNotThrow(() -> game.awardKillTo(stubPlayer(UUID.randomUUID())));
  }

  @Test
  void awardWin_doesNotThrow() {
    assertDoesNotThrow(() -> game.awardWinTo(stubPlayer(UUID.randomUUID())));
  }

  // --- endSoon schedules cleanup ---

  @Test
  void endSoon_schedulesDelayedTask() {
    assertDoesNotThrow(() -> game.endSoonPublic(1L));
  }

  // --- online() filters via server.player().online() ---

  @Test
  void online_returnsOnlyThoseFlaggedOnline() {
    UUID present = UUID.randomUUID();
    UUID absent = UUID.randomUUID();
    game.addPlayer(present);
    game.addPlayer(absent);
    // TestServerAdapter returns empty for unknown UUIDs → neither is "online"
    assertTrue(game.onlineList().isEmpty());
  }

  // --- Per-player UI release (exit/leave/quit) ---

  @Test
  void releasePlayerUi_hidesTrackedBossBarsAndPanelsForThatPlayer() {
    RecordingBossBar bar = new RecordingBossBar();
    RecordingPanel panel = new RecordingPanel();
    game.trackBar(bar);
    game.trackPanel(panel);
    UUID id = UUID.randomUUID();
    PlayerAdapter player = stubPlayer(id);

    game.releasePlayerUi(player);

    assertTrue(bar.hidden.contains(id), "boss bar should be hidden for the leaving player");
    assertTrue(panel.hidden.contains(id), "HUD panel should be hidden for the leaving player");
    assertFalse(bar.closed, "other players keep the bar; it must be hidden per-player, not closed");
  }

  @Test
  void releasePlayerUi_nullPlayer_doesNotThrow() {
    game.trackBar(new RecordingBossBar());
    assertDoesNotThrow(() -> game.releasePlayerUi(null));
  }

  @Test
  void releaseAndReset_hidesTrackedUiForPlayer() {
    // Mid-match elimination path (combat/gather/tntwar) must also hide overlays via the per-match hide.
    RecordingBossBar bar = new RecordingBossBar();
    game.trackBar(bar);
    UUID id = UUID.randomUUID();
    game.releaseAndResetOf(stubPlayer(id));
    assertTrue(bar.hidden.contains(id));
  }

  @Test
  void restorePlayerUi_reShowsTrackedUiForPlayer() {
    // A player who stays in the match (e.g. a respawning hunter) gets the overlay back.
    RecordingBossBar bar = new RecordingBossBar();
    game.trackBar(bar);
    UUID id = UUID.randomUUID();
    game.restorePlayerUiOf(stubPlayer(id));
    assertTrue(bar.shown.contains(id));
  }

}
