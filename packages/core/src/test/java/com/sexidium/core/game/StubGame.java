package com.sexidium.core.game;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal concrete {@link AbstractGame} used by the {@code AbstractGame} test suite.
 * Exposes the protected lifecycle/helper surface of {@link AbstractGame} as
 * package-private test hooks. Extracted verbatim from the former nested
 * {@code StubGame} of {@code AbstractGameTest}.
 */
class StubGame extends AbstractGame {
  StubGame(GameContext ctx) {
    super(ctx, "stub", "Stub Game", 1);
  }

  @Override public void start(List<PlayerAdapter> players) { beginRunning(); }
  @Override public void stop(LocalizedText reason) { markEnded(); }

  // Expose protected methods for testing
  void startGame() { beginRunning(); }
  void stopGame() { markEnded(); }
  void pauseGame() { pause(); }
  void resumeGame() { resume(); }
  void addPlayer(UUID id) { addParticipant(id); }
  void removePlayer(UUID id) { removeParticipant(id); }
  void clearAll() { clearParticipants(); }
  boolean isParticipant(UUID id) { return players.contains(id); }
  void disconnect(UUID id) { markDisconnected(id); }
  void reconnect(UUID id) { markReconnected(id); }
  boolean isDisconnectedPlayer(UUID id) { return isDisconnected(id); }
  Set<UUID> disconnectedPlayers() { return disconnected(); }
  void scheduleRepeating(Runnable r, long delay, long period) { runTimer(r, delay, period); }
  void scheduleOnce(Runnable r, long delay) { runLater(r, delay); }
  void trackBar(com.sexidium.core.platform.BossBarHandle b) { track(b); }
  void trackPanel(com.sexidium.core.platform.HudPanelHandle p) { track(p); }
  void releaseAndResetOf(PlayerAdapter p) { releaseAndReset(p); }
  void restorePlayerUiOf(PlayerAdapter p) { restorePlayerUi(p); }
  void prepareSurvivalFor(PlayerAdapter p) { prepareSurvival(p); }
  void giveKitTo(PlayerAdapter p, String name) { giveKit(p, name); }
  void releaseToLobbyOf(PlayerAdapter p) { releaseToLobby(p); }
  void restoreUiIfParticipant(PlayerAdapter p) { if (isParticipant(p)) restorePlayerUi(p); }
  boolean isLethalFor(PlayerAdapter p, double dmg) { return isLethal(p, dmg); }
  void healPlayer(PlayerAdapter p) { heal(p); }
  void awardParticipationTo(PlayerAdapter p) { awardParticipation(p); }
  void awardKillTo(PlayerAdapter p) { awardKill(p); }
  void awardWinTo(PlayerAdapter p) { awardWin(p); }
  void announceKey(MessageKey key) { announce(key); }
  List<PlayerAdapter> onlineList() { return online(); }
  void addParticipantPublic(PlayerAdapter p) { addParticipant(p); }
  void notifyParticipantAddedPublic(PlayerAdapter p) { onParticipantAdded(p); }
  int participantCountPublic() { return players.size(); }
  protected void endSoonPublic(long ticks) { endSoon(ticks); }
  protected void cleanupNow() { cleanup(); }
  protected boolean isPaused() { return super.isPaused(); }
  protected boolean isRunning() { return super.isRunning(); }
}
