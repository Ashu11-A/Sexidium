package com.sexidium.core.menu;

import com.sexidium.core.data.FriendService;
import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
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

/**
 * The social screens: the friends list, the click-to-add-friend picker, the unified Invites inbox
 * (lobby invites + friend requests, with separate Accept/Decline taps), and the per-friend actions.
 */
final class SocialMenu {
  private final MenuSupport support;
  private final MenuService menus;

  SocialMenu(MenuSupport support, MenuService menus) {
    this.support = support;
    this.menus = menus;
  }

  void openFriends(PlayerAdapter player) {
    openFriends(player, 0);
  }

  void openFriends(PlayerAdapter player, int page) {
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;

    List<FriendService.Entry> friends = friendService != null ? friendService.friends(player.uniqueId()) : List.of();
    int pending = support.pendingInviteCount(player.uniqueId());

    MenuView view = PaginatedScreen.<FriendService.Entry>of("<green><bold>Friends</bold></green>")
        .background(MenuArt.BG_FRIENDS)
        .items(friends)
        .page(page)
        .emptyIndicator(MenuButton.label(ItemKey.minecraft("paper"), "<gray><bold>No friends added yet</bold></gray>",
            List.of("<gray>Click <green>'+ Add Friend'</green> below to connect!</gray>")))
        .itemMapper(friend -> {
          boolean online = serverAdapter.player(friend.playerId()).filter(PlayerAdapter::online).isPresent();
          return MenuButton.head(friend.playerId(),
              (online ? "<green>● </green>" : "<dark_gray>● </dark_gray>") + "<white><bold>" + MenuSupport.escape(friend.playerName()) + "</bold></white>",
              List.of(online ? "<green>Online now</green>" : "<dark_gray>Offline</dark_gray>", "<yellow>Click to manage friend</yellow>"),
              ctx -> menus.openFriendActions(ctx.player(), friend.playerId(), friend.playerName()));
        })
        .onPageChange(p -> openFriends(player, p))
        .back(support.backButton(() -> menus.openMain(player)))
        .primaryAction(MenuButton.of(ItemKey.minecraft("lime_dye"), "<green><bold>+ Add Friend</bold></green>",
            List.of("<gray>Pick an online player — no typing required</gray>"),
            ctx -> menus.openAddFriend(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_ADD_FRIEND)))
        .build();

    // Persistent sidebar navigation rail
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.LOBBY_SOCIAL);

    // Contextual Invites Button at Slot 51
    view.set(ChestLayout.SLOT_CHAOS, MenuButton.of(ItemKey.minecraft("writable_book"),
        "<aqua><bold>📬 Pending Invites (" + pending + ")</bold></aqua>",
        List.of("<gray>Friend requests & lobby invites</gray>", "<yellow>Click to review inbox</yellow>"),
        ctx -> menus.openInvites(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_INVITES)));

    support.open(player, view);
  }

  /** Click-to-add picker of online players who are not already this player's friend. */
  void openAddFriend(PlayerAdapter player) {
    openAddFriend(player, 0);
  }

  void openAddFriend(PlayerAdapter player, int page) {
    FriendService friendService = support.friendService;
    if (friendService == null) {
      menus.openFriends(player);
      return;
    }
    Set<UUID> friendIds = new HashSet<>();
    for (FriendService.Entry friend : friendService.friends(player.uniqueId())) {
      friendIds.add(friend.playerId());
    }
    support.openPlayerPicker(player, "<green><bold>Add Friend</bold></green>", page,
        candidate -> !candidate.uniqueId().equals(player.uniqueId()) && !friendIds.contains(candidate.uniqueId()),
        target -> {
          if (friendService.areFriends(player.uniqueId(), target.uniqueId())) {
            player.sendActionBar("<yellow>You are already friends with " + MenuSupport.escape(target.name()) + ".</yellow>");
          } else {
            friendService.requestAsync(player.uniqueId(), player.name(), target.uniqueId());
            target.sendMiniMessage("<aqua>" + MenuSupport.escape(player.name())
                + "</aqua> <gray>sent you a friend request — open the menu ▶ Friends ▶ Invites.</gray>");
            player.sendActionBar("<green>Friend request sent to " + MenuSupport.escape(target.name()) + ".</green>");
          }
          menus.openFriends(player);
        },
        support.back(ctx -> menus.openFriends(ctx.player())));
  }

  private sealed interface InviteEntry {
    record LobbyInvite(UUID lobbyId, UUID hostId, String hostName, String mode) implements InviteEntry {}
    record FriendRequest(UUID requesterId, String requesterName) implements InviteEntry {}
  }

  /**
   * The unified Invites inbox: lobby invites first, then friend requests. Accept and Decline are SEPARATE
   * single-tap buttons — never a shift-click.
   */
  void openInvites(PlayerAdapter player) {
    openInvites(player, 0);
  }

  void openInvites(PlayerAdapter player, int page) {
    LobbyManager lobbyManager = support.lobbyManager;
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;

    List<InviteEntry> entries = new ArrayList<>();

    // Lobby invites first
    if (lobbyManager != null) {
      for (UUID lobbyId : lobbyManager.pendingInviteLobbies(player.uniqueId())) {
        Lobby lobby = lobbyManager.lobbyById(lobbyId);
        if (lobby == null) {
          continue;
        }
        String hostName = support.name(lobby.host());
        String mode = lobby.modeId() == null ? "group" : lobby.modeId();
        entries.add(new InviteEntry.LobbyInvite(lobbyId, lobby.host(), hostName, mode));
      }
    }

    // Friend requests next
    if (friendService != null) {
      for (FriendService.Entry request : friendService.incomingRequests(player.uniqueId())) {
        entries.add(new InviteEntry.FriendRequest(request.playerId(), request.playerName()));
      }
    }

    int pairsPerPage = 17; // 17 pairs = 34 slots <= 35 content capacity
    int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / pairsPerPage));
    int current = Math.max(0, Math.min(totalPages - 1, page));

    SidebarScreen.Builder screen = SidebarScreen.of("<aqua><bold>Invites Inbox</bold></aqua>").background(MenuArt.BG_FRIENDS);

    // Sidebar: Summary info
    screen.sidebar(0, MenuButton.label(ItemKey.minecraft("writable_book"),
        "<aqua><bold>Invites Inbox</bold></aqua>",
        List.of("<gray>Pending: <white>" + entries.size() + " total</white></gray>",
            "<gray>Click <green>✔</green> to accept</gray>",
            "<gray>Click <red>✖</red> to decline</gray>")));

    if (entries.isEmpty()) {
      screen.content(17, MenuButton.label(ItemKey.minecraft("paper"),
          "<gray><bold>No pending invites</bold></gray>",
          List.of("<gray>Lobby invites and friend requests will appear here</gray>")));
    } else {
      int start = current * pairsPerPage;
      int end = Math.min(entries.size(), start + pairsPerPage);
      for (int i = start; i < end; i++) {
        int pairIdx = i - start;
        int acceptSlot = ChestLayout.contentSlot(pairIdx * 2);
        int declineSlot = ChestLayout.contentSlot(pairIdx * 2 + 1);

        InviteEntry entry = entries.get(i);
        if (entry instanceof InviteEntry.LobbyInvite lobby) {
          screen.content(acceptSlot, MenuButton.head(lobby.hostId(),
              "<green><bold>✔ Join</bold></green> <white>" + MenuSupport.escape(lobby.hostName()) + "</white>",
              List.of("<gray>" + MenuSupport.escape(lobby.mode()) + " lobby</gray>", "<green>Tap to join</green>"),
              ctx -> {
                LobbyResult result = lobbyManager.accept(ctx.player(), lobby.lobbyId());
                ctx.player().sendActionBar(support.lobbyAcceptMessage(result, lobby.hostName()));
                menus.openInvites(ctx.player());
              }));
          screen.content(declineSlot, MenuButton.of(ItemKey.minecraft("barrier"), "<red><bold>✖ Decline</bold></red>",
              List.of("<gray>Reject " + MenuSupport.escape(lobby.hostName()) + "'s lobby invite</gray>"),
              ctx -> {
                lobbyManager.declineInvite(ctx.player().uniqueId(), lobby.lobbyId());
                ctx.player().sendActionBar("<yellow>Declined invite from " + MenuSupport.escape(lobby.hostName()) + ".</yellow>");
                menus.openInvites(ctx.player());
              }).withModel(MenuArt.model(MenuArt.ICON_DECLINE)));
        } else if (entry instanceof InviteEntry.FriendRequest fr) {
          screen.content(acceptSlot, MenuButton.head(fr.requesterId(),
              "<green><bold>✔ Accept</bold></green> <white>" + MenuSupport.escape(fr.requesterName()) + "</white>",
              List.of("<aqua>Friend request</aqua>", "<green>Tap to become friends</green>"),
              ctx -> {
                boolean accepted = friendService.accept(player.uniqueId(), player.name(), fr.requesterId(), fr.requesterName());
                ctx.player().sendActionBar(accepted
                    ? "<green>You are now friends with " + MenuSupport.escape(fr.requesterName()) + ".</green>"
                    : "<red>Could not accept that request.</red>");
                serverAdapter.player(fr.requesterId()).filter(PlayerAdapter::online).ifPresent(requester ->
                    requester.sendMiniMessage("<green>" + MenuSupport.escape(player.name()) + "</green> <gray>accepted your friend request.</gray>"));
                menus.openInvites(ctx.player());
              }));
          screen.content(declineSlot, MenuButton.of(ItemKey.minecraft("barrier"), "<red><bold>✖ Decline</bold></red>",
              List.of("<gray>Reject " + MenuSupport.escape(fr.requesterName()) + "'s friend request</gray>"),
              ctx -> {
                friendService.denyRequestAsync(fr.requesterId(), player.uniqueId());
                ctx.player().sendActionBar("<yellow>Declined " + MenuSupport.escape(fr.requesterName()) + ".</yellow>");
                menus.openInvites(ctx.player());
              }).withModel(MenuArt.model(MenuArt.ICON_DECLINE)));
        }
      }
    }

    // Pagination controls on standard bottom navigation bar (Row 5)
    if (totalPages > 1) {
      if (current > 0) {
        screen.set(ChestLayout.SLOT_PREV, MenuButton.of(
            ItemKey.minecraft("arrow"),
            "<aqua>« Previous Page</aqua>",
            List.of("<gray>Go to page <white>" + current + "</white></gray>"),
            ctx -> openInvites(player, current - 1)
        ).withModel(MenuArt.model(MenuArt.ICON_BACK)));
      }

      screen.set(ChestLayout.SLOT_PAGE, MenuButton.label(
          ItemKey.minecraft("paper"),
          "<gray>Page <white>" + (current + 1) + "</white>/<white>" + totalPages + "</white></gray>",
          List.of("<gray>Total invites: <white>" + entries.size() + "</white></gray>")
      ));

      if (current < totalPages - 1) {
        screen.set(ChestLayout.SLOT_NEXT, MenuButton.of(
            ItemKey.minecraft("arrow"),
            "<aqua>Next Page »</aqua>",
            List.of("<gray>Go to page <white>" + (current + 2) + "</white></gray>"),
            ctx -> openInvites(player, current + 1)
        ));
      }
    }

    screen.back(support.back(ctx -> menus.openFriends(ctx.player())));
    support.open(player, screen.build());
  }

  /** Actions on one friend: invite to your lobby, join their lobby, warp, or remove. */
  void openFriendActions(PlayerAdapter player, UUID friendId, String friendName) {
    LobbyManager lobbyManager = support.lobbyManager;
    FriendService friendService = support.friendService;
    GameManager gameManager = support.gameManager;
    ExperienceService experienceService = support.experienceService;
    ServerAdapter serverAdapter = support.serverAdapter;

    MenuView view = new MenuView("<green><bold>" + MenuSupport.escape(friendName) + "</bold></green>", ChestLayout.ROWS)
        .plainRows(ChestLayout.ROWS)
        .background(MenuArt.BG_FRIENDS);

    ChestLayout.fillSeparators(view);

    // Sidebar: Unified global navigation rail with LOBBY_SOCIAL marked active
    SidebarNav.apply(view, player, menus, support, SidebarNav.NavSection.LOBBY_SOCIAL);

    boolean online = serverAdapter.player(friendId).filter(PlayerAdapter::online).isPresent();

    // Content: Row 0 Header Plaque (slot 5)
    view.set(5, MenuButton.head(friendId,
        "<white><bold>" + MenuSupport.escape(friendName) + "</bold></white>",
        List.of(online ? "<green>● Online</green>" : "<dark_gray>● Offline</dark_gray>",
            "<gray>Friend Profile & Actions</gray>"),
        null));

    // Content: Row 1 Action Cards (slots 12, 14, 16)
    view.set(12, MenuButton.of(ItemKey.minecraft("cake"), "<light_purple><bold>Invite to Lobby</bold></light_purple>",
        List.of(online ? "<yellow>Click to invite to your party/match</yellow>" : "<dark_gray>Player is offline</dark_gray>"),
        ctx -> {
          if (lobbyManager == null) {
            return;
          }
          PlayerAdapter target = serverAdapter.player(friendId).filter(PlayerAdapter::online).orElse(null);
          if (target == null) {
            ctx.player().sendActionBar("<red>" + MenuSupport.escape(friendName) + " is offline.</red>");
            return;
          }
          LobbyResult result = lobbyManager.invite(ctx.player(), target);
          ctx.player().sendActionBar(support.lobbyInviteMessage(result, target.name()));
          if (result == LobbyResult.INVITE_SENT) {
            target.sendMiniMessage("<aqua>" + MenuSupport.escape(ctx.player().name())
                + "</aqua> <gray>invited you to their lobby — open the menu ▶ Friends ▶ Invites.</gray>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_INVITE)));

    view.set(14, MenuButton.of(ItemKey.minecraft("ender_pearl"), "<aqua><bold>Join Their Lobby</bold></aqua>",
        List.of(online ? "<gray>Join the lobby they host</gray>" : "<dark_gray>Player is offline</dark_gray>"),
        ctx -> {
          serverAdapter.menus().close(ctx.player());
          joinFriendGame(ctx.player(), friendId, friendName);
        }).withModel(MenuArt.model(MenuArt.ICON_JOIN)));

    view.set(16, MenuButton.of(ItemKey.minecraft("compass"), "<gold><bold>Warp to Friend</bold></gold>",
        List.of(online ? "<yellow>Tap to teleport / join world</yellow>" : "<dark_gray>Player is offline</dark_gray>"),
        ctx -> {
          PlayerAdapter live = serverAdapter.player(friendId).filter(PlayerAdapter::online).orElse(null);
          if (live == null) {
            ctx.player().sendActionBar("<red>" + MenuSupport.escape(friendName) + " is offline.</red>");
            return;
          }
          ActiveMatch match = gameManager.matchOf(live);
          ExperienceManager.Experience experience = match != null && experienceService != null
              ? experienceService.registry().byWorld(
                  com.sexidium.core.world.WorldKey.fromRuntime(match.worldName()).orElse(null)) : null;
          serverAdapter.menus().close(ctx.player());
          if (experience != null) {
            support.announceEnter(ctx.player(), experienceService.enter(ctx.player(), experience.id()));
          } else if (match == null) {
            if (live.position() != null) {
              ctx.player().teleport(live.position());
              ctx.player().sendActionBar("<green>Teleported to " + MenuSupport.escape(friendName) + ".</green>");
            } else {
              ctx.player().sendActionBar("<red>" + MenuSupport.escape(friendName) + " isn't available.</red>");
            }
          } else {
            ctx.player().sendActionBar("<gray>" + MenuSupport.escape(friendName) + " is in a match you can't drop into.</gray>");
          }
        }));

    // Bottom Navigation: Slot 47 Back, Slot 53 Remove Friend
    view.set(ChestLayout.SLOT_BACK, support.back(ctx -> {
      support.clearConfirm(ctx.player().uniqueId());
      menus.openFriends(ctx.player());
    }));

    view.set(ChestLayout.SLOT_PRIMARY, support.confirmButton(player, ItemKey.minecraft("barrier"), MenuArt.model(MenuArt.ICON_DECLINE), "unfriend:" + friendId,
        "<red><bold>Remove Friend</bold></red>",
        List.of("<gray>Tap, then tap again to confirm</gray>"),
        "<red><bold>⚠ Tap again to remove</bold></red>",
        List.of("<yellow>Tap once more to remove " + MenuSupport.escape(friendName) + "</yellow>"),
        ctx -> {
          if (friendService != null) {
            friendService.removeAsync(ctx.player().uniqueId(), friendId);
          }
          ctx.player().sendActionBar("<yellow>Removed " + MenuSupport.escape(friendName) + " from your friends.</yellow>");
          menus.openFriends(ctx.player());
        },
        viewer -> menus.openFriendActions(viewer, friendId, friendName)));

    support.open(player, view);
  }

  private void joinFriendGame(PlayerAdapter player, UUID friendId, String friendName) {
    LobbyManager lobbyManager = support.lobbyManager;
    Lobby lobby = lobbyManager == null ? null : lobbyManager.lobbyOf(friendId);
    if (lobby == null) {
      player.sendActionBar("<gray>" + MenuSupport.escape(friendName) + " isn't hosting a joinable lobby — try Quick Match.</gray>");
      return;
    }
    LobbyResult result = lobbyManager.join(player, lobby.id());
    switch (result) {
      case JOINED -> menus.openLobby(player);
      case ALREADY_IN -> player.sendActionBar("<red>Leave your current lobby or game first (/leave).</red>");
      case NOT_INVITED -> player.sendActionBar("<red>" + MenuSupport.escape(friendName) + "'s lobby is invite-only.</red>");
      case FULL -> player.sendActionBar("<red>" + MenuSupport.escape(friendName) + "'s lobby is full.</red>");
      default -> player.sendActionBar("<red>Could not join " + MenuSupport.escape(friendName) + "'s lobby.</red>");
    }
  }
}
