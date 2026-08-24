package com.sexidium.core.game;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.KitAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoreGameRegistryInitializerTest {
  private static final List<String> MODE_IDS = List.of(
      "race", "gather", "tntwar", "combat", "fugitive", "experience", "chaos"
  );

  @Test
  void create_registersAllCoreModesInOrder() {
    GameRegistry registry = CoreGameRegistryInitializer.create();

    assertEquals(MODE_IDS, registry.modeIds());
    assertEquals(7, registry.descriptors().size());
  }

  @Test
  void create_registersExistingAliases() {
    GameRegistry registry = CoreGameRegistryInitializer.create();

    assertTrue(registry.contains("race-for-item"));
    assertTrue(registry.contains("duel"));
    assertTrue(registry.contains("kitpvp"));
    assertTrue(registry.contains("exp"));
    assertTrue(registry.contains("experiences"));
    assertFalse(registry.contains("minecraftbut"));
  }

  @Test
  void create_factoriesProduceCoreGamesForEveryDescriptor() {
    GameRegistry registry = CoreGameRegistryInitializer.create();
    GameContext context = new GameContext(new TestServerAdapter(), new TrackingKitAdapter(), RankAwardPort.noop());

    for (String modeId : MODE_IDS) {
      Optional<Game> game = registry.create(context, modeId, List.of());
      assertTrue(game.isPresent(), modeId + " should create a game");
      assertEquals(modeId, game.get().id());
      String packageName = game.get().getClass().getPackageName();
      assertTrue(
          packageName.equals("com.sexidium.core.game.modes.minigames")
              || packageName.equals("com.sexidium.core.game.experience")
              || packageName.equals("com.sexidium.core.game.chaos"),
          modeId + " should live in a category subpackage but was " + packageName);
    }
  }

  @Test
  void mode_startPreparesParticipantsAndAppliesConfiguredKit() {
    TrackingKitAdapter kits = new TrackingKitAdapter();
    TestServerAdapter server = new TestServerAdapter();
    server.configuration().set("minigames.combat.kit", "arena");
    GameContext context = new GameContext(server, kits, RankAwardPort.noop());
    Game game = CoreGameRegistryInitializer.create().create(context, "combat", List.of()).orElseThrow();
    FakePlayer first = new FakePlayer("First");
    FakePlayer second = new FakePlayer("Second");

    game.start(List.of(first, second));

    assertEquals(GameState.RUNNING, game.state());
    assertEquals(GameModeType.SURVIVAL, first.gameMode());
    assertEquals(20, first.foodLevel());
    assertEquals(first.maxHealth(), first.health());
    assertEquals(List.of("arena", "arena"), kits.appliedKits);
  }

  @Test
  void chaos_startsRollsTwistsAndStopsSafely() {
    TestServerAdapter server = new TestServerAdapter();
    server.configuration().set("chaos.pool", List.of("doubledrops"));
    server.configuration().set("chaos.challenges-per-player", 1);
    GameContext context = new GameContext(server, new TrackingKitAdapter(), RankAwardPort.noop());
    Game game = CoreGameRegistryInitializer.create().create(context, "chaos", List.of()).orElseThrow();

    assertDoesNotThrow(() -> game.start(List.of(new FakePlayer("Chaos"))));
    assertEquals(GameState.RUNNING, game.state());
    assertDoesNotThrow(() -> game.stop(null));
    assertEquals(GameState.ENDED, game.state());
    assertTrue(game.isEmpty());
  }

  @Test
  void mode_stopEndsAndCanBeCalledSafely() {
    GameContext context = new GameContext(new TestServerAdapter(), new TrackingKitAdapter(), RankAwardPort.noop());
    Game game = CoreGameRegistryInitializer.create().create(context, "experience", List.of("doubledrops")).orElseThrow();
    game.start(List.of(new FakePlayer("Player")));

    assertDoesNotThrow(() -> game.stop(null));

    assertEquals(GameState.ENDED, game.state());
    assertTrue(game.isEmpty());
  }

  private static final class TrackingKitAdapter implements KitAdapter {
    private final List<String> appliedKits = new ArrayList<>();

    @Override
    public boolean apply(PlayerAdapter playerAdapter, String kitName) {
      appliedKits.add(kitName);
      return true;
    }

    @Override public boolean exists(String kitName) { return true; }
    @Override public Set<String> names() { return Set.of("arena"); }
    @Override public void reload() {}
  }

  private static final class FakePlayer implements PlayerAdapter {
    private final UUID uniqueId = UUID.randomUUID();
    private final String name;
    private final FakeInventory inventory = new FakeInventory();
    private GameModeType gameMode = GameModeType.ADVENTURE;
    private double health = 1.0;
    private int foodLevel = 1;

    private FakePlayer(String name) {
      this.name = name;
    }

    @Override public UUID uniqueId() { return uniqueId; }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return gameMode; }
    @Override public void setGameMode(GameModeType gameModeType) { this.gameMode = gameModeType; }
    @Override public double health() { return health; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double health) { this.health = health; }
    @Override public int foodLevel() { return foodLevel; }
    @Override public void setFoodLevel(int foodLevel) { this.foodLevel = foodLevel; }
    @Override public InventoryAdapter inventory() { return inventory; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() { inventory.clear(); }
    @Override public void clearPotionEffects() {}
    @Override public String name() { return name; }
    @Override public Locale locale() { return Locale.ROOT; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
  }

  private static final class FakeInventory implements InventoryAdapter {
    private List<ItemStackData> contents = new ArrayList<>();
    private Map<String, List<ItemStackData>> equipment = new HashMap<>();

    @Override public void clear() { contents.clear(); }
    @Override public boolean contains(ItemKey itemKey) { return contents.stream().anyMatch(item -> item.itemKey().equals(itemKey)); }
    @Override public void add(ItemStackData itemStackData) { contents.add(itemStackData); }
    @Override public List<ItemStackData> storageContents() { return List.copyOf(contents); }
    @Override public void setStorageContents(List<ItemStackData> itemStacks) { contents = new ArrayList<>(itemStacks); }
    @Override public Map<String, List<ItemStackData>> equipmentContents() { return Map.copyOf(equipment); }
    @Override public void setEquipmentContents(Map<String, List<ItemStackData>> equipmentContents) { equipment = new HashMap<>(equipmentContents); }
  }
}
