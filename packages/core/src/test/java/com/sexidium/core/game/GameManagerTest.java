package com.sexidium.core.game;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GameManagerTest {

  private GameRegistry registry;
  private GameManager manager;
  private GameContext ctx;

  @BeforeEach
  void setUp() {
    ctx = new GameContext(new TestServerAdapter(), new NoopKitAdapter(), com.sexidium.core.data.RankAwardPort.noop());
    registry = new GameRegistry();
    manager = new GameManager(ctx, registry, null);
  }

  @Test
  void initialState_hasNoMatches() {
    assertTrue(manager.matches().isEmpty());
  }

  @Test
  void active_whenEmpty_returnsNull() {
    // active() returns the first Game or null (not a collection)
    assertNull(manager.active());
  }

  @Test
  void isRunning_whenEmpty_returnsFalse() {
    assertFalse(manager.isRunning());
  }

  @Test
  void isStarting_whenEmpty_returnsFalse() {
    assertFalse(manager.isStarting());
  }

  @Test
  void contains_withNullGame_returnsFalse() {
    assertFalse(manager.contains(null));
  }

  @Test
  void matchOf_withNullPlayer_returnsNull() {
    assertNull(manager.matchOf((PlayerAdapter) null));
  }

  @Test
  void matchOf_withUnknownUuid_returnsNull() {
    assertNull(manager.matchOf(UUID.randomUUID()));
  }

  @Test
  void hasPersistedSession_forUnknownPlayer_returnsFalse() {
    assertFalse(manager.hasPersistedSession(UUID.randomUUID()));
  }

  @Test
  void pendingWorldNames_whenEmpty_returnsEmpty() {
    assertTrue(manager.pendingWorldNames().isEmpty());
  }

  @Test
  void protectedWorldNames_includesReconnectPendingWorldsWithoutDuplicates() {
    com.sexidium.core.game.persist.MatchSnapshot first =
        new com.sexidium.core.game.persist.MatchSnapshot(UUID.randomUUID(), "combat");
    first.worldName = "sexidium_temp/sexidium_temp_pending";
    com.sexidium.core.game.persist.MatchSnapshot duplicate =
        new com.sexidium.core.game.persist.MatchSnapshot(UUID.randomUUID(), "combat");
    duplicate.worldName = "sexidium_temp/sexidium_temp_pending";

    manager.importPersisted(List.of(first, duplicate));

    assertEquals(List.of("sexidium_temp/sexidium_temp_pending"), manager.protectedWorldNames());
  }

  @Test
  void modeIds_emptyRegistry_returnsEmpty() {
    assertTrue(manager.modeIds().isEmpty());
  }

  @Test
  void modeIds_afterRegistration_returnsIds() {
    registry.register(new GameModeDescriptor("combat", "pvp", "Combat", 2, List.of()), (c, id, args) -> null);
    assertTrue(manager.modeIds().contains("combat"));
  }

  @Test
  void startingDisplayName_whenNotStarting_returnsNull() {
    assertNull(manager.startingDisplayName());
  }

  @Test
  void start_withEmptyPlayers_returnsFalse() {
    registry.register(new GameModeDescriptor("combat", "pvp", "Combat", 2, List.of()), (c, id, args) -> null);
    boolean started = manager.start("combat", List.of(), null);
    assertFalse(started, "Cannot start with 0 players when minPlayers=2");
  }

  @Test
  void start_unknownModeId_returnsFalse() {
    boolean started = manager.start("nonexistent", List.of(), null);
    assertFalse(started);
  }

  @Test
  void stopActiveGame_whenNoGame_doesNotThrow() {
    assertDoesNotThrow(() -> manager.stopActiveGame(LocalizedText.of(com.sexidium.core.i18n.MessageKey.STOP_BY_CONSOLE)));
  }

  @Test
  void discardStalePending_whenEmpty_doesNotThrow() {
    assertDoesNotThrow(manager::discardStalePending);
  }

  @Test
  void prepareShutdown_whenEmpty_doesNotThrow() {
    assertDoesNotThrow(manager::prepareShutdown);
  }

  @Test
  void matches_returnsCopyNotOriginal() {
    // modifications to the returned collection don't affect manager state
    var copy = manager.matches();
    copy.add(null); // mutable copy - doesn't throw
    assertTrue(manager.matches().isEmpty(), "Original matches should still be empty");
  }

  // --- joinInProgress: 4 outcomes ---

  @Test
  void joinInProgress_noMatchRunning_returnsNotRunning() {
    registry.register(new GameModeDescriptor("combat", "minigames", "Combat", 1, List.of()),
        (c, id, args) -> new RecordingGame(id, false));
    PlayerAdapter player = new TestPlayer("Alice");
    GameManager.JoinResult result = manager.joinInProgress(player, "combat");
    assertEquals(GameManager.JoinResult.NOT_RUNNING, result);
    assertNull(manager.matchOf(player), "no match should be tracked");
  }

  @Test
  void joinInProgress_withRunningMatch_returnsJoined() {
    registry.register(new GameModeDescriptor("combat", "minigames", "Combat", 1, List.of()),
        (c, id, args) -> new RecordingGame(id, false));
    PlayerAdapter host = new TestPlayer("Host");
    boolean started = manager.start("combat", List.of(host), null);
    assertTrue(started, "match should have started");

    PlayerAdapter joiner = new TestPlayer("Joiner");
    GameManager.JoinResult result = manager.joinInProgress(joiner, "combat");
    assertEquals(GameManager.JoinResult.JOINED, result);
    assertNotNull(manager.matchOf(joiner), "joiner should be indexed in the match");
    ActiveMatch joined = manager.matchOf(joiner);
    assertEquals("combat", joined.modeId());
    assertTrue(joined.game() instanceof RecordingGame);
    assertTrue(((RecordingGame) joined.game()).addedParticipants.contains("Joiner"),
        "onParticipantAdded should have been invoked for joiner");
  }

  @Test
  void joinInProgress_playerAlreadyInMatch_returnsAlreadyInMatch() {
    registry.register(new GameModeDescriptor("combat", "minigames", "Combat", 1, List.of()),
        (c, id, args) -> new RecordingGame(id, false));
    PlayerAdapter host = new TestPlayer("Host");
    manager.start("combat", List.of(host), null);

    GameManager.JoinResult result = manager.joinInProgress(host, "combat");
    assertEquals(GameManager.JoinResult.ALREADY_IN_MATCH, result);
  }

  @Test
  void joinInProgress_offlinePlayer_returnsPlayerOffline() {
    PlayerAdapter offline = new TestPlayer("Ghost", false);
    GameManager.JoinResult result = manager.joinInProgress(offline, "combat");
    assertEquals(GameManager.JoinResult.PLAYER_OFFLINE, result);
  }

  @Test
  void joinInProgress_nullPlayer_returnsPlayerOffline() {
    GameManager.JoinResult result = manager.joinInProgress(null, "combat");
    assertEquals(GameManager.JoinResult.PLAYER_OFFLINE, result);
  }

  @Test
  void joinInProgress_modeMatchIsExact() {
    registry.register(new GameModeDescriptor("Combat", "minigames", "Combat", 1, List.of()),
        (c, id, args) -> new RecordingGame(id, false));
    PlayerAdapter host = new TestPlayer("Host");
    manager.start("Combat", List.of(host), null);

    PlayerAdapter upper = new TestPlayer("Upper");
    GameManager.JoinResult upperResult = manager.joinInProgress(upper, "COMBAT");
    assertEquals(GameManager.JoinResult.NOT_RUNNING, upperResult,
        "GameManager.findRunningMatchByMode is case-sensitive; callers (e.g. CoreCommandService) normalise");

    PlayerAdapter exact = new TestPlayer("Exact");
    GameManager.JoinResult exactResult = manager.joinInProgress(exact, "Combat");
    assertEquals(GameManager.JoinResult.JOINED, exactResult);
  }

  @Test
  void runningModeIds_emptyByDefault() {
    assertTrue(manager.runningModeIds().isEmpty());
  }

  @Test
  void runningModeIds_listsRunningMode() {
    registry.register(new GameModeDescriptor("combat", "minigames", "Combat", 1, List.of()),
        (c, id, args) -> new RecordingGame(id, false));
    manager.start("combat", List.of(new TestPlayer("Host")), null);
    assertTrue(manager.runningModeIds().contains("combat"));
  }

  @Test
  void runningModeIds_deduplicatesPerMode() {
    registry.register(new GameModeDescriptor("combat", "minigames", "Combat", 1, List.of()),
        (c, id, args) -> new RecordingGame(id, false));
    manager.start("combat", List.of(new TestPlayer("Host1")), null);
    manager.start("combat", List.of(new TestPlayer("Host2")), null);
    assertEquals(1, manager.runningModeIds().stream().filter(id -> id.equals("combat")).count());
  }

  // --- recording stub game: tracks onParticipantAdded invocations ---

  private static final class RecordingGame implements Game {
    private final String gameId;
    private final boolean throwOnAdd;
    final List<String> addedParticipants = new ArrayList<>();
    private GameState state = GameState.IDLE;
    RecordingGame(String gameId, boolean throwOnAdd) {
      this.gameId = gameId;
      this.throwOnAdd = throwOnAdd;
    }
    @Override public String id() { return gameId; }
    @Override public String displayName() { return gameId; }
    @Override public int minPlayers() { return 1; }
    @Override public void start(List<PlayerAdapter> players) { state = GameState.RUNNING; }
    @Override public void stop(LocalizedText reason) { state = GameState.ENDED; }
    @Override public GameState state() { return state; }
    @Override public void onParticipantAdded(PlayerAdapter playerAdapter) {
      addedParticipants.add(playerAdapter.name());
      if (throwOnAdd) {
        throw new RuntimeException("simulated onParticipantAdded failure");
      }
    }
  }

  // --- minimal player stub ---

  private static final class TestPlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final String name;
    private final boolean online;
    TestPlayer(String name) { this(name, true); }
    TestPlayer(String name, boolean online) { this.name = name; this.online = online; }
    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return name; }
    @Override public Locale locale() { return Locale.ROOT; }
    @Override public boolean hasPermission(String p) { return false; }
    @Override public void sendMiniMessage(String m) {}
    @Override public void sendPlainMessage(String m) {}
    @Override public boolean online() { return online; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition p) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType g) {}
    @Override public double health() { return 20.0; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double h) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int f) {}
    @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
    @Override public void playSound(com.sexidium.core.platform.model.SoundKey s, float v, float p) {}
    @Override public void showTitle(com.sexidium.core.platform.model.TitleSpec t) {}
    @Override public void sendActionBar(String m) {}
    @Override public void setCompassTarget(WorldPosition p) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
  }
}
