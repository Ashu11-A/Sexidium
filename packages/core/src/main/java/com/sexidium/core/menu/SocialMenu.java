package com.sexidium.core.menu;

import com.sexidium.core.data.FriendService;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.lobby.LobbyResult;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.ItemKey;

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
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;
    MenuView view = new MenuView("<green><bold>Friends</bold></green>", 6).background(MenuArt.BG_FRIENDS);
    view.set(3, MenuButton.of(ItemKey.minecraft("lime_dye"), "<green><bold>Add Friend</bold></green>",
        List.of("<gray>Pick an online player — no typing</gray>"),
        ctx -> menus.openAddFriend(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_ADD_FRIEND)));
    int pending = support.pendingInviteCount(player.uniqueId());
    view.set(5, MenuButton.of(ItemKey.minecraft("writable_book"), "<aqua><bold>Invites</bold></aqua>",
        List.of("<gray>Pending: <white>" + pending + "</white></gray>",
            "<gray>Friend requests + lobby invites</gray>", "<yellow>Click to review</yellow>"),
        ctx -> menus.openInvites(ctx.player())).withModel(MenuArt.model(MenuArt.ICON_INVITES)));
    int slot = 9;
    if (friendService != null) {
      for (FriendService.Entry friend : friendService.friends(player.uniqueId())) {
        if (slot >= 45) {
          break;
        }
        boolean online = serverAdapter.player(friend.playerId()).filter(PlayerAdapter::online).isPresent();
        view.set(slot++, MenuButton.head(friend.playerId(),
            (online ? "<green>● </green>" : "<dark_gray>● </dark_gray>") + "<white>" + MenuSupport.escape(friend.playerName()) + "</white>",
            List.of(online ? "<green>Online</green>" : "<dark_gray>Offline</dark_gray>", "<yellow>Click to manage</yellow>"),
            ctx -> menus.openFriendActions(ctx.player(), friend.playerId(), friend.playerName())));
      }
    }
    if (slot == 9) {
      view.set(22, MenuButton.label(ItemKey.minecraft("barrier"), "<gray>No friends yet</gray>",
          List.of("<gray>Use <white>Add Friend</white> above</gray>")));
    }
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
  }

  /** Click-to-add picker of online players who are not already this player's friend. */
  void openAddFriend(PlayerAdapter player) {
    FriendService friendService = support.friendService;
    if (friendService == null) {
      menus.openFriends(player);
      return;
    }
    Set<UUID> friendIds = new HashSet<>();
    for (FriendService.Entry friend : friendService.friends(player.uniqueId())) {
      friendIds.add(friend.playerId());
    }
    support.openPlayerPicker(player, "<green><bold>Add Friend</bold></green>",
        candidate -> !candidate.uniqueId().equals(player.uniqueId()) && !friendIds.contains(candidate.uniqueId()),
        target -> {
          if (friendService.areFriends(player.uniqueId(), target.uniqueId())) {
            player.sendActionBar("<yellow>You are already friends with " + MenuSupport.escape(target.name()) + ".</yellow>");
          } else {
            friendService.requestAsync(player.uniqueId(), player.name(), target.uniqueId());
            target.sendMiniMessage("<aqua>" + MenuSupport.escape(player.name())
                + "</aqua> <gray>sent you a friend request — open the menu ▶ Friends ▶ Friend Requests.</gray>");
            player.sendActionBar("<green>Friend request sent to " + MenuSupport.escape(target.name()) + ".</green>");
          }
          menus.openFriends(player);
        },
        support.back(ctx -> menus.openFriends(ctx.player())));
  }

  /**
   * The unified Invites inbox: lobby invites first, then friend requests (the two were structurally
   * identical screens). Accept and Decline are SEPARATE single-tap buttons — never a shift-click, which
   * a Bedrock player can't perform (Geyser maps every tap to LEFT), so on mobile they could otherwise
   * only ever accept and never clear an unwanted invite.
   */
  void openInvites(PlayerAdapter player) {
    LobbyManager lobbyManager = support.lobbyManager;
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;
    MenuView view = new MenuView("<aqua><bold>Invites</bold></aqua>", 6).background(MenuArt.BG_FRIENDS);
    int slot = 0;
    // Lobby invites first (Accept = join their lobby/group).
    if (lobbyManager != null) {
      for (UUID lobbyId : lobbyManager.pendingInviteLobbies(player.uniqueId())) {
        if (slot + 1 >= 45) {
          break;
        }
        Lobby lobby = lobbyManager.lobbyById(lobbyId);
        if (lobby == null) {
          continue;
        }
        String hostName = support.name(lobby.host());
        String mode = lobby.modeId() == null ? "group" : lobby.modeId();
        view.set(slot++, MenuButton.head(lobby.host(), "<green><bold>✔ Join</bold></green> <white>" + MenuSupport.escape(hostName) + "</white>",
            List.of("<gray>" + MenuSupport.escape(mode) + " lobby</gray>", "<green>Tap to join</green>"),
            ctx -> {
              LobbyResult result = lobbyManager.accept(ctx.player(), lobbyId);
              ctx.player().sendActionBar(support.lobbyAcceptMessage(result, hostName));
              menus.openInvites(ctx.player());
            }));
        view.set(slot++, MenuButton.of(ItemKey.minecraft("barrier"), "<red><bold>✖ Decline</bold></red>",
            List.of("<gray>Reject " + MenuSupport.escape(hostName) + "'s lobby invite</gray>"),
            ctx -> {
              lobbyManager.declineInvite(ctx.player().uniqueId(), lobbyId);
              ctx.player().sendActionBar("<yellow>Declined invite from " + MenuSupport.escape(hostName) + ".</yellow>");
              menus.openInvites(ctx.player());
            }).withModel(MenuArt.model(MenuArt.ICON_DECLINE)));
      }
    }
    // Friend requests next.
    if (friendService != null) {
      for (FriendService.Entry request : friendService.incomingRequests(player.uniqueId())) {
        if (slot + 1 >= 45) {
          break;
        }
        view.set(slot++, MenuButton.head(request.playerId(), "<green><bold>✔ Accept</bold></green> <white>" + MenuSupport.escape(request.playerName()) + "</white>",
            List.of("<aqua>Friend request</aqua>", "<green>Tap to become friends</green>"),
            ctx -> {
              boolean accepted = friendService.accept(player.uniqueId(), player.name(), request.playerId(), request.playerName());
              ctx.player().sendActionBar(accepted
                  ? "<green>You are now friends with " + MenuSupport.escape(request.playerName()) + ".</green>"
                  : "<red>Could not accept that request.</red>");
              serverAdapter.player(request.playerId()).filter(PlayerAdapter::online).ifPresent(requester ->
                  requester.sendMiniMessage("<green>" + MenuSupport.escape(player.name()) + "</green> <gray>accepted your friend request.</gray>"));
              menus.openInvites(ctx.player());
            }));
        view.set(slot++, MenuButton.of(ItemKey.minecraft("barrier"), "<red><bold>✖ Decline</bold></red>",
            List.of("<gray>Reject " + MenuSupport.escape(request.playerName()) + "'s friend request</gray>"),
            ctx -> {
              friendService.denyRequestAsync(request.playerId(), player.uniqueId());
              ctx.player().sendActionBar("<yellow>Declined " + MenuSupport.escape(request.playerName()) + ".</yellow>");
              menus.openInvites(ctx.player());
            }).withModel(MenuArt.model(MenuArt.ICON_DECLINE)));
      }
    }
    if (slot == 0) {
      view.set(22, MenuButton.label(ItemKey.minecraft("barrier"), "<gray>No pending invites</gray>", List.of()));
    }
    view.set(view.size() - 9, support.back(ctx -> menus.openFriends(ctx.player())));
    support.open(player, view);
  }

  /** Actions on one friend: invite to your lobby, join their lobby, or remove. */
  void openFriendActions(PlayerAdapter player, UUID friendId, String friendName) {
    LobbyManager lobbyManager = support.lobbyManager;
    FriendService friendService = support.friendService;
    ServerAdapter serverAdapter = support.serverAdapter;
    MenuView view = new MenuView("<green><bold>" + MenuSupport.escape(friendName) + "</bold></green>", 3).background(MenuArt.BG_FRIENDS);
    boolean online = serverAdapter.player(friendId).filter(PlayerAdapter::online).isPresent();
    view.set(10, MenuButton.of(ItemKey.minecraft("cake"), "<light_purple><bold>Invite to Lobby</bold></light_purple>",
        List.of(online ? "<yellow>Click to invite</yellow>" : "<dark_gray>Offline</dark_gray>"),
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
                + "</aqua> <gray>invited you to their lobby — open the menu ▶ Lobby ▶ Invites.</gray>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_INVITE)));
    view.set(13, MenuButton.of(ItemKey.minecraft("ender_pearl"), "<aqua><bold>Join Their Lobby</bold></aqua>",
        List.of(online ? "<gray>Join the lobby they host</gray>" : "<dark_gray>Offline</dark_gray>"),
        ctx -> {
          serverAdapter.menus().close(ctx.player());
          joinFriendGame(ctx.player(), friendId, friendName);
        }).withModel(MenuArt.model(MenuArt.ICON_JOIN)));
    view.set(16, support.confirmButton(player, ItemKey.minecraft("barrier"), MenuArt.model(MenuArt.ICON_DECLINE), "unfriend:" + friendId,
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
    view.set(view.size() - 9, support.back(ctx -> {
          support.clearConfirm(ctx.player().uniqueId());
          menus.openFriends(ctx.player());
        }));
    support.open(player, view);
  }

  private void joinFriendGame(PlayerAdapter player, UUID friendId, String friendName) {
    LobbyManager lobbyManager = support.lobbyManager;
    // Joining a running match is gone — players join a friend's match LOBBY instead.
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
