package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.command.CoreCommandService;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.MenuAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.lobby.MatchLauncher;
import com.sexidium.core.world.npc.NpcManager;

import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SidebarLayoutGridTest {
  private static final ItemKey DUMMY_ICON = ItemKey.minecraft("stone");

  private final List<MenuView> openedViews = new ArrayList<>();
  private TestServerAdapter server;
  private LobbyManager lobbyManager;
  private FriendService friendService;
  private NpcManager npcManager;
  private ExperienceManager experienceManager;
  private ExperienceService experienceService;
  private MenuSupport support;
  private MenuService menus;
  private TestPlayer player;

  private static class TestPlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final String name = "Tester";

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return name; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) {
      return CoreCommandService.ADMIN_PERMISSION.equals(permission);
    }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
  }

  @BeforeEach
  void setUp() throws Exception {
    openedViews.clear();
    player = new TestPlayer();

    server = new TestServerAdapter() {
      @Override
      public MenuAdapter menus() {
        return new MenuAdapter() {
          @Override
          public void open(PlayerAdapter p, MenuView view) {
            openedViews.add(view);
          }
          @Override
          public boolean isOpen(PlayerAdapter p) {
            return !openedViews.isEmpty();
          }
        };
      }
      @Override
      public List<PlayerAdapter> onlinePlayers() {
        return List.of(player);
      }
      @Override
      public Optional<PlayerAdapter> player(UUID id) {
        return player.uniqueId().equals(id) ? Optional.of(player) : Optional.empty();
      }
    };

    GameManager gameManager = new GameManager(
        new GameContext(server, new NoopKitAdapter(), com.sexidium.core.data.RankAwardPort.noop()),
        new GameRegistry(),
        null);

    MatchLauncher launcher = new MatchLauncher() {
      @Override
      public List<GameModeDescriptor> descriptors() {
        return List.of(new GameModeDescriptor("race", CoreGameRegistryInitializer.CATEGORY_MINIGAMES, "Race for Item", 1, List.of()));
      }

      @Override
      public ActiveMatch matchOf(UUID playerId) {
        return null;
      }

      @Override
      public boolean startWithPlayers(String modeId, Collection<UUID> participantIds, CommandSource initiator, List<String> modeArgs) {
        return true;
      }
    };

    lobbyManager = new LobbyManager(server, launcher, null);
    File dbFile = File.createTempFile("menutest", ".db");
    dbFile.deleteOnExit();
    friendService = new FriendService(server.logger(), new Database(dbFile));
    npcManager = new NpcManager(server, gameManager, lobbyManager);
    experienceManager = new ExperienceManager(server.logger(), new Database(dbFile));
    experienceService = new ExperienceService(server, gameManager, experienceManager, null, null);
    support = new MenuSupport(server, gameManager, lobbyManager, friendService, experienceService, npcManager);
    menus = new MenuService(server, gameManager, lobbyManager, friendService, experienceService, npcManager);
  }

  @Test
  @DisplayName("ChestLayout geometry constants match 54-slot 6x9 specifications")
  void chestLayoutConstants() {
    assertEquals(6, ChestLayout.ROWS);
    assertEquals(9, ChestLayout.COLUMNS);
    assertEquals(54, ChestLayout.SIZE);
    assertEquals(4, ChestLayout.CONTENT_ROWS);
    assertEquals(7, ChestLayout.CONTENT_COLUMNS);
    assertEquals(28, ChestLayout.CONTENT_CAPACITY);
    assertEquals(47, ChestLayout.BACK_SLOT);
    assertEquals(48, ChestLayout.PREV_PAGE_SLOT);
    assertEquals(49, ChestLayout.PAGE_INFO_SLOT);
    assertEquals(50, ChestLayout.NEXT_PAGE_SLOT);
    assertEquals(53, ChestLayout.PRIMARY_ACTION_SLOT);

    assertEquals(List.of(1, 10, 19, 28, 37, 46), ChestLayout.SEPARATOR_SLOTS);
    assertEquals(List.of(38, 39, 40, 41, 42, 43, 44), ChestLayout.ROW_DIVIDER_SLOTS);
    assertEquals(List.of(0, 9, 18, 27, 36, 45), ChestLayout.SIDEBAR_SLOTS);
    assertEquals(28, ChestLayout.CONTENT_SLOTS.size());

    // Slot 0 in content area is (row 0, col 2) = 2
    assertEquals(2, ChestLayout.contentSlot(0));
    // Slot 27 in content area is (row 3, col 8) = 35
    assertEquals(35, ChestLayout.contentSlot(27));

    assertTrue(ChestLayout.isSeparatorSlot(1));
    assertTrue(ChestLayout.isSeparatorSlot(46));
    assertTrue(ChestLayout.isSeparatorSlot(38));
    assertFalse(ChestLayout.isSeparatorSlot(47));

    assertTrue(ChestLayout.isContentSlot(2));
    assertTrue(ChestLayout.isContentSlot(35));
    assertFalse(ChestLayout.isContentSlot(1));

    assertTrue(ChestLayout.isSidebarSlot(0));
    assertTrue(ChestLayout.isSidebarSlot(36));
    assertFalse(ChestLayout.isSidebarSlot(47));
  }

  @Test
  @DisplayName("SidebarScreen places separators, sidebar, content, back and primary action correctly")
  void sidebarScreenBuilder() {
    AtomicReference<String> clicked = new AtomicReference<>();

    SidebarScreen.Builder screen = SidebarScreen.of("<gold>Test Screen</gold>")
        .background(MenuArt.BG_LOBBY)
        .sidebar(0, MenuButton.of(DUMMY_ICON, "Sidebar Action", ctx -> clicked.set("sidebar")))
        .content(ChestLayout.contentSlot(0), MenuButton.of(DUMMY_ICON, "Content 0", ctx -> clicked.set("content0")))
        .primaryAction(MenuButton.of(DUMMY_ICON, "Primary Action", ctx -> clicked.set("primary")))
        .back(ctx -> clicked.set("back"));

    MenuView view = screen.build();
    assertEquals(54, view.size());
    assertEquals(6, view.rows());
    assertEquals(MenuArt.BG_LOBBY, view.backgroundArt());

    // Separators filled in column 1
    for (int sepSlot : ChestLayout.SEPARATOR_SLOTS) {
      MenuButton sep = view.button(sepSlot);
      assertNotNull(sep, "separator at slot " + sepSlot);
      assertEquals(" ", sep.name());
    }

    // Sidebar button at slot 0
    assertNotNull(view.button(0));
    assertEquals("Sidebar Action", view.button(0).name());

    // Content button at slot 2
    assertNotNull(view.button(2));
    assertEquals("Content 0", view.button(2).name());

    // Back button at slot 47
    assertNotNull(view.button(ChestLayout.BACK_SLOT));
    assertTrue(view.button(ChestLayout.BACK_SLOT).name().contains("Back"));

    // Primary action at slot 53
    assertNotNull(view.button(ChestLayout.PRIMARY_ACTION_SLOT));
    assertEquals("Primary Action", view.button(ChestLayout.PRIMARY_ACTION_SLOT).name());
  }

  @Test
  @DisplayName("openSocialLobby renders 54 slots with back at 47 and separators")
  void socialLobbyLayout() {
    menus.openLobby(player);
    assertEquals(1, openedViews.size());
    MenuView view = openedViews.get(0);

    assertEquals(54, view.size());
    assertEquals(6, view.rows());
    assertNotNull(view.button(ChestLayout.BACK_SLOT), "back button at slot 47");
    assertTrue(view.button(ChestLayout.BACK_SLOT).name().contains("Back"));

    // Separators present
    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(view.button(sep), "separator at " + sep);
    }
  }

  @Test
  @DisplayName("openConfiguredLobby renders 54 slots with back at 47, host controls and separators")
  void configuredLobbyLayout() {
    lobbyManager.configure(player, "race");
    Lobby lobby = lobbyManager.lobbyOf(player.uniqueId());
    assertNotNull(lobby);

    menus.openLobby(player);
    assertEquals(1, openedViews.size());
    MenuView view = openedViews.get(0);

    assertEquals(54, view.size());
    assertEquals(6, view.rows());
    assertNotNull(view.button(ChestLayout.BACK_SLOT), "back at 47");

    // Host controls in sidebar
    assertNotNull(view.button(0), "teams control at slot 0");
    assertNotNull(view.button(ChestLayout.PRIMARY_ACTION_SLOT), "start match at slot 53");
  }

  @Test
  @DisplayName("openFriendActions renders 54 slots with back at 47 and action tiles")
  void friendActionsLayout() {
    UUID friendId = UUID.randomUUID();
    menus.openFriendActions(player, friendId, "BestFriend");
    assertEquals(1, openedViews.size());
    MenuView view = openedViews.get(0);

    assertEquals(54, view.size());
    assertEquals(6, view.rows());
    assertNotNull(view.button(ChestLayout.BACK_SLOT), "back at slot 47");
    assertNotNull(view.button(12), "invite to lobby at slot 12");
    assertNotNull(view.button(14), "join lobby at slot 14");
    assertNotNull(view.button(16), "warp at slot 16");
    assertNotNull(view.button(ChestLayout.PRIMARY_ACTION_SLOT), "remove friend at slot 53");
  }

  @Test
  @DisplayName("openAdminSettings and openMenuCalibration render 54 slots with back at 47")
  void adminMenuLayouts() {
    menus.openAdminSettings(player);
    assertEquals(1, openedViews.size());
    MenuView adminView = openedViews.get(0);
    assertEquals(54, adminView.size());
    assertNotNull(adminView.button(ChestLayout.BACK_SLOT), "back at slot 47");

    openedViews.clear();
    menus.openMenuCalibration(player);
    assertEquals(1, openedViews.size());
    MenuView calibView = openedViews.get(0);
    assertEquals(54, calibView.size());
    assertNotNull(calibView.button(ChestLayout.BACK_SLOT), "back at slot 47");

    // Check all 35 content markers
    for (int i = 0; i < ChestLayout.CONTENT_CAPACITY; i++) {
      int slot = ChestLayout.contentSlot(i);
      assertNotNull(calibView.button(slot), "slot marker at " + slot);
    }
  }

  @Test
  @DisplayName("Main Menu (openMain) adheres to 54 slots double-chest standard with sidebar in Col 0, separators in Col 1, and back at 47")
  void mainMenuLayout() {
    menus.openMain(player);
    assertEquals(1, openedViews.size());
    MenuView view = openedViews.get(0);

    assertEquals(54, view.size());
    assertEquals(6, view.rows());
    assertNotNull(view.button(ChestLayout.SLOT_BACK), "close/back button at slot 47");
    assertNotNull(view.button(ChestLayout.SLOT_PRIMARY), "primary action at slot 53");

    // Column 1 separators
    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(view.button(sep), "separator at " + sep);
    }

    // Column 0 sidebar
    for (int i = 0; i < ChestLayout.SIDEBAR_CAPACITY; i++) {
      int slot = ChestLayout.sidebarSlot(i);
      assertNotNull(view.button(slot), "sidebar tab at slot " + slot);
    }
  }

  @Test
  @DisplayName("Minigame category and mode detail screens adhere to 54 slots double-chest layout")
  void minigameScreensLayout() {
    menus.openCategory(player, CoreGameRegistryInitializer.CATEGORY_MINIGAMES, "Minigames");
    assertEquals(1, openedViews.size());
    MenuView catView = openedViews.get(0);
    assertEquals(54, catView.size());
    assertNotNull(catView.button(ChestLayout.SLOT_BACK), "back at 47");
    assertNotNull(catView.button(ChestLayout.SLOT_PRIMARY), "quick play at 53");

    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(catView.button(sep), "separator at " + sep);
    }

    openedViews.clear();
    menus.openModeDetail(player, "race");
    assertEquals(1, openedViews.size());
    MenuView detailView = openedViews.get(0);
    assertEquals(54, detailView.size());
    assertNotNull(detailView.button(ChestLayout.SLOT_BACK), "back at 47");
    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(detailView.button(sep), "separator at " + sep);
    }
  }

  @Test
  @DisplayName("Social Invites screen adheres to 54 slots double-chest layout with back at 47 and clean separators")
  void socialInvitesLayout() {
    menus.openInvites(player);
    assertEquals(1, openedViews.size());
    MenuView view = openedViews.get(0);
    assertEquals(54, view.size());
    assertEquals(6, view.rows());
    assertNotNull(view.button(ChestLayout.SLOT_BACK), "back at 47");

    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(view.button(sep), "separator at " + sep);
    }
  }

  @Test
  @DisplayName("Experience screens adhere to 54 slots double-chest standard with clean separators and content slots")
  void experienceScreensLayout() {
    // 1. Experience Builder
    menus.openExperienceBuilder(player);
    assertEquals(1, openedViews.size());
    MenuView builderView = openedViews.get(0);
    assertEquals(54, builderView.size());
    assertNotNull(builderView.button(ChestLayout.SLOT_BACK), "back at 47");
    assertNotNull(builderView.button(ChestLayout.SLOT_PRIMARY), "create at 53");
    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(builderView.button(sep));
    }

    // 2. World Type Picker
    openedViews.clear();
    menus.openExperienceWorldType(player, "dummy-id");
    assertEquals(1, openedViews.size());
    MenuView worldTypeView = openedViews.get(0);
    assertEquals(54, worldTypeView.size());
    assertNotNull(worldTypeView.button(ChestLayout.SLOT_BACK));

    // 3. My Experiences
    openedViews.clear();
    menus.openExperiences(player);
    assertEquals(1, openedViews.size());
    MenuView expView = openedViews.get(0);
    assertEquals(54, expView.size());
    assertNotNull(expView.button(ChestLayout.SLOT_BACK));
    assertNotNull(expView.button(ChestLayout.SLOT_PRIMARY));

    // 4. Experience Manage & Backup screens
    ExperienceManager.Experience exp = experienceManager.create(player.uniqueId(), player.name(), List.of("randomdrops"), "My World", System.currentTimeMillis());
    assertNotNull(exp);

    openedViews.clear();
    menus.openExperienceManage(player, exp.id());
    assertEquals(1, openedViews.size());
    MenuView manageView = openedViews.get(0);
    assertEquals(54, manageView.size());
    assertNotNull(manageView.button(ChestLayout.SLOT_BACK));
    // Verify Column 1 separators are intact
    for (int sep : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(manageView.button(sep), "separator at " + sep);
      assertEquals(" ", manageView.button(sep).name());
    }

    // 5. Backups screen
    openedViews.clear();
    menus.openBackups(player, exp.id());
    assertEquals(1, openedViews.size());
    MenuView backupsView = openedViews.get(0);
    assertEquals(54, backupsView.size());
    assertNotNull(backupsView.button(ChestLayout.SLOT_BACK));

    // 6. Experience Browse
    openedViews.clear();
    menus.openBrowse(player);
    assertEquals(1, openedViews.size());
    MenuView browseView = openedViews.get(0);
    assertEquals(54, browseView.size());
    assertNotNull(browseView.button(ChestLayout.SLOT_BACK));
  }

  @Test
  @DisplayName("2-tap confirmation gestures protect destructive actions across menus")
  void destructiveConfirmationGestures() {
    UUID dummyFriendId = UUID.randomUUID();
    String unfriendToken = "unfriend:" + dummyFriendId;

    assertFalse(support.isArmed(player.uniqueId(), unfriendToken));
    support.confirmStep(new MenuContext(player, MenuContext.ClickType.LEFT), unfriendToken);
    assertTrue(support.isArmed(player.uniqueId(), unfriendToken));
    support.clearConfirm(player.uniqueId());
    assertFalse(support.isArmed(player.uniqueId(), unfriendToken));

    String lobbyLeaveToken = "lobby-leave";
    assertFalse(support.isArmed(player.uniqueId(), lobbyLeaveToken));
    support.confirmStep(new MenuContext(player, MenuContext.ClickType.LEFT), lobbyLeaveToken);
    assertTrue(support.isArmed(player.uniqueId(), lobbyLeaveToken));
    support.clearConfirm(player.uniqueId());
  }
}
