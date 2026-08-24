package com.sexidium.core.game.modes;

import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.AbstractGame;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class BaseTimedGame extends AbstractGame {
  protected BaseTimedGame(GameContext gameContext, String modeId, String displayName, int minPlayers) {
    super(gameContext, modeId, displayName, minPlayers);
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    beginRunning();
    if (participants != null) {
      for (PlayerAdapter player : participants) {
        startParticipant(player);
      }
    }
    announceStarted();
    long durationSeconds = durationSeconds();
    if (durationSeconds > 0L) {
      endSoon(durationSeconds * 20L);
    }
  }

  @Override
  public void stop(LocalizedText reason) {
    markEnded();
    for (PlayerAdapter player : online()) {
      releaseAndReset(player);
    }
    cleanup();
    clearParticipants();
  }

  @Override
  public void handle(GameEvent gameEvent) {
    if (!isRunning()) {
      return;
    }
    // Unified HUD toggle (PC double-sneak); no-op for modes without a HUD. Every minigame reaches this
    // via super.handle() or by not overriding handle(), so the gesture works everywhere uniformly.
    routeHudGesture(gameEvent);
    if (gameEvent instanceof PlayerDamageGameEvent damageEvent) {
      handleDamage(damageEvent);
    }
  }

  // All minigames and Experience modes support reconnect: a disconnecting player stays in the
  // match (within reconnect.timeout-seconds) instead of being dropped, and is teleported back into
  // their match world on rejoin. Disable globally with reconnect.enabled: false.
  @Override
  public boolean isReconnectable() {
    return cfg().getBoolean("reconnect.enabled", true);
  }

  protected abstract void announceStarted();

  protected void startParticipant(PlayerAdapter player) {
    addParticipant(player);
    prepareSurvival(player);
    giveKit(player, configuredKit());
    awardParticipation(player);
  }

  @Override
  public void onParticipantAdded(PlayerAdapter player) {
    if (player == null || isParticipant(player)) {
      return;
    }
    addParticipant(player);
    prepareSurvival(player);
    giveKit(player, configuredKit());
  }

  protected String configuredKit() {
    return cfg().getString(configPath("kit"), "");
  }

  protected long durationSeconds() {
    return Math.max(0L, cfg().getLong(configPath("duration-seconds"), 0L));
  }

  protected String configPath(String childPath) {
    return configPrefix() + "." + childPath;
  }

  protected String configPrefix() {
    return "games." + id();
  }

  // ----- shared "game.*" tunables -----------------------------------------------------------------
  // Settings common to every mode (warm-up, friendly fire, starter kit, …) live once under the global
  // `game:` config section. Each read prefers a per-mode override and otherwise inherits the global,
  // so a value is configured in one place but a single mode can still diverge.

  protected int tunableInt(String modeKey, String globalKey, int fallback) {
    return cfg().getInt(configPath(modeKey), cfg().getInt("game." + globalKey, fallback));
  }

  protected boolean tunableBoolean(String modeKey, String globalKey, boolean fallback) {
    return cfg().getBoolean(configPath(modeKey), cfg().getBoolean("game." + globalKey, fallback));
  }

  protected String tunableString(String modeKey, String globalKey, String fallback) {
    return cfg().getString(configPath(modeKey), cfg().getString("game." + globalKey, fallback));
  }

  protected void handleDamage(PlayerDamageGameEvent event) {
    PlayerAdapter victim = event.victim();
    if (!isParticipant(victim) || !isLethal(victim, event.finalDamage())) {
      return;
    }
    eliminate(victim, event.attacker());
  }

  protected void eliminate(PlayerAdapter victim, PlayerAdapter attacker) {
    if (victim == null || !isParticipant(victim)) {
      return;
    }
    removeParticipant(victim);
    if (attacker != null && isParticipant(attacker)) {
      awardKill(attacker);
    }
    checkForWinner();
  }

  protected void checkForWinner() {
    List<PlayerAdapter> remaining = remainingOnlineParticipants();
    if (remaining.size() == 1 && players.size() <= 1) {
      awardWin(remaining.get(0));
      requestEnd();
    } else if (remaining.isEmpty() && players.isEmpty()) {
      requestEnd();
    }
  }

  protected void finishWithWinner(PlayerAdapter winner) {
    if (winner != null) {
      awardWin(winner);
    }
    requestEnd();
  }

  protected void requestEnd() {
    if (gameContext.games() != null && gameContext.games().contains(this)) {
      gameContext.games().endMatch(this, null);
    } else {
      markEnded();
    }
  }

  protected List<PlayerAdapter> remainingOnlineParticipants() {
    List<PlayerAdapter> remaining = new ArrayList<>();
    for (UUID playerId : players) {
      gameContext.server().player(playerId)
          .filter(PlayerAdapter::online)
          .ifPresent(remaining::add);
    }
    return remaining;
  }
}
