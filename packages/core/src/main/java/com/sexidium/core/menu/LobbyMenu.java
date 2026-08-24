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
    boolean leader = lobby == null || lobby.isLeader(id); // solo players lead themselves
    boolean queued = lobby != null && lobby.isQueued();
    MenuView view = new MenuView(queued
        ? "<aqua><bold>Lobby</bold></aqua> <gray>· in queue</gray>"
        : "<light_purple><bold>Lobby</bold></light_purple>", 4).background(MenuArt.BG_LOBBY);

    view.set(2, MenuButton.of(ItemKey.minecraft("lime_dye"), "<green><bold>Invite Players</bold></green>",
        List.of(inGroup && !leader ? "<red>Only the leader can invite</red>" : "<gray>Pick a friend or online player</gray>"),
        ctx -> {
          if (inGroup && !leader) {
            ctx.player().sendActionBar("<red>Only the lobby leader can invite.</red>");
          } else {
            menus.openInviteFromFriends(ctx.player());
          }
        }).withModel(MenuArt.model(MenuArt.ICON_INVITE)));
    int pending = support.pendingInviteCount(id);
    view.set(4, MenuButton.of(ItemKey.minecraft("writable_book"), "<aqua><bold>Invites</bold></aqua>",
        List.of("<gray>Pending: <white>" + pending + "</white></gray>", "<yellow>Click to review</yellow>"),
        ctx -> menus.openInvites(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_INVITES)));
    if (leader) {
      view.set(6, MenuButton.of(ItemKey.minecraft("diamond_sword"), "<gold><bold>Play Together</bold></gold>",
          List.of("<gray>Quick-play or host a minigame for your group</gray>"),
          ctx -> menus.openCategory(ctx.player(), "minigames", "<gold><bold>Play Together</bold></gold>"))
          .withModel(MenuArt.model(MenuArt.ICON_PLAY_TOGETHER)));
    }

    int slot = 9;
    List<UUID> members = lobby == null ? List.of(id) : lobby.members();
    for (UUID memberId : members) {
      if (slot >= 27) {
        break;
      }
      boolean memberIsLeader = lobby == null ? memberId.equals(id) : lobby.isLeader(memberId);
      boolean self = memberId.equals(id);
      boolean kickable = leader && inGroup && !self;
      String memberName = support.name(memberId);
      boolean armed = kickable && support.isArmed(id, "kick:" + memberId);
      String memberTitle = "<white>" + MenuSupport.escape(memberName) + "</white>" + (memberIsLeader ? " <gold>(leader)</gold>" : "");
      List<String> memberLore = List.of(kickable
          ? (armed ? "<red>⚠ Tap again to kick</red>" : "<yellow>Tap to kick (confirms)</yellow>")
          : "<gray>Member</gray>");
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
          // confirmStep ignores anything that is not a tap, so a number key or a drop that landed on
          // this head armed nothing: promising that one more tap kicks would be a lie, and there is no
          // changed state to redraw the screen for.
          return;
        }
        menus.openLobby(ctx.player());
      };
      view.set(slot++, memberIsLeader && self
          ? MenuButton.of(ItemKey.minecraft("golden_helmet"), memberTitle, memberLore, onMember)
          : MenuButton.head(memberId, memberTitle, memberLore, onMember));
    }
    if (!inGroup) {
      view.set(31, MenuButton.label(ItemKey.minecraft("paper"), "<gray>Just you for now</gray>",
          List.of("<gray>Invite friends, or Play Together to quick-play</gray>")));
    }

    if (queued && lobby != null) {
      String mode = lobby.modeId();
      view.set(33, MenuButton.of(ItemKey.minecraft("clock"), "<yellow><bold>In quick-play queue</bold></yellow>",
          List.of("<gray>Mode: <white>" + mode + "</white></gray>",
              "<gray>Waiting: <white>" + lobbyManager.queueSize(mode) + "</white></gray>",
              leader ? "<red>Click to cancel</red>" : "<gray>The leader controls the queue</gray>"),
          ctx -> {
            if (lobby.isLeader(ctx.player().uniqueId())) {
              lobbyManager.dequeue(ctx.player());
              ctx.player().sendActionBar("<yellow>Left the quick-play queue.</yellow>");
            }
            menus.openLobby(ctx.player());
          }));
    }

    if (inGroup) {
      view.set(view.size() - 1, support.confirmButton(player, ItemKey.minecraft("barrier"), MenuArt.model(MenuArt.ICON_LEAVE), "lobby-leave",
          leader ? "<red><bold>Disband Lobby</bold></red>" : "<red><bold>Leave Lobby</bold></red>",
          List.of("<gray>Tap, then tap again to confirm</gray>"),
          leader ? "<red><bold>⚠ Tap again to disband</bold></red>" : "<red><bold>⚠ Tap again to leave</bold></red>",
          List.of("<yellow>Tap once more to confirm</yellow>"),
          ctx -> {
            boolean wasLeader = lobbyManager.isLeader(ctx.player().uniqueId());
            if (wasLeader) {
              lobbyManager.disband(ctx.player().uniqueId());
            } else {
              lobbyManager.leave(ctx.player());
            }
            ctx.player().sendActionBar(wasLeader ? "<yellow>Lobby disbanded.</yellow>" : "<yellow>You left the lobby.</yellow>");
            menus.openLobby(ctx.player());
          },
          menus::openLobby));
    }
    view.set(view.size() - 9, support.back(ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          menus.openMain(ctx.player());
        }));
    support.open(player, view);
  }

  /**
   * The host CONFIGURED-lobby team-select screen. Shows the (auto-balanced) teams with their colour and a
   * live player count + member list, lets the host cycle the team size, visibility and start, and anyone leave.
   */
  private void openConfiguredLobby(PlayerAdapter player, Lobby lobby) {
    LobbyManager lobbyManager = support.lobbyManager;
    ServerAdapter serverAdapter = support.serverAdapter;
    boolean host = lobby.isHost(player.uniqueId());
    MenuView view = new MenuView("<aqua><bold>Match Lobby</bold></aqua> <gray>" + lobby.modeId() + "</gray>", 4)
        .background(MenuArt.BG_LOBBY);
    List<UUID> members = new ArrayList<>(lobby.members());
    if (!lobby.teamsEnabled()) {
      view.set(4, MenuButton.label(ItemKey.minecraft("iron_sword"), "<white><bold>Free for all</bold></white>",
          List.of("<gray>" + members.size() + " player(s)</gray>")));
      int slot = 9;
      for (UUID memberId : members) {
        if (slot >= 27) {
          break;
        }
        view.set(slot++, MenuButton.headLabel(memberId,
            "<white>" + MenuSupport.escape(support.name(memberId)) + "</white>", List.of()));
      }
    } else {
      TeamColor[] palette = TeamColor.values();
      int[] slots = teamSlots(lobby.teamCount());
      for (int teamIndex = 0; teamIndex < lobby.teamCount() && teamIndex < palette.length; teamIndex++) {
        TeamColor color = palette[teamIndex];
        int selectedSize = lobby.selectedTeamSize(teamIndex);
        Integer viewerTeam = lobby.selectedTeam(player.uniqueId());
        boolean selectedByViewer = viewerTeam != null && viewerTeam == teamIndex;
        boolean full = selectedSize >= lobby.teamSize();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + selectedSize + "/" + lobby.teamSize() + " selected</gray>");
        lore.add(selectedByViewer
            ? "<yellow>Click to leave this team</yellow>"
            : full ? "<red>No open slots</red>" : "<yellow>Click to join this team</yellow>");
        boolean anyMember = false;
        for (UUID memberId : members) {
          Integer memberTeam = lobby.selectedTeam(memberId);
          if (memberTeam != null && memberTeam == teamIndex) {
            lore.add("<gray>• </gray><white>" + MenuSupport.escape(support.name(memberId)) + "</white>");
            anyMember = true;
          }
        }
        if (!anyMember) {
          lore.add("<dark_gray>No players selected</dark_gray>");
        }
        int clickedTeam = teamIndex;
        TeamColor clickedColor = color;
        view.set(slots[teamIndex], new MenuButton(ItemKey.minecraft(color.woolItem()), Math.max(1, selectedSize),
            (selectedByViewer ? "<green><bold>✔ </bold></green>" : "")
                + color.colorize("<bold>" + color.displayName() + "</bold>")
                + " <gray>(" + selectedSize + "/" + lobby.teamSize() + ")</gray>", lore,
            ctx -> {
              LobbyResult result = lobbyManager.chooseTeam(ctx.player(), clickedTeam);
              switch (result) {
                case TEAM_SELECTED -> ctx.player().sendActionBar("<green>Joined "
                    + clickedColor.colorize(clickedColor.displayName()) + ".</green>");
                case TEAM_LEFT -> ctx.player().sendActionBar("<yellow>Left "
                    + clickedColor.colorize(clickedColor.displayName()) + ".</yellow>");
                case FULL -> ctx.player().sendActionBar("<red>That team is full.</red>");
                default -> ctx.player().sendActionBar("<red>Could not choose that team.</red>");
              }
              menus.openLobby(ctx.player());
            }, null, null));
      }
      int slot = 9;
      for (UUID memberId : members) {
        if (lobby.selectedTeam(memberId) != null) {
          continue;
        }
        if (slot >= 27) {
          break;
        }
        view.set(slot++, MenuButton.headLabel(memberId,
            "<white>" + MenuSupport.escape(support.name(memberId)) + "</white>",
            List.of("<gray>Not on a team yet</gray>", "<yellow>Choose a wool block above</yellow>")));
      }
      if (slot == 9) {
        view.set(22, MenuButton.label(ItemKey.minecraft("lime_dye"), "<green><bold>All players selected</bold></green>",
            List.of("<gray>Click your wool block again to leave.</gray>")));
      }
    }
    if (host) {
      view.set(27, MenuButton.of(ItemKey.minecraft("comparator"),
          "<yellow><bold>Teams: " + (lobby.teamsEnabled() ? lobby.teamCount() : "FFA") + "</bold></yellow>",
          List.of("<gray>Click to cycle FFA → 2 → 3 → 4</gray>"),
          ctx -> {
            int next = !lobby.teamsEnabled() ? 2 : (lobby.teamCount() >= Lobby.MAX_TEAMS ? 0 : lobby.teamCount() + 1);
            lobbyManager.setTeamCount(ctx.player(), next);
            menus.openLobby(ctx.player());
          }).withModel(MenuArt.model(MenuArt.ICON_TEAMS)));
      if (lobby.teamsEnabled()) {
        view.set(28, MenuButton.of(ItemKey.minecraft("comparator"),
            "<yellow><bold>Players per team: " + lobby.teamSize() + "</bold></yellow>",
            List.of("<gray>Click to cycle 1 → 8</gray>",
                "<gray>Capacity: " + (lobby.teamCount() * lobby.teamSize()) + "</gray>"),
            ctx -> {
              int next = lobby.teamSize() >= 8 ? 1 : lobby.teamSize() + 1;
              lobbyManager.setTeamSize(ctx.player(), next);
              menus.openLobby(ctx.player());
            }).withModel(MenuArt.model(MenuArt.ICON_TEAMS)));
      }
      view.set(29, MenuButton.of(MenuSupport.visibilityIcon(lobby.visibility()), MenuSupport.visibilityTitle(lobby.visibility()),
          List.of(MenuSupport.visibilityHint(lobby.visibility()), "<yellow>Click to cycle</yellow>"),
          ctx -> {
            lobbyManager.setVisibility(ctx.player(), MenuSupport.nextVisibility(lobby.visibility()));
            menus.openLobby(ctx.player());
          }).withModel(MenuArt.model(MenuArt.ICON_VISIBILITY)));
      view.set(30, MenuButton.of(ItemKey.minecraft("emerald"),
          "<yellow><bold>Min players: " + lobby.minPlayers() + "</bold></yellow>",
          List.of("<gray>Click to cycle 2 → 3 → 4</gray>"),
          ctx -> {
            // Floor at 2: a minigame match always needs an opponent, so the host can never drop below it.
            int next = lobby.minPlayers() >= 4 ? 2 : Math.max(2, lobby.minPlayers() + 1);
            lobbyManager.setMinPlayers(ctx.player(), next);
            menus.openLobby(ctx.player());
          }).withModel(MenuArt.model(MenuArt.ICON_MIN_PLAYERS)));
      view.set(31, MenuButton.of(ItemKey.minecraft("player_head"), "<aqua><bold>Invite players</bold></aqua>",
          List.of("<gray>Invite players from the lobby</gray>"),
          ctx -> menus.openInviteFromFriends(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_INVITE)));
      List<String> startLore = new ArrayList<>();
      int requiredPlayers = lobby.requiredPlayersForStart();
      startLore.add("<gray>" + members.size() + (lobby.teamsEnabled() ? "/" + lobby.capacity() : "") + " player(s)</gray>");
      if (members.size() < requiredPlayers) {
        startLore.add("<red>Need " + requiredPlayers + " players</red>");
      } else if (lobby.teamsEnabled() && members.size() > lobby.capacity()) {
        startLore.add("<red>Too many players for the team slots</red>");
      } else {
        if (lobby.teamsEnabled() && !lobby.unselectedMembers().isEmpty()) {
          startLore.add("<yellow>Unpicked players will be assigned randomly</yellow>");
        }
        startLore.add("<yellow>Click to begin</yellow>");
      }
      view.set(33, MenuButton.of(ItemKey.minecraft("lime_concrete"),
          "<green><bold>Start match</bold></green>", startLore,
          ctx -> {
            LobbyResult result = lobbyManager.start(ctx.player());
            if (result == LobbyResult.STARTED) {
              serverAdapter.menus().close(ctx.player());
            } else {
              ctx.player().sendActionBar(switch (result) {
                case TOO_FEW -> "<red>Not enough players to start (min " + requiredPlayers + ").</red>";
                case FULL -> "<red>Too many players for the configured team slots.</red>";
                default -> "<red>Could not start the match.</red>";
              });
            }
          }).withModel(MenuArt.model(MenuArt.ICON_START)));
    }
    view.set(view.size() - 1, MenuButton.of(ItemKey.minecraft("barrier"),
        "<red><bold>Leave lobby</bold></red>", List.of(),
        ctx -> {
          serverAdapter.menus().close(ctx.player());
          lobbyManager.leave(ctx.player());
        }).withModel(MenuArt.model(MenuArt.ICON_LEAVE)));
    support.open(player, view);
  }

  /**
   * Pick a friend (or any online player) to invite to your lobby — friends first. The single reusable
   * invite picker, replacing the old separate party-invite and match-lobby-invite grids.
   */
  void openInviteFromFriends(PlayerAdapter player) {
    FriendService friendService = support.friendService;
    GameManager gameManager = support.gameManager;
    ServerAdapter serverAdapter = support.serverAdapter;
    MenuView view = new MenuView("<green><bold>Invite to Lobby</bold></green>", 6).background(MenuArt.BG_LOBBY);
    int slot = 0;
    Set<UUID> shown = new HashSet<>();
    // Online friends first (with a green presence dot).
    if (friendService != null) {
      for (FriendService.Entry entry : friendService.friends(player.uniqueId())) {
        if (slot >= 45) {
          break;
        }
        PlayerAdapter friend = serverAdapter.player(entry.playerId()).filter(PlayerAdapter::online).orElse(null);
        if (friend == null || friend.uniqueId().equals(player.uniqueId()) || gameManager.matchOf(friend) != null) {
          continue;
        }
        shown.add(friend.uniqueId());
        view.set(slot++, inviteHead(friend, true));
      }
    }
    // Then any other online player not already shown as a friend.
    for (PlayerAdapter candidate : serverAdapter.onlinePlayers()) {
      if (slot >= 45) {
        break;
      }
      if (candidate.uniqueId().equals(player.uniqueId()) || shown.contains(candidate.uniqueId())
          || gameManager.matchOf(candidate) != null) {
        continue;
      }
      view.set(slot++, inviteHead(candidate, false));
    }
    if (slot == 0) {
      view.set(22, MenuButton.label(ItemKey.minecraft("barrier"), "<gray>No players available to invite</gray>", List.of()));
    }
    view.set(view.size() - 9, support.back(ctx -> menus.openLobby(ctx.player())));
    support.open(player, view);
  }

  private MenuButton inviteHead(PlayerAdapter target, boolean friend) {
    LobbyManager lobbyManager = support.lobbyManager;
    ServerAdapter serverAdapter = support.serverAdapter;
    UUID targetId = target.uniqueId();
    String targetName = target.name();
    return MenuButton.head(targetId,
        (friend ? "<green>● </green>" : "") + "<white>" + MenuSupport.escape(targetName) + "</white>",
        List.of(friend ? "<green>Friend</green>" : "<gray>Online</gray>", "<yellow>Click to invite</yellow>"),
        ctx -> {
          LobbyResult result = lobbyManager.invite(ctx.player(), serverAdapter.player(targetId).orElse(target));
          ctx.player().sendActionBar(support.lobbyInviteMessage(result, targetName));
          if (result == LobbyResult.INVITE_SENT) {
            serverAdapter.player(targetId).filter(PlayerAdapter::online).ifPresent(invitee ->
                invitee.sendMiniMessage("<aqua>" + MenuSupport.escape(ctx.player().name())
                    + "</aqua> <gray>invited you to their lobby — open the menu ▶ Lobby ▶ Invites.</gray>"));
          }
          menus.openInviteFromFriends(ctx.player());
        });
  }

  /**
   * The "Friends" hotbar roster: every online friend shown as their own head, hovering to reveal their
   * username and exactly where they are right now (which experience / minigame / the lobby). Tapping a
   * friend who is inside an experience joins that experience via the existing friend-join system; tapping
   * a friend standing in a lobby teleports the viewer to them, even across different lobby worlds.
   */
  void openFriendsWarp(PlayerAdapter player) {
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;
    MenuView view = new MenuView("<green><bold>Friends</bold></green>", 6).background(MenuArt.BG_FRIENDS);
    int slot = 0;
    if (friendService != null) {
      for (FriendService.Entry entry : friendService.friends(player.uniqueId())) {
        if (slot >= 45) {
          break;
        }
        PlayerAdapter friend = serverAdapter.player(entry.playerId()).filter(PlayerAdapter::online).orElse(null);
        if (friend == null || friend.uniqueId().equals(player.uniqueId())) {
          continue;
        }
        view.set(slot++, friendWarpButton(friend));
      }
    }
    if (slot == 0) {
      view.set(22, MenuButton.label(ItemKey.minecraft("barrier"), "<gray>No friends online</gray>",
          List.of("<gray>Add friends from the Friends menu</gray>")));
    }
    view.set(view.size() - 9, support.back(ctx -> menus.openMain(ctx.player())));
    support.open(player, view);
  }

  /** One friend head: name = username, lore = current location, click = join their experience / teleport. */
  private MenuButton friendWarpButton(PlayerAdapter friend) {
    GameManager gameManager = support.gameManager;
    ExperienceService experienceService = support.experienceService;
    ServerAdapter serverAdapter = support.serverAdapter;
    UUID friendId = friend.uniqueId();
    String friendName = friend.name();
    ActiveMatch match = gameManager.matchOf(friend);
    ExperienceManager.Experience experience = match != null && experienceService != null
        ? experienceService.registry().byWorld(
            com.sexidium.core.world.WorldKey.fromRuntime(match.worldName()).orElse(null)) : null;
    String location;
    String action;
    if (experience != null) {
      location = "<light_purple>In: <white>" + MenuSupport.escape(experience.displayName()) + "</white></light_purple>";
      action = "<yellow>Tap to join their world</yellow>";
    } else if (match != null) {
      GameModeDescriptor descriptor = support.descriptorOf(match.modeId());
      String modeName = descriptor != null ? descriptor.displayName() : match.modeId();
      location = "<aqua>Playing: <white>" + MenuSupport.escape(modeName) + "</white></aqua>";
      action = "<dark_gray>Can't join a match in progress</dark_gray>";
    } else {
      location = "<gray>In the lobby</gray>";
      action = "<yellow>Tap to teleport to them</yellow>";
    }
    ExperienceManager.Experience joinExperience = experience;
    boolean inMatch = match != null;
    return MenuButton.head(friendId, "<white><bold>" + MenuSupport.escape(friendName) + "</bold></white>",
        List.of(location, action),
        ctx -> {
          serverAdapter.menus().close(ctx.player());
          if (joinExperience != null) {
            support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), joinExperience.id()));
          } else if (!inMatch) {
            PlayerAdapter live = serverAdapter.player(friendId).filter(PlayerAdapter::online).orElse(null);
            if (live == null || live.position() == null) {
              ctx.player().sendActionBar("<red>" + MenuSupport.escape(friendName) + " isn't available.</red>");
            } else {
              ctx.player().teleport(live.position());
              ctx.player().sendActionBar("<green>Teleported to " + MenuSupport.escape(friendName) + ".</green>");
            }
          } else {
            ctx.player().sendActionBar("<gray>" + MenuSupport.escape(friendName) + " is in a match you can't drop into.</gray>");
          }
        });
  }

  /** Lists every match lobby the player may join — public ones and lobbies they were invited to. */
  void openLobbyBrowser(PlayerAdapter player) {
    LobbyManager lobbyManager = support.lobbyManager;
    MenuView view = new MenuView("<aqua><bold>Match Lobbies</bold></aqua>", 6).background(MenuArt.BG_LOBBY);
    int slot = 0;
    if (lobbyManager != null) {
      for (Lobby lobby : lobbyManager.joinableFor(player.uniqueId(), null)) {
        if (slot >= 45) {
          break;
        }
        view.set(slot++, lobbyButton(lobby));
      }
    }
    if (slot == 0) {
      view.set(22, MenuButton.label(ItemKey.minecraft("paper"), "<gray>No open match lobbies</gray>",
          List.of("<gray>Open a minigame and pick</gray>", "<white>Create Lobby</white>")));
    }
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
  }

  /** One joinable-lobby row, labelled by host + mode + visibility. Shared by the browser and per-mode detail. */
  MenuButton lobbyButton(Lobby lobby) {
    LobbyManager lobbyManager = support.lobbyManager;
    GameModeDescriptor descriptor = support.descriptorOf(lobby.modeId());
    String modeLabel = descriptor != null ? descriptor.displayName() : lobby.modeId();
    return MenuButton.of(support.icon(lobby.modeId()),
        "<white><bold>" + MenuSupport.escape(support.name(lobby.host())) + "</bold></white><gray>'s lobby</gray>",
        List.of("<gray>Mode: <white>" + MenuSupport.escape(modeLabel) + "</white></gray>",
            "<gray>Players: <white>" + lobby.size() + (lobby.teamsEnabled() ? "/" + lobby.capacity() : "") + "</white></gray>",
            "<gray>Teams: <white>" + (lobby.teamsEnabled() ? lobby.teamCount() : "FFA") + "</white></gray>",
            lobby.isOpen() ? "<green>Public</green>" : "<yellow>Invite-only</yellow>",
            "<yellow>Click to join</yellow>"),
        ctx -> {
          LobbyResult result = lobbyManager.join(ctx.player(), lobby.id());
          switch (result) {
            case JOINED -> menus.openLobby(ctx.player());
            case ALREADY_IN -> ctx.player().sendActionBar("<red>Leave your current lobby or game first.</red>");
            case NOT_INVITED -> ctx.player().sendActionBar("<red>That lobby is invite-only — ask the host for an invite.</red>");
            case NOT_FOUND -> ctx.player().sendActionBar("<red>That lobby no longer exists.</red>");
            case FULL -> ctx.player().sendActionBar("<red>That lobby is full.</red>");
            default -> ctx.player().sendActionBar("<red>Could not join that lobby.</red>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_LOBBY_ROW));
  }

  private static int[] teamSlots(int teamCount) {
    if (teamCount <= 2) {
      return new int[] {2, 6};
    }
    if (teamCount == 3) {
      return new int[] {2, 4, 6};
    }
    return new int[] {1, 3, 5, 7};
  }
}
