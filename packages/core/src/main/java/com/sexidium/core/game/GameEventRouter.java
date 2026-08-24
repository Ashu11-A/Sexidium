package com.sexidium.core.game;

import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerChangedWorldGameEvent;
import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.GameEvents.PlayerJoinGameEvent;
import com.sexidium.core.game.GameEvents.PlayerQuitGameEvent;
import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.map.editor.MapEditorService;
import com.sexidium.core.platform.ServerAdapter;

import java.util.ArrayList;

public final class GameEventRouter {
  private final ServerAdapter serverAdapter;
  private final GameManager gameManager;
  private final LobbyManager lobbyManager;
  // Notified on every player join (set by SexidiumCore). Lets the NPC manager re-spawn once the server is
  // fully up; default no-op keeps this router usable in tests without wiring it.
  private Runnable playerJoinHook = () -> { };
  // Bridge notifications (set by SexidiumCore) so the Discord bot gets real-time join/leave events.
  // Default no-ops keep this router usable in tests without wiring them.
  private java.util.function.Consumer<com.sexidium.core.platform.PlayerAdapter> playerJoinListener = player -> { };
  private java.util.function.Consumer<com.sexidium.core.platform.PlayerAdapter> playerLeaveListener = player -> { };
  // In-world battle-map editor, set by SexidiumCore. Interact events reach it before active matches so the
  // selection axe works outside of any running game; null in tests where it is not wired.
  private MapEditorService mapEditor;

  public GameEventRouter(ServerAdapter serverAdapter, GameManager gameManager, LobbyManager lobbyManager) {
    this.serverAdapter = serverAdapter;
    this.gameManager = gameManager;
    this.lobbyManager = lobbyManager;
  }

  public void setPlayerJoinHook(Runnable playerJoinHook) {
    this.playerJoinHook = playerJoinHook == null ? () -> { } : playerJoinHook;
  }

  public void setPlayerJoinListener(java.util.function.Consumer<com.sexidium.core.platform.PlayerAdapter> listener) {
    this.playerJoinListener = listener == null ? player -> { } : listener;
  }

  public void setPlayerLeaveListener(java.util.function.Consumer<com.sexidium.core.platform.PlayerAdapter> listener) {
    this.playerLeaveListener = listener == null ? player -> { } : listener;
  }

  public void setMapEditor(MapEditorService mapEditor) {
    this.mapEditor = mapEditor;
  }

  public void handle(GameEvent gameEvent) {
    switch (gameEvent) {
      case PlayerJoinGameEvent playerJoinGameEvent -> handleJoin(playerJoinGameEvent);
      case PlayerQuitGameEvent playerQuitGameEvent -> handleQuit(playerQuitGameEvent);
      case PlayerRespawnGameEvent playerRespawnGameEvent -> handleRespawn(playerRespawnGameEvent);
      case PlayerChangedWorldGameEvent playerChangedWorldGameEvent -> handleChangedWorld(playerChangedWorldGameEvent);
      case PlayerInteractGameEvent playerInteractGameEvent -> handleInteract(playerInteractGameEvent);
      case BlockBreakGameEvent blockBreakGameEvent -> handleBlockBreak(blockBreakGameEvent);
      default -> routeToActiveGames(gameEvent);
    }
  }

  private void handleInteract(PlayerInteractGameEvent playerInteractGameEvent) {
    // The map editor consumes an axe click outside of any match; if it does, don't also route to games.
    if (mapEditor != null && mapEditor.onInteract(playerInteractGameEvent)) {
      return;
    }
    routeToActiveGames(playerInteractGameEvent);
  }

  private void handleBlockBreak(BlockBreakGameEvent blockBreakGameEvent) {
    // Keep the editor's selection tools non-destructive in Creative: veto a break while a session player
    // holds a tool (ordinary creative building with a normal item still falls through to the games below).
    if (mapEditor != null && mapEditor.shouldCancelBlockBreak(blockBreakGameEvent.playerAdapter())) {
      blockBreakGameEvent.setCancelled(true);
      return;
    }
    routeToActiveGames(blockBreakGameEvent);
  }

  private void handleJoin(PlayerJoinGameEvent playerJoinGameEvent) {
    playerJoinHook.run();
    playerJoinListener.accept(playerJoinGameEvent.playerAdapter());
    // A join reaches gameManager.handleJoin for TWO different reasons, and for a long time only the
    // first one got there:
    //
    //   1. a reconnect -- this node has a persisted session for the player;
    //   2. an ARRIVAL FROM ANOTHER NODE -- this node has nothing at all for the player, only a row
    //      in match_handoffs saying they were sent here.
    //
    // The old `hasPersistedSession` guard returned early for everyone in case 2, which is every
    // player a transfer ever delivers: a first arrival on a worker has no local session by
    // definition. The arrival gate (PlayerSessionCoordinator#admitTransferredPlayer) was therefore
    // unreachable from the case it was written for, and the symptom was a plain Minecraft server --
    // the transfer completed, the player landed in the node's default world, and the experience they
    // were sent for never opened. Both cases now take the same path; the guard survives only as the
    // reason to forward the event to matches already running here.
    if (!gameManager.hasPersistedSession(playerJoinGameEvent.playerAdapter().uniqueId())) {
      routeToActiveGames(playerJoinGameEvent);
    }
    serverAdapter.scheduler().runLater(() -> {
      if (!playerJoinGameEvent.playerAdapter().online()) {
        return;
      }
      // False is the ordinary answer here -- no session, no handoff -- and costs one indexed lookup
      // that only runs at all on a networked node (the coordinator returns immediately when no
      // handoff service is wired, which is every standalone server).
      if (gameManager.handleJoin(playerJoinGameEvent.playerAdapter())) {
        serverAdapter.messages().send(playerJoinGameEvent.playerAdapter(), LocalizedText.of(MessageKey.RECONNECT_RESTORED));
      }
    }, 1L);
  }

  private void handleQuit(PlayerQuitGameEvent playerQuitGameEvent) {
    playerLeaveListener.accept(playerQuitGameEvent.playerAdapter());
    gameManager.handleQuit(playerQuitGameEvent.playerAdapter());
    if (mapEditor != null) {
      // End any open editor session so its render task is cancelled and the loaded world is released.
      mapEditor.exit(playerQuitGameEvent.playerAdapter().uniqueId());
    }
    if (lobbyManager != null) {
      // Drop the disconnecting player from their lobby/queue so they don't linger as a ghost member.
      lobbyManager.onPlayerQuit(playerQuitGameEvent.playerAdapter().uniqueId());
    }
    routeToActiveGames(playerQuitGameEvent);
  }

  private void handleRespawn(PlayerRespawnGameEvent playerRespawnGameEvent) {
    gameManager.handleRespawn(playerRespawnGameEvent.playerAdapter());
    routeToActiveGames(playerRespawnGameEvent);
  }

  private void handleChangedWorld(PlayerChangedWorldGameEvent playerChangedWorldGameEvent) {
    gameManager.handleChangedWorld(
        playerChangedWorldGameEvent.playerAdapter(),
        playerChangedWorldGameEvent.fromWorld(),
        playerChangedWorldGameEvent.toWorld()
    );
    routeToActiveGames(playerChangedWorldGameEvent);
  }

  private void routeToActiveGames(GameEvent gameEvent) {
    for (ActiveMatch activeMatch : new ArrayList<>(gameManager.matches())) {
      activeMatch.game().handle(gameEvent);
    }
  }
}
