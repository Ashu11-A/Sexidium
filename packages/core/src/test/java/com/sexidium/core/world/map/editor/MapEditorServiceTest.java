package com.sexidium.core.world.map.editor;

import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.DirectSchedulerAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import com.sexidium.core.world.map.BattleMap;
import com.sexidium.core.world.map.BattleMapStore;
import com.sexidium.core.world.map.Cuboid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapEditorServiceTest {
  private static final String WORLD = "minigames/arena1";
  private static final ItemKey AXE = ItemKey.minecraft("golden_axe");

  @Test
  void enterOpensSessionGivesToolsAndSwitchesToCreative(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);

    MapEditorService.Result result = service.enter(admin, "tntwar", "arena1", WORLD);
    assertTrue(result.ok(), result.message());
    assertTrue(service.hasSession(admin.uniqueId()));
    assertEquals(GameModeType.CREATIVE, admin.gameMode);
    assertTrue(admin.inventory().contains(AXE), "the selection axe should be handed out");
    assertTrue(admin.inventory().contains(ItemKey.minecraft("iron_pickaxe")), "the delete tool");
    assertTrue(admin.inventory().contains(ItemKey.minecraft("name_tag")), "the team-switch tool");
    assertTrue(admin.inventory().contains(ItemKey.minecraft("ender_pearl")), "the set-spawn tool");
    assertTrue(admin.inventory().contains(ItemKey.minecraft("lime_dye")), "the confirm tool");
    assertTrue(admin.inventory().contains(ItemKey.minecraft("red_dye")), "the cancel tool");
  }

  @Test
  void leftAndRightClickSetTheFocusedTeamCornersAndPersist(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);

    BlockPosition corner1 = new BlockPosition(WORLD, 10, 64, -5);
    BlockPosition corner2 = new BlockPosition(WORLD, 20, 80, 5);
    PlayerInteractGameEvent left = interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE, corner1);
    PlayerInteractGameEvent right = interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, AXE, corner2);

    assertTrue(service.onInteract(left));
    assertTrue(service.onInteract(right));
    assertTrue(left.cancelled(), "the editor must swallow the axe click so it never breaks a block");

    assertTrue(service.save(admin.uniqueId()).ok());
    BattleMap loaded = BattleMapStore.load(root.resolve(WORLD), "arena1");
    Cuboid region = loaded.teamRegion(0);
    assertNotNull(region, "team 0's region should be persisted");
    assertEquals(10, region.minX());
    assertEquals(20, region.maxX());
    assertEquals(-5, region.minZ());
  }

  @Test
  void focusTeamRedirectsTheAxeToAnotherTeam(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);

    assertTrue(service.focusTeam(admin.uniqueId(), 1).ok());
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE, new BlockPosition(WORLD, -10, 64, -5)));
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, AXE, new BlockPosition(WORLD, -20, 80, 5)));
    assertTrue(service.save(admin.uniqueId()).ok());

    BattleMap loaded = BattleMapStore.load(root.resolve(WORLD), "arena1");
    Cuboid blue = loaded.teamRegion(1);
    assertNotNull(blue);
    assertEquals(-20, blue.minX());
  }

  @Test
  void addSpawnCapturesThePlayerPosition(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);
    // Admin walks to the spot they want as a spawn, then runs /sx mapedit spawn.
    admin.position = new WorldPosition(WORLD, 15.5, 65.0, 0.5, -90.0f, 0.0f);

    assertTrue(service.addSpawn(admin.uniqueId()).ok());
    assertTrue(service.save(admin.uniqueId()).ok());

    BattleMap loaded = BattleMapStore.load(root.resolve(WORLD), "arena1");
    assertEquals(1, loaded.zone(0).spawns().size());
    assertEquals(15.5, loaded.zone(0).spawns().get(0).coordinateX(), 1e-9);
    assertEquals(-90.0f, loaded.zone(0).spawns().get(0).yaw());
  }

  @Test
  void clicksWithoutTheAxeOrWithoutASessionAreIgnored(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);

    // No session yet.
    assertFalse(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE,
        new BlockPosition(WORLD, 0, 64, 0))));

    service.enter(admin, "tntwar", "arena1", WORLD);
    // A bare hand / wrong item is not the selection tool.
    PlayerInteractGameEvent sword = interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK,
        ItemKey.minecraft("diamond_sword"), new BlockPosition(WORLD, 0, 64, 0));
    assertFalse(service.onInteract(sword));
    assertFalse(sword.cancelled());
  }

  @Test
  void exitClosesTheSessionRestoresInventoryAndGameMode(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    admin.gameMode = GameModeType.SURVIVAL;
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);
    assertTrue(admin.inventory().contains(AXE));

    service.exit(admin.uniqueId());
    assertFalse(service.hasSession(admin.uniqueId()));
    assertFalse(admin.inventory().contains(AXE), "the tools are taken back on exit");
    assertEquals(GameModeType.SURVIVAL, admin.gameMode, "the prior game mode is restored");
  }

  @Test
  void undoRevertsTheLastCorner(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE, new BlockPosition(WORLD, 0, 64, 0)));
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, AXE, new BlockPosition(WORLD, 9, 70, 9)));

    // Undo (clock, right-click) drops the second corner, so the region is no longer complete.
    assertTrue(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK,
        ItemKey.minecraft("clock"), null)));
    assertTrue(service.save(admin.uniqueId()).ok());
    assertNull(BattleMapStore.load(root.resolve(WORLD), "arena1").teamRegion(0));
  }

  @Test
  void deleteToolRemovesTheStruckBox(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE, new BlockPosition(WORLD, 0, 64, 0)));
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, AXE, new BlockPosition(WORLD, 10, 74, 10)));

    // Strike (left-click) a block inside team 0's box with the iron pick.
    assertTrue(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK,
        ItemKey.minecraft("iron_pickaxe"), new BlockPosition(WORLD, 5, 68, 5))));
    assertTrue(service.save(admin.uniqueId()).ok());
    assertNull(BattleMapStore.load(root.resolve(WORLD), "arena1").teamRegion(0));
  }

  @Test
  void confirmSavesAndExits(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE, new BlockPosition(WORLD, 0, 64, 0)));
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, AXE, new BlockPosition(WORLD, 9, 70, 9)));

    assertTrue(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK,
        ItemKey.minecraft("lime_dye"), null)));
    assertFalse(service.hasSession(admin.uniqueId()), "confirm exits the editor");
    Cuboid region = BattleMapStore.load(root.resolve(WORLD), "arena1").teamRegion(0);
    assertNotNull(region, "confirm persisted the map");
    assertEquals(9, region.maxX());
  }

  @Test
  void cancelExitsWithoutSaving(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE, new BlockPosition(WORLD, 0, 64, 0)));
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, AXE, new BlockPosition(WORLD, 9, 70, 9)));

    assertTrue(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK,
        ItemKey.minecraft("red_dye"), null)));
    assertFalse(service.hasSession(admin.uniqueId()), "cancel exits the editor");
    // Nothing was written, so a fresh load has no region.
    assertNull(BattleMapStore.load(root.resolve(WORLD), "arena1").teamRegion(0));
  }

  @Test
  void spawnToolRecordsTheTeamSpawnWithFacing(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);
    admin.position = new WorldPosition(WORLD, 3.5, 65.0, 7.5, 180.0f, -5.0f);

    // Ender-pearl right-click captures the admin's exact position + facing as team 0's spawn.
    assertTrue(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK,
        ItemKey.minecraft("ender_pearl"), null)));
    assertTrue(service.save(admin.uniqueId()).ok());

    BattleMap loaded = BattleMapStore.load(root.resolve(WORLD), "arena1");
    assertEquals(1, loaded.zone(0).spawns().size());
    assertEquals(3.5, loaded.zone(0).spawns().get(0).coordinateX(), 1e-9);
    assertEquals(180.0f, loaded.zone(0).spawns().get(0).yaw());
  }

  @Test
  void confirmBakesWorldBlocksIntoTheTemplate(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    FakeServer server = new FakeServer(root, admin);
    MapEditorService service = new MapEditorService(server);
    service.enter(admin, "tntwar", "arena1", WORLD);

    // Confirm (lime dye) finalises: unload-with-save the edit clone + copy its blocks onto the template.
    assertTrue(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK,
        ItemKey.minecraft("lime_dye"), null)));
    assertFalse(service.hasSession(admin.uniqueId()), "confirm exits the editor");
    assertEquals(WORLD, ((FakeLeaseService) server.worlds()).savedTemplate);
  }

  @Test
  void teamToolCyclesTheFocusedTeam(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);
    service.enter(admin, "tntwar", "arena1", WORLD);

    // Name-tag right-click moves focus 0 -> 1, so the axe now edits team 1.
    assertTrue(service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK,
        ItemKey.minecraft("name_tag"), null)));
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.LEFT_CLICK, AXE, new BlockPosition(WORLD, -10, 64, -5)));
    service.onInteract(interact(admin, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, AXE, new BlockPosition(WORLD, -20, 80, 5)));
    assertTrue(service.save(admin.uniqueId()).ok());

    BattleMap loaded = BattleMapStore.load(root.resolve(WORLD), "arena1");
    assertNotNull(loaded.teamRegion(1), "the cycled-to team 1 got the corners");
    assertNull(loaded.teamRegion(0), "team 0 was untouched after cycling away");
  }

  @Test
  void blockBreakIsVetoedOnlyWhileHoldingAToolInASession(@TempDir Path root) {
    FakePlayer admin = new FakePlayer();
    MapEditorService service = serviceFor(root, admin);

    admin.held = AXE;
    assertFalse(service.shouldCancelBlockBreak(admin), "no session yet");

    service.enter(admin, "tntwar", "arena1", WORLD);
    assertTrue(service.shouldCancelBlockBreak(admin), "holding a tool in a session");
    admin.held = ItemKey.minecraft("stone");
    assertFalse(service.shouldCancelBlockBreak(admin), "holding a normal block still builds in creative");
  }

  // ----- fakes -------------------------------------------------------------------------------------

  private MapEditorService serviceFor(Path root, FakePlayer admin) {
    return new MapEditorService(new FakeServer(root, admin));
  }

  private static PlayerInteractGameEvent interact(PlayerAdapter player, PlayerInteractGameEvent.ActionType action,
      ItemKey item, BlockPosition block) {
    return new PlayerInteractGameEvent(player, action, item, block);
  }

  /** Server wired only with the pieces the editor touches: world lease, scheduler, logger, player lookup. */
  private static final class FakeServer implements ServerAdapter {
    private final FakeLeaseService worlds;
    private final FakePlayer admin;

    FakeServer(Path root, FakePlayer admin) {
      this.worlds = new FakeLeaseService(root);
      this.admin = admin;
    }

    @Override public WorldLeaseService worlds() { return worlds; }
    @Override public SchedulerAdapter scheduler() { return new DirectSchedulerAdapter(); }
    @Override public LoggerAdapter logger() { return new StdoutLoggerAdapter("Test"); }
    @Override public Optional<PlayerAdapter> player(UUID id) {
      return admin.uniqueId().equals(id) ? Optional.of(admin) : Optional.empty();
    }

    @Override public String serverName() { return "Test"; }
    @Override public PlatformType platformType() { return PlatformType.UNKNOWN; }
    @Override public Path dataDirectory() { return Path.of("."); }
    @Override public ConfigurationAdapter configuration() { return null; }
    @Override public ResourceAdapter resources() { return null; }
    @Override public UiAdapter ui() { return null; }
    @Override public MessageAdapter messages() { return null; }
    @Override public EventDispatcherAdapter events() { return null; }
    @Override public CommandDispatcherAdapter commands() { return null; }
    @Override public CommandSource console() { return null; }
    @Override public Collection<PlayerAdapter> onlinePlayers() { return List.of(admin); }
    @Override public Optional<PlayerAdapter> playerExact(String name) { return Optional.empty(); }
  }

  /** Hands out one in-memory world for {@code reacquireByName} and roots sidecars under a temp dir. */
  private static final class FakeLeaseService implements WorldLeaseService {
    private final Path root;
    private final FakeWorld world = new FakeWorld();
    private String savedTemplate;

    FakeLeaseService(Path root) { this.root = root; }

    @Override public boolean saveTemplateAndDispose(WorldLease lease, String templateWorldName) {
      this.savedTemplate = templateWorldName;
      if (lease != null) {
        lease.close();
      }
      return true;
    }

    @Override public Optional<WorldLease> reacquireByName(String worldName) {
      return Optional.of(new FakeLease(world));
    }

    @Override public Path worldRoot() { return root; }
    @Override public Optional<WorldPosition> lobbySpawn() { return Optional.empty(); }

    @Override public boolean enabled() { return true; }
    @Override public void start() {}
    @Override public void preserve(Collection<String> worldNames) {}
    @Override public void discardByName(String worldName) {}
    @Override public String lobbyName() { return "lobby"; }
    @Override public Path tempSubdir() { return root.resolve("temp"); }
    @Override public Optional<WorldLease> acquireReady(com.sexidium.core.world.WorldProfile profile) {
      return Optional.empty();
    }
    @Override public void acquireOrCreate(Collection<? extends PlayerAdapter> viewers,
        Consumer<WorldLease> onReady, Runnable onFailure) {}
    // The editor clones the template via acquireOrCreateClone; serve the in-memory world synchronously.
    @Override public void acquireOrCreateClone(String templateWorldName, Collection<? extends PlayerAdapter> viewers,
        Consumer<WorldLease> onReady, Runnable onFailure) {
      onReady.accept(new FakeLease(world));
    }
    @Override public void shutdown() {}
  }

  private record FakeLease(WorldAdapter world) implements WorldLease {
    @Override public void close() {}
  }

  private static final class FakeWorld implements WorldAdapter {
    @Override public String name() { return WORLD; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition(WORLD, 0.5, 64.0, 0.5, 0f, 0f); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }

  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final FakeInventory inventory = new FakeInventory();
    private GameModeType gameMode = GameModeType.SURVIVAL;
    private WorldPosition position = new WorldPosition(WORLD, 0.5, 64.0, 0.5, 0f, 0f);
    private ItemKey held;

    @Override public ItemKey heldItem() { return held; }
    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "Admin"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String p) { return true; }
    @Override public void sendMiniMessage(String m) {}
    @Override public void sendPlainMessage(String m) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return position; }
    @Override public void teleport(WorldPosition p) { if (p != null) { this.position = p; } }
    @Override public GameModeType gameMode() { return gameMode; }
    @Override public void setGameMode(GameModeType g) { this.gameMode = g; }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double h) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int f) {}
    @Override public InventoryAdapter inventory() { return inventory; }
    @Override public void playSound(SoundKey s, float v, float p) {}
    @Override public void showTitle(TitleSpec t) {}
    @Override public void sendActionBar(String m) {}
    @Override public void setCompassTarget(WorldPosition p) {}
    @Override public void clearInventory() { inventory.clear(); }
    @Override public void clearPotionEffects() {}
  }

  private static final class FakeInventory implements InventoryAdapter {
    private final List<ItemStackData> contents = new ArrayList<>();

    @Override public void clear() { contents.clear(); }
    @Override public boolean contains(ItemKey itemKey) {
      return contents.stream().anyMatch(s -> s != null && s.itemKey() != null && s.itemKey().equals(itemKey));
    }
    @Override public void add(ItemStackData itemStackData) { contents.add(itemStackData); }
    @Override public List<ItemStackData> storageContents() { return new ArrayList<>(contents); }
    @Override public void setStorageContents(List<ItemStackData> itemStacks) {
      contents.clear();
      for (ItemStackData stack : itemStacks) {
        if (stack != null) {
          contents.add(stack);
        }
      }
    }
    @Override public Map<String, List<ItemStackData>> equipmentContents() { return Map.of(); }
    @Override public void setEquipmentContents(Map<String, List<ItemStackData>> equipmentContents) {}
  }
}
