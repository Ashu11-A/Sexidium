package com.sexidium.core.menu;

import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.npc.NpcManager;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;

import java.util.UUID;

/**
 * Builds and opens the lobby chest-GUI menus. Platform-agnostic: it composes {@link MenuView}s and
 * hands them to {@code serverAdapter.menus()} (the platform {@link com.sexidium.core.platform.MenuAdapter}).
 *
 * <p>The menus cover the whole lobby experience: pick a minigame, build a composable experience
 * experience (multi-select challenges, always survival), manage your own experiences (enter / make
 * public / delete), browse and request to join other players' public experiences, and see friends and
 * party. See {@code docs/ui-and-localization.md} for how to add buttons and icons.</p>
 *
 * <p>This class is the stable public facade. The screens themselves are split, by responsibility,
 * across {@link HubMenu}, {@link MinigameMenu}, {@link ExperienceMenu}, {@link LobbyMenu},
 * {@link SocialMenu} and {@link AdminMenu}, all composing the shared {@link MenuSupport}. Every public
 * method here delegates straight through so adapters, the command service and tests compile unchanged.</p>
 */
public final class MenuService {
  private final MenuSupport support;
  private final HubMenu hub;
  private final MinigameMenu minigames;
  private final ExperienceMenu experiences;
  private final LobbyMenu lobby;
  private final SocialMenu social;
  private final AdminMenu admin;

  public MenuService(ServerAdapter serverAdapter, GameManager gameManager, LobbyManager lobbyManager,
      FriendService friendService, ExperienceService experienceService, NpcManager npcManager) {
    this.support = new MenuSupport(serverAdapter, gameManager, lobbyManager, friendService,
        experienceService, npcManager);
    this.minigames = new MinigameMenu(support, this);
    this.experiences = new ExperienceMenu(support, this);
    this.lobby = new LobbyMenu(support, this);
    this.social = new SocialMenu(support, this);
    this.admin = new AdminMenu(support, this);
    this.hub = new HubMenu(support, this);
  }

  // ----- hub ------------------------------------------------------------------------------------

  public void openMain(PlayerAdapter player) {
    hub.openMain(player);
  }

  // ----- minigames ------------------------------------------------------------------------------

  public void openCategory(PlayerAdapter player, String category, String title) {
    minigames.openCategory(player, category, title);
  }

  public void openModeDetail(PlayerAdapter player, String modeId) {
    minigames.openModeDetail(player, modeId);
  }

  // ----- experiences ----------------------------------------------------------------------------

  public void openExperienceBuilder(PlayerAdapter player) {
    experiences.openExperienceBuilder(player);
  }

  public void openExperiences(PlayerAdapter player) {
    experiences.openExperiences(player);
  }

  /**
   * The single-choice map-type screen (normal / Nether / End / a generated SkyBlock map). Pass
   * {@code experienceId = null} for the create builder, or an id to re-point an existing experience.
   */
  public void openExperienceWorldType(PlayerAdapter player, String experienceId) {
    experiences.openExperienceWorldType(player, experienceId);
  }

  public void openExperienceManage(PlayerAdapter player, String experienceId) {
    experiences.openExperienceManage(player, experienceId);
  }

  public void openExperienceRename(PlayerAdapter player, String experienceId) {
    experiences.openExperienceRename(player, experienceId);
  }

  public void openExperienceEdit(PlayerAdapter player, String experienceId) {
    experiences.openExperienceEdit(player, experienceId);
  }

  /** Every copy taken of one experience, plus the tile that takes another. */
  public void openBackups(PlayerAdapter player, String sourceId) {
    experiences.openBackups(player, sourceId);
  }

  /** One copy: what it is a copy of, when it was taken, and every verb that acts on it. */
  public void openBackup(PlayerAdapter player, String backupId) {
    experiences.openBackup(player, backupId);
  }

  public void openBrowse(PlayerAdapter player) {
    experiences.openBrowse(player);
  }

  /**
   * An experience row changed — here or on another node — so redraw the experience screens players
   * on THIS node are looking at.
   *
   * <p>The registry has always been the source of truth and every screen is built from a fresh query,
   * so a reopened menu was never wrong. An <em>already open</em> one was: it is a photograph of rows
   * taken when it was opened, and the rows it shows mostly belong to other players. Wired to
   * {@code NetworkBus.Topics.EXPERIENCE_UPDATED} in {@code SexidiumCore}, which is why the redraw is
   * not filtered by experience id: a browse list holds dozens of them, and the only cost of redrawing
   * it is one query the viewer's next click would have made anyway.</p>
   */
  public void refreshExperienceScreens() {
    try {
      support.redrawLiveScreens();
    } catch (RuntimeException failed) {
      support.serverAdapter.logger().warning(
          "Could not refresh the open experience screens: " + failed.getMessage());
    }
  }

  // ----- lobby ----------------------------------------------------------------------------------

  public void openLobby(PlayerAdapter player) {
    lobby.openLobby(player);
  }

  public void openInviteFromFriends(PlayerAdapter player) {
    lobby.openInviteFromFriends(player);
  }

  public void openLobbyBrowser(PlayerAdapter player) {
    lobby.openLobbyBrowser(player);
  }

  /** The Friends roster — see where each friend is and join their world or teleport to them. */
  public void openFriendsWarp(PlayerAdapter player) {
    lobby.openFriendsWarp(player);
  }

  /** One joinable-lobby row, shared by the lobby browser and the per-mode minigame detail screen. */
  MenuButton lobbyButton(Lobby value) {
    return lobby.lobbyButton(value);
  }

  // ----- social ---------------------------------------------------------------------------------

  public void openFriends(PlayerAdapter player) {
    social.openFriends(player);
  }

  public void openAddFriend(PlayerAdapter player) {
    social.openAddFriend(player);
  }

  public void openInvites(PlayerAdapter player) {
    social.openInvites(player);
  }

  public void openFriendActions(PlayerAdapter player, UUID friendId, String friendName) {
    social.openFriendActions(player, friendId, friendName);
  }

  // ----- admin ----------------------------------------------------------------------------------

  public void openAdminSettings(PlayerAdapter player) {
    admin.openAdminSettings(player);
  }

  public void openMenuCalibration(PlayerAdapter player) {
    admin.openMenuCalibration(player);
  }

  public void openNpcList(PlayerAdapter player) {
    admin.openNpcList(player);
  }

  public void openNpcEditor(PlayerAdapter player, String npcId) {
    admin.openNpcEditor(player, npcId);
  }

  public void openNpcModePicker(PlayerAdapter player, String npcId) {
    admin.openNpcModePicker(player, npcId);
  }

  public void openNpcSkinPicker(PlayerAdapter player, String npcId) {
    admin.openNpcSkinPicker(player, npcId);
  }

  // ----- teardown -------------------------------------------------------------------------------

  /**
   * Close every open menu, everywhere.
   *
   * <p>There was no teardown at all: {@code MenuService} had no stop and was absent from
   * {@code SexidiumCore.close()}, so an open chest GUI outlived the listener that cancels its clicks
   * — and every button in it became a <b>real item</b> the player could drag into their inventory.
   * Called from two places, for the same reason: the top of a drain's OFFERING step (nobody should be
   * holding a GUI when they are moved) and the core's own close.</p>
   *
   * <p>Never throws. It runs on the way down, where an exception costs the rest of the teardown.</p>
   */
  public void closeAll() {
    try {
      support.serverAdapter.menus().closeAll();
    } catch (RuntimeException failed) {
      support.serverAdapter.logger().warning("Could not close open menus: " + failed.getMessage());
    }
  }
}
