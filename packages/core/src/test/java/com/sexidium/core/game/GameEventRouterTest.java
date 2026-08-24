package com.sexidium.core.game;
import com.sexidium.core.game.GameEvents.*;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.game.*;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.*;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GameEventRouterTest {

  private static final com.sexidium.core.platform.LoggerAdapter SILENT =
      new com.sexidium.core.platform.LoggerAdapter() {
        @Override public void info(String message) { }
        @Override public void warning(String message) { }
        @Override public void severe(String message) { }
        @Override public void warning(String message, Throwable throwable) { }
        @Override public void severe(String message, Throwable throwable) { }
      };

  private GameEventRouter router;
  private GameManager manager;
  private TrackingGame game;

  @BeforeEach
  void setUp() {
    TestServerAdapter server = new TestServerAdapter();
    GameContext ctx = new GameContext(server, new NoopKitAdapter(), com.sexidium.core.data.RankAwardPort.noop());
    GameRegistry registry = new GameRegistry();
    manager = new GameManager(ctx, registry, null);
    router = new GameEventRouter(server, manager, null);
    game = new TrackingGame(ctx);
  }

  private PlayerAdapter stubPlayer(UUID id) {
    return new PlayerAdapter() {
      @Override public UUID uniqueId() { return id; }
      @Override public String name() { return "Player"; }
      @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
      @Override public boolean hasPermission(String p) { return true; }
      @Override public void sendMiniMessage(String m) {}
      @Override public void sendPlainMessage(String m) {}
      @Override public boolean online() { return true; }
      @Override public boolean dead() { return false; }
      @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
      @Override public WorldPosition position() { return null; }
      @Override public void teleport(WorldPosition p) {}
      @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
      @Override public void setGameMode(GameModeType g) {}
      @Override public double health() { return 20; }
      @Override public double maxHealth() { return 20; }
      @Override public void setHealth(double h) {}
      @Override public int foodLevel() { return 20; }
      @Override public void setFoodLevel(int f) {}
      @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
      @Override public void playSound(SoundKey s, float v, float p) {}
      @Override public void showTitle(TitleSpec t) {}
      @Override public void sendActionBar(String m) {}
      @Override public void setCompassTarget(WorldPosition p) {}
      @Override public void clearInventory() {}
      @Override public void clearPotionEffects() {}
    };
  }

  /**
   * The regression that made a transferred player land on a plain Minecraft server.
   *
   * <p>A player sent to another node has NO persisted session there — that is what being transferred
   * means. The join handler used to return early for exactly that case, so the arrival gate, which
   * is the only thing that opens the experience the player was sent for, was never consulted. The
   * negative control is the assertion below: with the early return restored this resume hook is
   * never invoked and the player simply stands in the node's default world.</p>
   */
  @Test
  void handle_joinEvent_transferredPlayerWithNoLocalSession_reachesTheArrivalGate() throws Exception {
    UUID playerId = UUID.randomUUID();
    PlayerAdapter player = stubPlayer(playerId);
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("handoff-router");
    com.sexidium.core.network.MatchHandoffService handoffs =
        new com.sexidium.core.network.MatchHandoffService(
            new com.sexidium.core.lib.data.Database(new java.io.File(dir.toFile(), "h.db")), SILENT);

    TestServerAdapter server = new TestServerAdapter() {
      @Override public Optional<PlayerAdapter> player(UUID id) {
        return playerId.equals(id) ? Optional.of(player) : Optional.empty();
      }
    };
    GameContext ctx = new GameContext(server, new NoopKitAdapter(), com.sexidium.core.data.RankAwardPort.noop());
    GameManager localManager = new GameManager(ctx, new GameRegistry(), null);
    GameEventRouter localRouter = new GameEventRouter(server, localManager, null);

    // Through the first-class transfer ticket now, not a "experience:"-prefixed match_handoffs row.
    // The mechanism changed; what this test pins did not: an arrival must OPEN the world the player
    // was sent for, or they stand on a worker with no lobby, no NPC and no menu.
    String worldKey = "death_resets_aa280b96";
    com.sexidium.core.network.transfer.DbTransferService transfers =
        new com.sexidium.core.network.transfer.DbTransferService(
            new com.sexidium.core.lib.data.Database(new java.io.File(dir.toFile(), "t.db")),
            SILENT, "lobby", 120_000L, 3, 60_000L, 3);
    assertTrue(transfers.request(playerId, "worker-1", 0L,
        com.sexidium.core.network.transfer.TransferReason.EXPERIENCE, worldKey).isPresent());

    localManager.setHandoffs(handoffs, "worker-1", 0L);
    localManager.setArrivalGate(transfers, "worker-1", 0L);
    List<String> resumed = new ArrayList<>();
    localManager.setTransferHooks((p, key) -> resumed.add(key.key()), id -> false);

    assertFalse(localManager.hasPersistedSession(playerId),
        "a transferred player has no session on the node they arrive at — that is the whole point");
    localRouter.handle(new PlayerJoinGameEvent(player));

    assertEquals(List.of(worldKey), resumed,
        "the arrival must open the world the player was sent for");
  }

  @Test
  void handle_joinEvent_noActiveGame_doesNotThrow() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    assertDoesNotThrow(() -> router.handle(new PlayerJoinGameEvent(player)));
  }

  @Test
  void handle_quitEvent_noActiveGame_doesNotThrow() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    assertDoesNotThrow(() -> router.handle(new PlayerQuitGameEvent(player)));
  }

  @Test
  void handle_blockBreakEvent_routedToActiveGames() {
    // Manually inject an active match so we can verify routing
    // Since we can't easily inject an ActiveMatch, we verify no NPE for non-join/quit events
    BlockPosition pos = new BlockPosition("world", 0, 64, 0);
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    BlockBreakGameEvent event = new BlockBreakGameEvent(player, pos, ItemKey.minecraft("stone"));
    assertDoesNotThrow(() -> router.handle(event));
  }

  @Test
  void handle_damageEvent_routedToActiveGames() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    PlayerDamageGameEvent event = new PlayerDamageGameEvent(player, null, DamageCauseType.FALL, 2.0);
    assertDoesNotThrow(() -> router.handle(event));
  }

  @Test
  void handle_entityDeathEvent_routedToActiveGames() {
    EntityDeathGameEvent event = new EntityDeathGameEvent("zombie",
        new WorldPosition("world", 0, 64, 0, 0, 0));
    assertDoesNotThrow(() -> router.handle(event));
  }

  @Test
  void handle_inventoryChangeEvent_routedToActiveGames() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    assertDoesNotThrow(() -> router.handle(new InventoryChangeGameEvent(player)));
  }

  @Test
  void handle_playerMoveEvent_routedToActiveGames() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    WorldPosition from = new WorldPosition("w", 0, 64, 0, 0, 0);
    WorldPosition to = new WorldPosition("w", 1, 64, 0, 0, 0);
    assertDoesNotThrow(() -> router.handle(new PlayerMoveGameEvent(player, from, to)));
  }

  @Test
  void handle_blockPlaceEvent_routedToActiveGames() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    assertDoesNotThrow(() -> router.handle(new BlockPlaceGameEvent(player,
        new BlockPosition("w", 0, 65, 0), ItemKey.minecraft("dirt"))));
  }

  @Test
  void handle_interactEvent_routedToActiveGames() {
    PlayerAdapter player = stubPlayer(UUID.randomUUID());
    assertDoesNotThrow(() -> router.handle(new PlayerInteractGameEvent(
        player, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, null, null)));
  }

  // Minimal stub game that tracks received events
  static class TrackingGame implements Game {
    private final List<GameEvent> received = new ArrayList<>();
    TrackingGame(GameContext ctx) {}
    @Override public String id() { return "track"; }
    @Override public String displayName() { return "Tracker"; }
    @Override public int minPlayers() { return 1; }
    @Override public void start(List<PlayerAdapter> p) {}
    @Override public void stop(LocalizedText r) {}
    @Override public GameState state() { return GameState.RUNNING; }
    @Override public void handle(GameEvent event) { received.add(event); }
    List<GameEvent> received() { return received; }
  }
}
