package com.sexidium.core.menu;

import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.lobby.LobbyResult;
import com.sexidium.core.world.lobby.LobbyEnums.LobbyVisibility;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The unified lobby home and its sub-screens: the social roster (invite / kick / play-together / queue
 * status), the host team-select / start screen for a CONFIGURED lobby, the reusable invite picker, and
 * the joinable-lobby browser.
 */
final class LobbyMenu {
  private final MenuSupport support;
  private final MenuService menus;

  LobbyMenu(MenuSupport support, MenuService menus) {
    this.support = support;
    this.menus = menus;
  }

  /**
   * The unified lobby home. State-aware: a CONFIGURED lobby shows the host team-select / start screen;
   * an IDLE/QUEUED/solo lobby shows the social roster with invite, play-together and queue controls.
   */
  void openLobby(PlayerAdapter player) {
    LobbyManager lobbyManager = support.lobbyManager;
    Lobby lobby = lobbyManager == null ? null : lobbyManager.lobbyOf(player.uniqueId());
    if (lobby != null && lobby.isConfigured()) {
      openConfiguredLobby(player, lobby);
      return;
    }
    openSocialLobby(player, lobby);
  }

  /**
   * IDLE / QUEUED / solo lobby: the social roster (invite, kick, play-together, queue status), replacing
   * the old separate Party screen. Works whether or not the player already has a lobby allocated.
   */
  private void openSocialLobby(PlayerAdapter player, Lobby lobby) {
    LobbyManager lobbyManager = support.lobbyManager;
    ServerAdapter serverAdapter = support.serverAdapter;
    UUID id = player.uniqueId();
    boolean inGroup = lobby != null && lobby.size() > 1;
    boolean leader = lobby == null || lobby.isLeader(id);
    boolean queued = lobby != null && lobby.isQueued();
    String title = queued
        ? "<aqua><bold>Lobby</bold></aqua> <gray>· in queue</gray>"
        : "<light_purple><bold>Lobby & Party</bold></light_purple>";

    MenuView view = new MenuView(title, ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_LOBBY);

    fillSeparator(view);

    // Sidebar: Unified global navigation rail with LOBBY_SOCIAL marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.LOBBY_SOCIAL);

    // Content area: group roster (up to 35 slots)
    List<UUID> members = lobby == null ? List.of(id) : lobby.members();
    for (int i = 0; i < members.size() && i < ChestLayout.CONTENT_CAPACITY; i++) {
      UUID memberId = members.get(i);
      boolean memberIsLeader = lobby == null ? memberId.equals(id) : lobby.isLeader(memberId);
      boolean self = memberId.equals(id);
      boolean kickable = leader && inGroup && !self;
      String memberName = support.name(memberId);
      boolean armed = kickable && support.isArmed(id, "kick:" + memberId);
      String memberTitle = "<white><bold>" + MenuSupport.escape(memberName) + "</bold></white>" + (memberIsLeader ? " <gold>(leader)</gold>" : "");
      List<String> memberLore = List.of(kickable
          ? (armed ? "<red>⚠ Tap again to kick</red>" : "<yellow>Tap to kick (confirms)</yellow>")
          : (self ? "<green>You (Host)</green>" : "<gray>Party Member</gray>"));
      Consumer<MenuContext> onMember = ctx -> {
        if (!kickable) {
          ctx.player().sendActionBar(self ? "<gray>That's you.</gray>" : "<gray>Only the leader can kick members.</gray>");
          return;
        }
        if (support.confirmStep(ctx, "kick:" + memberId)) {
          support.clearConfirm(ctx.player().uniqueId());
          lobbyManager.kick(ctx.player().uniqueId(), memberId);
          serverAdapter.player(memberId).filter(PlayerAdapter::online)
              .ifPresent(kicked -> kicked.sendMiniMessage("<red>You were removed from the lobby.</red>"));
          ctx.player().sendActionBar("<yellow>Removed " + MenuSupport.escape(memberName) + " from the lobby.</yellow>");
        } else if (MenuSupport.isTap(ctx)) {
          ctx.player().sendActionBar("<red>Tap again to kick " + MenuSupport.escape(memberName) + ".</red>");
        } else {
          return;
        }
        menus.openLobby(ctx.player());
      };
      MenuButton button = memberIsLeader && self
          ? MenuButton.of(ItemKey.minecraft("golden_helmet"), memberTitle, memberLore, onMember)
          : MenuButton.head(memberId, memberTitle, memberLore, onMember);
      view.set(ChestLayout.contentSlot(i), button);
    }

    if (!inGroup) {
      view.set(ChestLayout.contentSlot(17), MenuButton.label(ItemKey.minecraft("paper"),
          "<gray><bold>Just you in the party</bold></gray>",
          List.of("<gray>Click <green>'Invite Players'</green> below to invite friends!</gray>")));
    }

    // Bottom Navigation:
    view.set(ChestLayout.SLOT_BACK, support.backButton(() -> menus.openMain(player)));

    // Slot 51: Invite players shortcut
    view.set(ChestLayout.SLOT_CHAOS, MenuButton.of(ItemKey.minecraft("lime_dye"), "<green><bold>Invite Players</bold></green>",
        List.of(inGroup && !leader ? "<red>Only the leader can invite</red>" : "<gray>Pick a friend or online player</gray>", "<yellow>Click to open invite picker</yellow>"),
        ctx -> {
          if (inGroup && !leader) {
            ctx.player().sendActionBar("<red>Only the lobby leader can invite.</red>");
          } else {
            menus.openInviteFromFriends(ctx.player());
          }
        }).withModel(MenuArt.model(MenuArt.ICON_INVITE)));

    // Slot 53: Primary Action (Play Together or Leave Group)
    if (inGroup) {
      view.set(ChestLayout.SLOT_PRIMARY, support.confirmButton(player, ItemKey.minecraft("barrier"),
          MenuArt.model(MenuArt.ICON_LEAVE), "lobby-leave",
          leader ? "<red><bold>Disband Lobby</bold></red>" : "<red><bold>Leave Lobby</bold></red>",
          List.of("<gray>" + (leader ? "Disbands the lobby for everyone" : "Leaves your current group") + "</gray>",
              "<yellow>Tap, then tap again to confirm</yellow>"),
          "<red><bold>⚠ Tap again to confirm</bold></red>",
          List.of("<yellow>Tap once more to leave</yellow>"),
          ctx -> {
            support.clearConfirm(ctx.player().uniqueId());
            if (lobbyManager != null) {
              lobbyManager.leave(ctx.player());
            }
            ctx.player().sendActionBar("<yellow>Left the lobby.</yellow>");
            menus.openLobby(ctx.player());
          },
          viewer -> menus.openLobby(viewer)));
    } else {
      view.set(ChestLayout.SLOT_PRIMARY, MenuButton.of(ItemKey.minecraft("diamond_sword"),
          "<gold><bold>▶ Play Minigames</bold></gold>",
          List.of("<gray>Jump straight into quick-match games</gray>", "<yellow>Click to explore modes</yellow>"),
          ctx -> menus.openCategory(ctx.player(), "minigames", "<aqua><bold>Minigames</bold></aqua>"))
          .withModel(MenuArt.model(MenuArt.ICON_PLAY_TOGETHER)));
    }

    support.open(player, view);
    support.trackLive(player, menus::openLobby);
  }

  /**
   * The host team-select / start screen for a CONFIGURED lobby.
   */
  private void openConfiguredLobby(PlayerAdapter player, Lobby lobby) {
    LobbyManager lobbyManager = support.lobbyManager;
    UUID id = player.uniqueId();
    boolean isHost = lobby.isHost(id);
    String modeId = lobby.modeId();
    GameModeDescriptor descriptor = support.descriptorOf(modeId);
    String modeName = descriptor != null ? descriptor.displayName() : modeId;

    MenuView view = new MenuView("<aqua><bold>Match Lobby</bold></aqua> <gray>· " + MenuSupport.escape(modeName) + "</gray>",
        ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_LOBBY);

    fillSeparator(view);

    // Sidebar (Col 0): Host Controls & Information
    view.set(ChestLayout.sidebarSlot(0), MenuButton.label(support.icon(modeId),
        "<white><bold>" + MenuSupport.escape(modeName) + "</bold></white>",
        List.of("<gray>Configured Match Lobby</gray>")));

    if (isHost) {
      int teamCount = lobby.teamCount();
      view.set(ChestLayout.sidebarSlot(1), MenuButton.of(ItemKey.minecraft("white_wool"),
          "<white>Teams: <gold><bold>" + (lobby.teamsEnabled() ? teamCount + " teams" : "FFA (no teams)") + "</bold></gold></white>",
          List.of("<gray>Click to cycle team count</gray>"),
          ctx -> {
            int next = !lobby.teamsEnabled() ? 2 : (teamCount >= 4 ? 0 : teamCount + 1);
            lobby.setTeamCount(next);
            menus.openLobby(ctx.player());
          }).withModel(MenuArt.model(MenuArt.ICON_TEAMS)));

      view.set(ChestLayout.sidebarSlot(2), MenuButton.of(ItemKey.minecraft("oak_sign"),
          "<white>Lobby: <gold><bold>" + (lobby.isOpen() ? "Public" : "Invite-only") + "</bold></gold></white>",
          List.of("<gray>Click to toggle visibility</gray>"),
          ctx -> {
            lobby.setVisibility(lobby.isOpen() ? LobbyVisibility.INVITE_ONLY : LobbyVisibility.PUBLIC);
            menus.openLobby(ctx.player());
          }).withModel(MenuArt.model(lobby.isOpen() ? MenuArt.ICON_PUBLIC : MenuArt.ICON_INVITE)));
    } else {
      view.set(ChestLayout.sidebarSlot(1), MenuButton.label(ItemKey.minecraft("white_wool"),
          "<white>Teams: <gold><bold>" + (lobby.teamsEnabled() ? lobby.teamCount() + " teams" : "FFA") + "</bold></gold></white>",
          List.of("<gray>The host controls teams</gray>")));
    }

    // Content area: Team Wool selectors on Row 0 (Content Slots 1..5)
    if (lobby.teamsEnabled()) {
      int teamCount = lobby.teamCount();
      int[] teamSlots = teamSlots(teamCount);
      for (int i = 0; i < teamCount && i < teamSlots.length; i++) {
        TeamColor color = TeamColor.values()[i % TeamColor.values().length];
        ItemKey wool = ItemKey.minecraft(color.name().toLowerCase(java.util.Locale.ROOT) + "_wool");
        Integer myTeam = lobby.selectedTeam(id);
        boolean isMyTeam = myTeam != null && myTeam == i;
        int teamIndex = i;
        int slot = ChestLayout.contentSlot(teamSlots[i]);
        view.set(slot, MenuButton.of(wool,
            "<" + color.name().toLowerCase(java.util.Locale.ROOT) + "><bold>" + color.displayName() + " Team"
                + (isMyTeam ? " ✔" : "") + "</bold></" + color.name().toLowerCase(java.util.Locale.ROOT) + ">",
            List.of("<gray>Click to pick this team</gray>"),
            ctx -> {
              lobby.selectTeam(ctx.player().uniqueId(), teamIndex);
              menus.openLobby(ctx.player());
            }));
      }
    }

    // Content: Roster member heads (Rows 2–4: Content Slots 14..34)
    List<UUID> members = lobby.members();
    for (int i = 0; i < members.size() && i < 21; i++) {
      UUID memberId = members.get(i);
      Integer teamIdx = lobby.selectedTeam(memberId);
      TeamColor color = teamIdx != null && teamIdx >= 0 && teamIdx < TeamColor.values().length
          ? TeamColor.values()[teamIdx] : null;
      String colorTag = color != null ? color.name().toLowerCase(java.util.Locale.ROOT) : "white";
      String memberTitle = "<" + colorTag + ">" + MenuSupport.escape(support.name(memberId)) + "</" + colorTag + ">";
      view.set(ChestLayout.contentSlot(14 + i), MenuButton.head(memberId, memberTitle, List.of(), null));
    }

    // Bottom Navigation
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      if (lobbyManager != null) {
        lobbyManager.leave(ctx.player());
      }
      menus.openCategory(ctx.player(), "minigames", "<aqua><bold>Minigames</bold></aqua>");
    }));

    view.set(ChestLayout.SLOT_CHAOS, MenuButton.of(ItemKey.minecraft("lime_dye"), "<green><bold>Invite Players</bold></green>",
        List.of("<gray>Invite online players to match</gray>"),
        ctx -> menus.openInviteFromFriends(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_INVITE)));

    if (isHost) {
      view.set(ChestLayout.SLOT_PRIMARY, MenuButton.of(ItemKey.minecraft("lime_concrete"),
          "<green><bold>▶ Start Match</bold></green>",
          List.of("<gray>Players: <white>" + lobby.size() + "</white></gray>", "<yellow>Click to begin match</yellow>"),
          ctx -> {
            if (lobbyManager != null) {
              LobbyResult result = lobbyManager.start(ctx.player());
              if (result != LobbyResult.STARTED) {
                ctx.player().sendActionBar("<red>Cannot start match yet.</red>");
              }
            }
          }).withModel(MenuArt.model(MenuArt.ICON_START)));
    }

    support.open(player, view);
    support.trackLive(player, menus::openLobby);
  }

  MenuButton lobbyButton(Lobby lobby) {
    if (lobby == null) {
      return null;
    }
    String hostName = support.name(lobby.host());
    String modeId = lobby.modeId() == null ? "group" : lobby.modeId();
    GameModeDescriptor descriptor = support.descriptorOf(modeId);
    String modeName = descriptor != null ? descriptor.displayName() : modeId;
    ItemKey icon = descriptor != null ? support.icon(modeId) : ItemKey.minecraft("cake");
    int size = lobby.size();
    int cap = lobby.capacity();
    String count = cap == Integer.MAX_VALUE ? size + " players" : size + "/" + cap + " players";
    return MenuButton.of(icon, "<gold><bold>" + MenuSupport.escape(hostName) + "'s Lobby</bold></gold>",
        List.of("<gray>Mode: <white>" + MenuSupport.escape(modeName) + "</white></gray>",
            "<gray>Players: <white>" + count + "</white></gray>",
            "<yellow>Click to join lobby</yellow>"),
        ctx -> {
          LobbyManager lobbyManager = support.lobbyManager;
          if (lobbyManager != null) {
            LobbyResult result = lobbyManager.join(ctx.player(), lobby.id());
            ctx.player().sendActionBar(support.lobbyAcceptMessage(result, hostName));
            if (result == LobbyResult.JOINED) {
              menus.openLobby(ctx.player());
            }
          }
        }).withModel(MenuArt.model(MenuArt.ICON_JOIN));
  }

  void openInviteFromFriends(PlayerAdapter player) {
    openInviteFromFriends(player, 0);
  }

  void openInviteFromFriends(PlayerAdapter player, int page) {
    LobbyManager lobbyManager = support.lobbyManager;
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;
    UUID id = player.uniqueId();

    Set<UUID> friendIds = new HashSet<>();
    if (friendService != null) {
      for (FriendService.Entry friend : friendService.friends(id)) {
        friendIds.add(friend.playerId());
      }
    }

    List<PlayerAdapter> candidates = new ArrayList<>();
    for (PlayerAdapter online : serverAdapter.onlinePlayers()) {
      if (!online.uniqueId().equals(id)) {
        if (friendIds.contains(online.uniqueId())) {
          candidates.add(0, online);
        } else {
          candidates.add(online);
        }
      }
    }

    MenuView view = PaginatedScreen.<PlayerAdapter>of("<green><bold>Invite to Lobby</bold></green>")
        .background(MenuArt.BG_LOBBY)
        .items(candidates)
        .page(page)
        .emptyIndicator(MenuButton.label(ItemKey.minecraft("paper"), "<gray><bold>No players online to invite</bold></gray>", List.of()))
        .itemMapper(target -> {
          boolean friend = friendIds.contains(target.uniqueId());
          return MenuButton.head(target.uniqueId(),
              (friend ? "<green>★ " : "<white>") + MenuSupport.escape(target.name()) + (friend ? "</green>" : "</white>"),
              List.of(friend ? "<green>Friend</green>" : "<gray>Online player</gray>", "<yellow>Click to invite to lobby</yellow>"),
              ctx -> {
                if (lobbyManager == null) return;
                LobbyResult result = lobbyManager.invite(ctx.player(), target);
                ctx.player().sendActionBar(support.lobbyInviteMessage(result, target.name()));
                if (result == LobbyResult.INVITE_SENT) {
                  target.sendMiniMessage("<aqua>" + MenuSupport.escape(ctx.player().name())
                      + "</aqua> <gray>invited you to their lobby — open the menu ▶ Friends ▶ Invites.</gray>");
                }
                menus.openLobby(ctx.player());
              });
        })
        .onPageChange(p -> openInviteFromFriends(player, p))
        .back(support.back(ctx -> menus.openLobby(ctx.player())))
        .build();

    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.LOBBY_SOCIAL);
    support.open(player, view);
  }

  void openFriendsWarp(PlayerAdapter player) {
    openFriendsWarp(player, 0);
  }

  void openFriendsWarp(PlayerAdapter player, int page) {
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;
    GameManager gameManager = support.gameManager;
    ExperienceService experienceService = support.experienceService;

    List<FriendService.Entry> friends = friendService != null ? friendService.friends(player.uniqueId()) : List.of();
    List<PlayerAdapter> onlineFriends = new ArrayList<>();
    for (FriendService.Entry friend : friends) {
      serverAdapter.player(friend.playerId()).filter(PlayerAdapter::online).ifPresent(onlineFriends::add);
    }

    MenuView view = PaginatedScreen.<PlayerAdapter>of("<green><bold>Friends Warp</bold></green>")
        .background(MenuArt.BG_FRIENDS)
        .items(onlineFriends)
        .page(page)
        .emptyIndicator(MenuButton.label(ItemKey.minecraft("paper"), "<gray><bold>No friends online right now</bold></gray>", List.of()))
        .itemMapper(target -> {
          ActiveMatch match = gameManager.matchOf(target);
          ExperienceManager.Experience experience = match != null && experienceService != null
              ? experienceService.registry().byWorld(
                  com.sexidium.core.world.WorldKey.fromRuntime(match.worldName()).orElse(null)) : null;
          String location = experience != null
              ? "In: " + experience.displayName()
              : (match != null ? "Playing: " + match.modeId() : "In the lobby");
          return MenuButton.head(target.uniqueId(),
              "<green><bold>" + MenuSupport.escape(target.name()) + "</bold></green>",
              List.of("<gray>" + MenuSupport.escape(location) + "</gray>", "<yellow>Click to warp / join world</yellow>"),
              ctx -> {
                serverAdapter.menus().close(ctx.player());
                if (experience != null) {
                  support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), experience.id()));
                } else if (match == null && target.position() != null) {
                  ctx.player().teleport(target.position());
                  ctx.player().sendActionBar("<green>Teleported to " + MenuSupport.escape(target.name()) + ".</green>");
                }
              });
        })
        .onPageChange(p -> openFriendsWarp(player, p))
        .back(support.backButton(() -> menus.openMain(player)))
        .build();

    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.LOBBY_SOCIAL);
    support.open(player, view);
  }

  void openLobbyBrowser(PlayerAdapter player) {
    openLobbyBrowser(player, 0);
  }

  void openLobbyBrowser(PlayerAdapter player, int page) {
    LobbyManager lobbyManager = support.lobbyManager;
    List<Lobby> openLobbies = new ArrayList<>();
    if (lobbyManager != null) {
      for (Lobby lobby : lobbyManager.joinableFor(player.uniqueId(), null)) {
        openLobbies.add(lobby);
      }
    }

    MenuView view = PaginatedScreen.<Lobby>of("<aqua><bold>Match Lobbies</bold></aqua>")
        .background(MenuArt.BG_LOBBY)
        .items(openLobbies)
        .page(page)
        .emptyIndicator(MenuButton.label(ItemKey.minecraft("paper"), "<gray><bold>No active public lobbies</bold></gray>",
            List.of("<gray>Host your own match lobby in Minigames!</gray>")))
        .itemMapper(this::lobbyButton)
        .onPageChange(p -> openLobbyBrowser(player, p))
        .back(support.backButton(() -> menus.openMain(player)))
        .primaryAction(MenuButton.of(ItemKey.minecraft("compass"), "<aqua><bold>⟳ Refresh</bold></aqua>",
            List.of("<gray>Scan for open lobbies</gray>"),
            ctx -> openLobbyBrowser(ctx.player(), page)))
        .build();

    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.MINIGAMES);
    support.open(player, view);
    support.trackLive(player, p -> openLobbyBrowser(p, page));
  }

  private static int[] teamSlots(int teamCount) {
    if (teamCount <= 2) return new int[] {1, 5};
    if (teamCount == 3) return new int[] {1, 3, 5};
    return new int[] {0, 2, 4, 6};
  }

  private void fillSeparator(MenuView view) {
    ChestLayout.fillSeparator(view);
  }
}
