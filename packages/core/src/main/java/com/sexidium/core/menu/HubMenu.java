package com.sexidium.core.menu;

import com.sexidium.core.Branding;
import com.sexidium.core.command.CoreCommandService;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.menu.scene.SceneTemplates;

import java.util.List;
import java.util.UUID;

/**
 * The declarative {@code /sx} hub. Every top-level interface is registered here as a {@link MenuTab};
 * {@link #openMain} renders them as a permission-filtered, centered icon grid (or the baked
 * medieval-card art when the tab count matches a known hub layout). State-aware tiles (Lobby, Friends)
 * compute their lore per viewer; the admin tile is gated by a visibility predicate.
 */
final class HubMenu {
  private static final String MAIN_TITLE = "<gradient:#ff5f6d:#ffc371><bold><brand></bold></gradient>";

  private final MenuSupport support;
  private final MenuService menus;
  // The declarative hub registry: every /sx interface is a tab here, and openMain renders them.
  private final MenuCatalog catalog = new MenuCatalog();

  HubMenu(MenuSupport support, MenuService menus) {
    this.support = support;
    this.menus = menus;
    registerHubTabs();
  }

  /**
   * Registers every {@code /sx} interface as a hub {@link MenuTab}. This is the one place a new
   * top-level interface is wired into the main menu — add a tab and it appears, permission-filtered,
   * with its custom icon, in {@link #openMain}. State-aware tiles (Lobby, Friends) compute their lore
   * per viewer; the admin tile is gated by a visibility predicate.
   */
  private void registerHubTabs() {
    catalog.register(MenuTab.of("minigames", ItemKey.minecraft("diamond_sword"),
        MenuArt.model(MenuArt.ICON_MINIGAMES), "<aqua><bold>Minigames</bold></aqua>",
        List.of("<gray>Competitive game modes</gray>"),
        p -> menus.openCategory(p, "minigames", "<aqua><bold>Minigames</bold></aqua>")));
    catalog.register(MenuTab.of("experience-create", ItemKey.minecraft("tnt"),
        MenuArt.model(MenuArt.ICON_EXPERIENCE_CREATE), "<gold><bold>Create Experience</bold></gold>",
        List.of("<gray>Build a survival challenge experience</gray>", "<gray>Pick any mix of twists</gray>"),
        p -> {
          support.resetBuilder(p.uniqueId());
          menus.openExperienceBuilder(p);
        }));
    catalog.register(MenuTab.of("experience-mine", ItemKey.minecraft("ender_chest"),
        MenuArt.model(MenuArt.ICON_EXPERIENCE_MINE), "<yellow><bold>My Experiences</bold></yellow>",
        List.of("<gray>Worlds you created</gray>"), menus::openExperiences));
    catalog.register(MenuTab.of("browse", ItemKey.minecraft("compass"),
        MenuArt.model(MenuArt.ICON_BROWSE), "<blue><bold>Browse Worlds</bold></blue>",
        List.of("<gray>Friends' worlds & public experiences</gray>"), menus::openBrowse));
    // One unified Lobby tile (your group + quick-play + host a match), state-aware, replacing the old
    // separate Match Lobbies / Party / queue-status tiles.
    catalog.register(MenuTab.dynamic("lobby", ItemKey.minecraft("cake"),
        MenuArt.model(MenuArt.ICON_LOBBY), "<light_purple><bold>Lobby</bold></light_purple>",
        this::mainLobbyLore, menus::openLobby));
    catalog.register(MenuTab.dynamic("friends", ItemKey.minecraft("player_head"),
        MenuArt.model(MenuArt.ICON_FRIENDS), "<green><bold>Friends</bold></green>",
        this::mainFriendsLore, menus::openFriends));
    // Admin-only control panel — visible only to operators (anyone holding sexidium.admin, which ops
    // have by default), so regular players never see server-configuration tools.
    catalog.register(MenuTab.of("admin", ItemKey.minecraft("command_block"),
            MenuArt.model(MenuArt.ICON_ADMIN), "<red><bold>Admin Settings</bold></red>",
            List.of("<gray>Configure NPCs & server</gray>", "<dark_gray>Operators only</dark_gray>"),
            menus::openAdminSettings)
        .visibleWhen(p -> p.hasPermission(CoreCommandService.ADMIN_PERMISSION)));
  }

  void openMain(PlayerAdapter player) {
    List<MenuTab> tabs = catalog.visibleFor(player);
    boolean opHub = tabs.size() == SceneTemplates.HUB_TABS_OP;        // 7 = admin/Settings visible (operator)
    boolean normalHub = tabs.size() == SceneTemplates.HUB_TABS_NORMAL; // 6 = normal
    // A baked hub fills the full six-row window: the chest_6 wood frame is the background and the transparent
    // medieval-card overlay paints on top. Other tab counts keep the plain centered icon-grid fallback.
    int rows = (opHub || normalHub) ? 6 : Math.max(3, Math.min(6, ((tabs.size() - 1) / 7) + 2));
    // The baked hub art needs a full six-row window, but a no-pack / Bedrock viewer only sees the plain
    // centered icon grid — which layoutCentered packs into a single row — so it renders in a compact
    // three-row (27-slot) chest instead. The renderer picks size() vs plainSize() per viewer's pack state.
    String brandLabel = support.brandLabel();
    MenuView view = new MenuView(MAIN_TITLE.replace("<brand>", brandLabel), rows)
        .plainRows(3).background(MenuArt.BG_MAIN);
    layoutCentered(view, tabs, player);
    // Bundled hub textures contain the default brand, so do not show stale art for a custom label.
    if (opHub && Branding.DEFAULT_LABEL.equals(brandLabel)) {
      view.screenArt("main-hub-op");
    } else if (normalHub && Branding.DEFAULT_LABEL.equals(brandLabel)) {
      view.screenArt("main-hub");
    }
    support.open(player, view);
  }

  /** State-aware lore for the unified Lobby hub tile. */
  private List<String> mainLobbyLore(PlayerAdapter player) {
    UUID id = player.uniqueId();
    LobbyManager lobbyManager = support.lobbyManager;
    if (lobbyManager != null && lobbyManager.isQueued(id)) {
      String mode = lobbyManager.queuedMode(id);
      return List.of("<yellow>In quick-play queue</yellow>", "<gray>Mode: <white>" + mode + "</white></gray>",
          "<gray>Waiting: <white>" + lobbyManager.queueSize(mode) + "</white></gray>", "<yellow>Click to manage</yellow>");
    }
    if (lobbyManager != null && lobbyManager.inGroup(id)) {
      return List.of("<gray>Your group — invite, queue or host</gray>", "<yellow>Click to open</yellow>");
    }
    return List.of("<gray>Invite friends & play together</gray>", "<yellow>Click to open</yellow>");
  }

  /** Lore for the Friends hub tile, surfacing the pending-invite count. */
  private List<String> mainFriendsLore(PlayerAdapter player) {
    int pending = support.pendingInviteCount(player.uniqueId());
    return pending > 0
        ? List.of("<gray>See who is online</gray>", "<aqua>" + pending + " pending invite(s)</aqua>")
        : List.of("<gray>See who is online</gray>");
  }

  /**
   * Lays out hub tabs as a centered grid: up to 7 tiles per row, each row centered in the 9-column
   * chest, filling from the second row down. This reproduces the familiar centered hub look while
   * scaling automatically as tabs are added or hidden by permission.
   */
  private void layoutCentered(MenuView view, List<MenuTab> tabs, PlayerAdapter viewer) {
    int perRow = Math.min(7, Math.max(1, tabs.size()));
    int index = 0;
    int row = 1;
    while (index < tabs.size()) {
      int countThisRow = Math.min(perRow, tabs.size() - index);
      int startCol = (9 - countThisRow) / 2;
      for (int col = 0; col < countThisRow; col++) {
        int slot = row * 9 + startCol + col;
        if (slot < view.size()) {
          view.set(slot, tabs.get(index).toButton(viewer));
        }
        index++;
      }
      row++;
    }
  }
}
