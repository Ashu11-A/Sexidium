package com.sexidium.core.menu;

import com.sexidium.core.command.CoreCommandService;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.world.lobby.LobbyManager;

import java.util.List;
import java.util.UUID;

/**
 * The unified persistent sidebar navigation rail for all Sexidium chest GUIs.
 *
 * <p>Standardizes the 6-slot vertical sidebar (Column 0, slots 0, 9, 18, 27, 36, 45) across every screen.
 * Displays the active section highlighted with distinct visual indicators and provides 1-click fluid
 * navigation to every other primary domain of the network.</p>
 */
public final class SidebarNav {

  public enum NavSection {
    MINIGAMES,
    CREATE_EXPERIENCE,
    MY_EXPERIENCES,
    BROWSE_WORLDS,
    LOBBY_SOCIAL,
    ADMIN_SETTINGS,
    NONE
  }

  private SidebarNav() {
  }

  /**
   * Applies the unified navigation rail to a {@link MenuView}.
   *
   * @param view    the target menu view
   * @param player  the viewer
   * @param menus   the menu service for navigation callbacks
   * @param support the menu support toolkit
   * @param active  the currently active section (or {@link NavSection#NONE})
   */
  public static void apply(MenuView view, PlayerAdapter player, MenuService menus, MenuSupport support, NavSection active) {
    if (view == null || player == null || menus == null) {
      return;
    }

    // 1. Minigames (Slot 0)
    boolean minigamesActive = active == NavSection.MINIGAMES;
    view.set(ChestLayout.sidebarSlot(0), MenuButton.of(
        ItemKey.minecraft("diamond_sword"),
        minigamesActive ? "<aqua><bold>▶ Minigames</bold></aqua>" : "<aqua>Minigames</aqua>",
        minigamesActive
            ? List.of("<green>● Currently viewing</green>", "<gray>Competitive & casual minigames</gray>")
            : List.of("<gray>Play competitive & casual minigames</gray>", "<yellow>Click to switch tab</yellow>"),
        minigamesActive ? null : ctx -> menus.openCategory(ctx.player(), "minigames", "<aqua><bold>Minigames</bold></aqua>")
    ).withModel(MenuArt.model(MenuArt.ICON_MINIGAMES)));

    // 2. Create Experience (Slot 9)
    boolean createActive = active == NavSection.CREATE_EXPERIENCE;
    view.set(ChestLayout.sidebarSlot(1), MenuButton.of(
        ItemKey.minecraft("tnt"),
        createActive ? "<gold><bold>▶ Create Experience</bold></gold>" : "<gold>Create Experience</gold>",
        createActive
            ? List.of("<green>● Currently viewing</green>", "<gray>Custom survival challenge builder</gray>")
            : List.of("<gray>Build a custom survival challenge</gray>", "<yellow>Click to switch tab</yellow>"),
        createActive ? null : ctx -> {
          if (support != null) {
            support.resetBuilder(ctx.player().uniqueId());
          }
          menus.openExperienceBuilder(ctx.player());
        }
    ).withModel(MenuArt.model(MenuArt.ICON_EXPERIENCE_CREATE)));

    // 3. My Experiences (Slot 18)
    boolean myExpActive = active == NavSection.MY_EXPERIENCES;
    view.set(ChestLayout.sidebarSlot(2), MenuButton.of(
        ItemKey.minecraft("ender_chest"),
        myExpActive ? "<yellow><bold>▶ My Experiences</bold></yellow>" : "<yellow>My Experiences</yellow>",
        myExpActive
            ? List.of("<green>● Currently viewing</green>", "<gray>Your created worlds & snapshots</gray>")
            : List.of("<gray>Manage your worlds & backups</gray>", "<yellow>Click to switch tab</yellow>"),
        myExpActive ? null : ctx -> menus.openExperiences(ctx.player())
    ).withModel(MenuArt.model(MenuArt.ICON_EXPERIENCE_MINE)));

    // 4. Browse Worlds (Slot 27)
    boolean browseActive = active == NavSection.BROWSE_WORLDS;
    view.set(ChestLayout.sidebarSlot(3), MenuButton.of(
        ItemKey.minecraft("compass"),
        browseActive ? "<blue><bold>▶ Browse Worlds</bold></blue>" : "<blue>Browse Worlds</blue>",
        browseActive
            ? List.of("<green>● Currently viewing</green>", "<gray>Public & friends' worlds</gray>")
            : List.of("<gray>Explore joinable worlds</gray>", "<yellow>Click to switch tab</yellow>"),
        browseActive ? null : ctx -> menus.openBrowse(ctx.player())
    ).withModel(MenuArt.model(MenuArt.ICON_BROWSE)));

    // 5. Lobby / Social (Slot 36)
    boolean lobbyActive = active == NavSection.LOBBY_SOCIAL;
    List<String> lobbyLore = lobbyActive
        ? List.of("<green>● Currently viewing</green>", "<gray>Party, match queue & friends</gray>")
        : dynamicLobbyLore(player, support);
    view.set(ChestLayout.sidebarSlot(4), MenuButton.of(
        ItemKey.minecraft("cake"),
        lobbyActive ? "<light_purple><bold>▶ Lobby & Friends</bold></light_purple>" : "<light_purple>Lobby & Friends</light_purple>",
        lobbyLore,
        lobbyActive ? null : ctx -> menus.openLobby(ctx.player())
    ).withModel(MenuArt.model(MenuArt.ICON_LOBBY)));

    // 6. Slot 45: Admin Settings (for ops) or Hub / Home Shortcut (for all players)
    if (player.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      boolean adminActive = active == NavSection.ADMIN_SETTINGS;
      view.set(ChestLayout.sidebarSlot(5), MenuButton.of(
          ItemKey.minecraft("command_block"),
          adminActive ? "<red><bold>▶ Admin Settings</bold></red>" : "<red>Admin Settings</red>",
          adminActive
              ? List.of("<green>● Currently viewing</green>", "<gray>Operator configuration</gray>")
              : List.of("<gray>Server & NPC configuration</gray>", "<dark_gray>Operators only</dark_gray>", "<yellow>Click to switch tab</yellow>"),
          adminActive ? null : ctx -> menus.openAdminSettings(ctx.player())
      ).withModel(MenuArt.model(MenuArt.ICON_ADMIN)));
    } else {
      view.set(ChestLayout.sidebarSlot(5), MenuButton.of(
          ItemKey.minecraft("barrier"),
          "<red><bold>✕ Close</bold></red>",
          List.of("<gray>Close the menu</gray>"),
          ctx -> {
            if (support != null && support.serverAdapter != null) {
              support.serverAdapter.menus().close(ctx.player());
            }
          }
      ).withModel(MenuArt.model(MenuArt.ICON_LEAVE)));
    }
  }

  private static List<String> dynamicLobbyLore(PlayerAdapter player, MenuSupport support) {
    if (support == null) {
      return List.of("<gray>Manage group, match queue & friends</gray>", "<yellow>Click to switch tab</yellow>");
    }
    UUID id = player.uniqueId();
    LobbyManager lobbyManager = support.lobbyManager;
    if (lobbyManager != null && lobbyManager.isQueued(id)) {
      String mode = lobbyManager.queuedMode(id);
      return List.of("<yellow>In queue: " + mode + "</yellow>", "<yellow>Click to open</yellow>");
    }
    if (lobbyManager != null && lobbyManager.inGroup(id)) {
      return List.of("<gray>Your group is active</gray>", "<yellow>Click to open</yellow>");
    }
    int pending = support.pendingInviteCount(id);
    if (pending > 0) {
      return List.of("<aqua>" + pending + " pending invite(s)</aqua>", "<yellow>Click to open</yellow>");
    }
    return List.of("<gray>Party, match queue & friends</gray>", "<yellow>Click to switch tab</yellow>");
  }
}
