package com.sexidium.core.game.modes;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.AbstractGame;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameState;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.KitAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseTimedGameTest {

  private RecordingKitAdapter kits;
  private TestServerAdapter server;
  private GameContext ctx;
  private RecordingTimedGame game;

  @BeforeEach
  void setUp() {
    kits = new RecordingKitAdapter(Set.of("pvp", "builder"));
    server = new TestServerAdapter();
    server.configuration().set("games.demo.kit", "pvp");
    ctx = new GameContext(server, kits, RankAwardPort.noop());
    game = new RecordingTimedGame(ctx);
    game.start(List.of(stubPlayer(UUID.randomUUID(), "First")));
  }

  // --- onParticipantAdded: late joiner semantics ---

  @Test
  void onParticipantAdded_addsPlayerAsParticipant() {
    PlayerAdapter joiner = stubPlayer(UUID.randomUUID(), "Late");
    game.onParticipantAdded(joiner);
    assertTrue(game.isParticipant(joiner.uniqueId()),
        "late joiner should be added to the participant set");
  }

  @Test
  void onParticipantAdded_preparesSurvival() {
    TrackedPlayer joiner = new TrackedPlayer(UUID.randomUUID(), "Late", 1.0);
    game.onParticipantAdded(joiner);
    assertEquals(20.0, joiner.health(), "health should be healed to max");
    assertEquals(20, joiner.foodLevel(), "food should be set to 20");
    assertEquals(GameModeType.SURVIVAL, joiner.gameMode());
  }

  @Test
  void onParticipantAdded_givesConfiguredKit() {
    PlayerAdapter joiner = stubPlayer(UUID.randomUUID(), "Late");
    int beforeKits = kits.applied.size();
    game.onParticipantAdded(joiner);
    assertEquals(beforeKits + 1, kits.applied.size());
    assertEquals("pvp", kits.applied.get(kits.applied.size() - 1).kitName());
    assertEquals(joiner.uniqueId(), kits.applied.get(kits.applied.size() - 1).playerId());
  }

  @Test
  void onParticipantAdded_nullPlayer_doesNotThrow() {
    int beforeKits = kits.applied.size();
    game.onParticipantAdded(null);
    assertEquals(beforeKits, kits.applied.size(), "no kit should be applied for null player");
  }

  @Test
  void onParticipantAdded_existingParticipant_doesNothing() {
    UUID id = UUID.randomUUID();
    PlayerAdapter joiner = stubPlayer(id, "Late");
    game.onParticipantAdded(joiner);
    int participantsAfterFirst = game.participantCount();
    int kitsAfterFirst = kits.applied.size();
    game.onParticipantAdded(joiner);
    assertEquals(participantsAfterFirst, game.participantCount(),
        "duplicate add should not double-count");
    assertEquals(kitsAfterFirst, kits.applied.size(),
        "duplicate add should not re-apply kit");
  }

  @Test
  void onParticipantAdded_blankKit_doesNotInvokeKitAdapter() {
    server.configuration().set("games.demo.kit", "");
    PlayerAdapter joiner = stubPlayer(UUID.randomUUID(), "Late");
    int beforeKits = kits.applied.size();
    game.onParticipantAdded(joiner);
    assertEquals(beforeKits, kits.applied.size(), "blank kit should not call apply");
  }

  @Test
  void onParticipantAdded_runsWhileGameRunning() {
    assertEquals(GameState.RUNNING, game.state());
    PlayerAdapter joiner = stubPlayer(UUID.randomUUID(), "Late");
    game.onParticipantAdded(joiner);
    assertEquals(GameState.RUNNING, game.state());
  }

  // --- minimal concrete subclass ---

  static final class RecordingTimedGame extends BaseTimedGame {
    RecordingTimedGame(GameContext gameContext) {
      super(gameContext, "demo", "Demo", 1);
    }
    @Override public void start(List<PlayerAdapter> players) { super.start(players); }
    @Override public void stop(LocalizedText reason) { super.stop(reason); }
    @Override protected void announceStarted() {}
    boolean isParticipant(UUID id) { return players.contains(id); }
    int participantCount() { return players.size(); }
  }

  // --- recording kit adapter ---

  private static final class RecordingKitAdapter implements KitAdapter {
    private final Set<String> known;
    final List<KitCall> applied = new java.util.ArrayList<>();
    RecordingKitAdapter(Set<String> known) { this.known = Set.copyOf(known); }
    @Override public boolean apply(PlayerAdapter playerAdapter, String kitName) {
      if (!exists(kitName)) return false;
      applied.add(new KitCall(playerAdapter.uniqueId(), kitName));
      return true;
    }
    @Override public boolean exists(String kitName) { return kitName != null && known.contains(kitName); }
    @Override public Set<String> names() { return known; }
    @Override public void reload() {}
    record KitCall(UUID playerId, String kitName) {}
  }

  // --- player stubs ---

  private static PlayerAdapter stubPlayer(UUID id, String name) {
    return new PlayerAdapter() {
      @Override public UUID uniqueId() { return id; }
      @Override public String name() { return name; }
      @Override public Locale locale() { return Locale.ROOT; }
      @Override public boolean hasPermission(String p) { return false; }
      @Override public void sendMiniMessage(String m) {}
      @Override public void sendPlainMessage(String m) {}
      @Override public boolean online() { return true; }
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
      @Override public InventoryAdapter inventory() { return null; }
      @Override public void playSound(SoundKey s, float v, float p) {}
      @Override public void showTitle(TitleSpec t) {}
      @Override public void sendActionBar(String m) {}
      @Override public void setCompassTarget(WorldPosition p) {}
      @Override public void clearInventory() {}
      @Override public void clearPotionEffects() {}
    };
  }

  static final class TrackedPlayer implements PlayerAdapter {
    private final UUID id;
    private final String playerName;
    private double health;
    private int food;
    private GameModeType mode = GameModeType.SURVIVAL;
    TrackedPlayer(UUID id, String name, double initialHealth) {
      this.id = id; this.playerName = name; this.health = initialHealth;
    }
    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return playerName; }
    @Override public Locale locale() { return Locale.ROOT; }
    @Override public boolean hasPermission(String p) { return false; }
    @Override public void sendMiniMessage(String m) {}
    @Override public void sendPlainMessage(String m) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition p) {}
    @Override public GameModeType gameMode() { return mode; }
    @Override public void setGameMode(GameModeType g) { this.mode = g; }
    @Override public double health() { return health; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double h) { this.health = h; }
    @Override public int foodLevel() { return food; }
    @Override public void setFoodLevel(int f) { this.food = f; }
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey s, float v, float p) {}
    @Override public void showTitle(TitleSpec t) {}
    @Override public void sendActionBar(String m) {}
    @Override public void setCompassTarget(WorldPosition p) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
  }
}
