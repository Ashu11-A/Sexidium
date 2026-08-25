package com.sexidium.core.menu;

import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.lobby.LobbyResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minigame discovery screens: the 54-slot per-category mode grid and the per-mode detail screen
 * offering quick-play queueing, hosting a lobby, and a live list of open lobbies.
 */
public final class MinigameMenu extends SidebarScreen {

  public MinigameMenu(MenuSupport support, MenuService menus) {
    super(support, menus);
  }

  @Override
  public String title() {
    return "<aqua><bold>Minigames</bold></aqua>";
  }

  @Override
  public String backgroundArt() {
    return MenuArt.BG_MINIGAMES;
  }

  @Override
  public boolean isAnimated() {
    return true;
  }

  @Override
  protected void buildSidebar(MenuView view, PlayerAdapter player) {
    SidebarNav.apply(view, player, menuService, support, SidebarNav.NavSection.MINIGAMES);
  }

  @Override
  protected void buildContent(MenuView view, PlayerAdapter player) {
    populateCategoryContent(view, player, "minigames");
  }

  /**
   * 54-slot sidebar screen displaying the minigames within a specific category with persistent global navigation.
   */
  public void openCategory(PlayerAdapter player, String category, String title) {
    GameManager gameManager = support.gameManager;
    MenuView view = new MenuView(title, ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_MINIGAMES)
        .animated(true);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail with MINIGAMES marked active
    SidebarNav.apply(view, player, menuService, support, SidebarNav.NavSection.MINIGAMES);

    // Content: Mode cards
    populateCategoryContent(view, player, category);

    // Bottom Nav: Slot 47 Back, Slot 53 Quick Play
    view.set(ChestLayout.SLOT_BACK, support.backButton(() -> menuService.openMain(player)));

    view.set(ChestLayout.SLOT_PRIMARY, MenuButton.of(ItemKey.minecraft("nether_star"),
        "<green><bold>⚡ Quick Play</bold></green>",
        List.of("<gray>Auto-queue for the most popular game</gray>", "<yellow>Click to queue</yellow>"),
        ctx -> {
          if (support.lobbyManager != null) {
            menuService.openCategory(ctx.player(), "minigames", "<aqua><bold>Minigames</bold></aqua>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_QUICK_PLAY)));

    support.open(player, view);
  }

  private void populateCategoryContent(MenuView view, PlayerAdapter player, String category) {
    GameManager gameManager = support.gameManager;
    List<GameModeDescriptor> modes = new ArrayList<>();
    for (GameModeDescriptor descriptor : gameManager.descriptors()) {
      if (MenuSupport.normalize(descriptor.category()).equals(MenuSupport.normalize(category))) {
        modes.add(descriptor);
      }
    }

    int groupSize = support.roster(player).size();
    boolean inMatch = gameManager.matchOf(player) != null;
    int contentIndex = 0;

    for (GameModeDescriptor descriptor : modes) {
      if (contentIndex >= ChestLayout.CONTENT_CAPACITY) {
        break;
      }
      String modeId = descriptor.modeId();
      int playing = gameManager.playersInMode(modeId)
          + (support.lobbyManager != null ? support.lobbyManager.queueSize(modeId) : 0);

      String status = inMatch
          ? "<red>✖ Leave your current game first</red>"
          : "<green>✔ Click to play</green>";
      List<String> colors = modeColors(modeId);
      List<String> frames = animatedNameFrames(MenuSupport.escape(descriptor.displayName()), colors);

      MenuButton tile = new MenuButton(support.icon(modeId), Math.max(1, Math.min(64, playing)),
          frames.get(0),
          support.modeLore(descriptor,
              "<gray>Playing now: <white>" + playing + "</white></gray>",
              "<gray>Your group: <white>" + groupSize + "</white></gray>",
              status),
          ctx -> {
            if (gameManager.matchOf(ctx.player()) != null) {
              ctx.player().sendActionBar("<red>Leave your current game first (/leave).</red>");
              return;
            }
            menuService.openModeDetail(ctx.player(), modeId);
          }, null, MenuArt.modeModel(modeId), frames);

      int slot = ChestLayout.contentSlot(contentIndex++);
      view.set(slot, tile);
    }
  }

  /**
   * 54-slot sidebar screen offering mode profile, Quick Play / Create Lobby, and live joinable lobbies.
   */
  public void openModeDetail(PlayerAdapter player, String modeId) {
    LobbyManager lobbyManager = support.lobbyManager;
    ServerAdapter serverAdapter = support.serverAdapter;
    GameModeDescriptor descriptor = support.descriptorOf(modeId);
    if (descriptor == null) {
      menuService.openCategory(player, "minigames", "<aqua><bold>Minigames</bold></aqua>");
      return;
    }
    String mode = descriptor.modeId();
    MenuView view = new MenuView("<aqua><bold>" + MenuSupport.escape(descriptor.displayName()) + "</bold></aqua>",
        ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_MINIGAMES);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail with MINIGAMES marked active
    SidebarNav.apply(view, player, menuService, support, SidebarNav.NavSection.MINIGAMES);

    // Content: Row 0 Header & Quick Actions (slots 3, 5, 7)
    view.set(3, MenuButton.label(support.icon(mode),
        "<white><bold>" + descriptor.displayName() + "</bold></white>",
        support.modeLore(descriptor)));

    boolean queuedHere = lobbyManager != null && mode.equals(lobbyManager.queuedMode(player.uniqueId()));
    view.set(5, MenuButton.of(ItemKey.minecraft("diamond_sword"),
        queuedHere ? "<yellow><bold>In quick-play queue</bold></yellow>" : "<green><bold>⚡ Quick Play</bold></green>",
        List.of("<gray>Auto-match with anyone online</gray>",
            queuedHere
                ? "<red>Click to leave the queue</red>"
                : "<yellow>Click to join the queue</yellow>"),
        ctx -> {
          if (lobbyManager == null) {
            ctx.player().sendActionBar("<red>Quick play is unavailable.</red>");
            return;
          }
          if (mode.equals(lobbyManager.queuedMode(ctx.player().uniqueId()))) {
            lobbyManager.dequeue(ctx.player());
            ctx.player().sendActionBar("<yellow>Left the quick-play queue.</yellow>");
            menuService.openModeDetail(ctx.player(), mode);
            return;
          }
          LobbyResult result = lobbyManager.queue(ctx.player(), mode);
          switch (result) {
            case QUEUED, ALREADY_QUEUED -> {
              serverAdapter.menus().close(ctx.player());
              ctx.player().sendActionBar("<green>Searching for a match… we'll start when enough players are ready.</green>");
            }
            case ALREADY_IN_MATCH -> ctx.player().sendActionBar("<red>Leave your current game first (/leave).</red>");
            case NOT_LEADER -> ctx.player().sendActionBar("<red>Only your group leader can queue. Leave your group to play solo.</red>");
            case NOT_MINIGAME -> ctx.player().sendActionBar("<red>That mode can't be quick-played.</red>");
            default -> ctx.player().sendActionBar("<red>Could not join the queue.</red>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_QUICK_PLAY)));

    view.set(7, MenuButton.of(ItemKey.minecraft("oak_sign"),
        "<gold><bold>⚒ Create Custom Lobby</bold></gold>",
        List.of("<gray>Host a private or public match</gray>",
            "<gray>Configure teams, invite friends & start</gray>",
            "<yellow>Click to create lobby</yellow>"),
        ctx -> {
          if (lobbyManager == null) {
            ctx.player().sendActionBar("<red>Lobbies are unavailable.</red>");
            return;
          }
          LobbyResult result = lobbyManager.configure(ctx.player(), mode);
          switch (result) {
            case CONFIGURED -> menuService.openLobby(ctx.player());
            case NOT_LEADER -> ctx.player().sendActionBar("<red>Only your group leader can host a match.</red>");
            case ALREADY_IN_MATCH -> ctx.player().sendActionBar("<red>You are already in a match.</red>");
            default -> ctx.player().sendActionBar("<red>Could not create the lobby.</red>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_CREATE_LOBBY)));

    // Open Lobbies Header / Grid (Rows 1–3: Content Slots 7..27 = physical slots 11..17, 20..26, 29..35)
    int contentIndex = 7;
    if (lobbyManager != null) {
      for (Lobby lobby : lobbyManager.joinableFor(player.uniqueId(), mode)) {
        if (contentIndex >= ChestLayout.CONTENT_CAPACITY) {
          break;
        }
        int slot = ChestLayout.contentSlot(contentIndex++);
        view.set(slot, menuService.lobbyButton(lobby));
      }
    }

    if (contentIndex == 7) {
      view.set(23, MenuButton.label(ItemKey.minecraft("paper"),
          "<gray><bold>No open lobbies for this mode</bold></gray>",
          List.of("<gray>Use <white>'Create Custom Lobby'</white> above to host one!</gray>")));
    }

    // Bottom Nav: Slot 47 Back
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> menuService.openCategory(ctx.player(), "minigames", "<aqua><bold>Minigames</bold></aqua>")));
    support.open(player, view);
  }

  // Distinct colour theme per game mode
  private static final Map<String, List<String>> MODE_COLORS = Map.of(
      "race", List.of("#55ffff", "#5599ff", "#5555ff"),
      "gather", List.of("#7cff4f", "#33cc33", "#aaff55"),
      "tntwar", List.of("#ff5555", "#ffaa00", "#ff2200"),
      "combat", List.of("#ffaa00", "#ff5555", "#ffdd55"),
      "fugitive", List.of("#ff55ff", "#aa55ff", "#ff88ff"));
  private static final List<String> DEFAULT_COLORS = List.of("#ffffff", "#c8c8c8", "#ffffff");

  private static List<String> modeColors(String modeId) {
    return MODE_COLORS.getOrDefault(modeId, DEFAULT_COLORS);
  }

  private static List<String> animatedNameFrames(String plainName, List<String> colors) {
    List<String> frames = new ArrayList<>();
    int steps = Math.max(2, colors.size());
    for (int frame = 0; frame < steps; frame++) {
      List<String> rotated = new ArrayList<>(colors.size());
      for (int index = 0; index < colors.size(); index++) {
        rotated.add(colors.get((index + frame) % colors.size()));
      }
      frames.add("<gradient:" + String.join(":", rotated) + "><bold>" + plainName + "</bold></gradient>");
    }
    return frames;
  }
}
