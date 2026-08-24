package com.sexidium.core.command;

import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;

import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.lower;

/** Handles {@code /friend} — friend requests, accept/remove and listing. */
final class FriendCommands {
  private final CommandContext ctx;

  FriendCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  void handle(CommandSource source, String[] args) {
    if (args.length < 2) {
      ctx.send(source, MessageKey.FRIEND_USAGE);
      return;
    }
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    if (ctx.core.friends() == null) {
      ctx.send(source, MessageKey.FRIEND_UNAVAILABLE);
      return;
    }
    switch (lower(args[1])) {
      case "add" -> friendAdd(source, player, CommandText.arg(args, 2));
      case "accept" -> friendAccept(source, player, CommandText.arg(args, 2));
      case "remove" -> friendRemove(source, player, CommandText.arg(args, 2));
      case "list" -> friendList(source, player);
      case "requests" -> friendRequests(source, player);
      default -> ctx.send(source, MessageKey.FRIEND_USAGE);
    }
  }

  private void friendAdd(CommandSource source, PlayerAdapter player, String targetName) {
    PlayerAdapter target = ctx.onlinePlayerOrUsage(source, targetName);
    if (target == null) return;
    if (player.uniqueId().equals(target.uniqueId())) {
      ctx.send(source, MessageKey.FRIEND_REQUEST_SELF);
      return;
    }
    if (ctx.core.friends().areFriends(player.uniqueId(), target.uniqueId())) {
      ctx.send(source, MessageKey.FRIEND_ALREADY_FRIENDS, MessageArg.text("player", target.name()));
      return;
    }
    ctx.core.friends().requestAsync(player.uniqueId(), player.name(), target.uniqueId());
    ctx.send(source, MessageKey.FRIEND_REQUEST_SENT, MessageArg.text("player", target.name()));
    ctx.send(target, MessageKey.FRIEND_REQUEST_RECEIVED, MessageArg.text("player", player.name()));
  }

  private void friendAccept(CommandSource source, PlayerAdapter player, String targetName) {
    PlayerAdapter target = ctx.onlinePlayerOrUsage(source, targetName);
    if (target == null) return;
    if (!ctx.core.friends().accept(player.uniqueId(), player.name(), target.uniqueId(), target.name())) {
      ctx.send(source, MessageKey.FRIEND_ACCEPT_NONE, MessageArg.text("player", target.name()));
      return;
    }
    ctx.send(source, MessageKey.FRIEND_ACCEPTED, MessageArg.text("player", target.name()));
    ctx.send(target, MessageKey.FRIEND_ACCEPTED_BY, MessageArg.text("player", player.name()));
  }

  private void friendRemove(CommandSource source, PlayerAdapter player, String targetName) {
    PlayerAdapter target = ctx.onlinePlayerOrUsage(source, targetName);
    if (target == null) return;
    ctx.core.friends().removeAsync(player.uniqueId(), target.uniqueId());
    ctx.send(source, MessageKey.FRIEND_REMOVED, MessageArg.text("player", target.name()));
  }

  private void friendList(CommandSource source, PlayerAdapter player) {
    List<FriendService.Entry> friends = ctx.core.friends().friends(player.uniqueId());
    if (friends.isEmpty()) {
      ctx.send(source, MessageKey.FRIEND_LIST_EMPTY);
      return;
    }
    ctx.send(source, MessageKey.FRIEND_LIST_HEADER, MessageArg.text("count", friends.size()));
    for (FriendService.Entry entry : friends) {
      boolean online = ctx.server.player(entry.playerId()).filter(PlayerAdapter::online).isPresent();
      ctx.send(source, MessageKey.FRIEND_LIST_ROW,
          MessageArg.text("player", entry.playerName()),
          MessageArg.mini("status", online ? "<green>online</green>" : "<gray>offline</gray>"));
    }
  }

  private void friendRequests(CommandSource source, PlayerAdapter player) {
    List<FriendService.Entry> requests = ctx.core.friends().incomingRequests(player.uniqueId());
    if (requests.isEmpty()) {
      ctx.send(source, MessageKey.FRIEND_REQUESTS_EMPTY);
      return;
    }
    ctx.send(source, MessageKey.FRIEND_REQUESTS_HEADER, MessageArg.text("count", requests.size()));
    for (FriendService.Entry entry : requests) {
      ctx.send(source, MessageKey.FRIEND_REQUESTS_ROW, MessageArg.text("player", entry.playerName()));
    }
  }

  List<String> suggest(String[] args) {
    if (args.length == 2) {
      return ctx.filter(List.of("add", "accept", "remove", "list", "requests"), args[1]);
    }
    if (args.length == 3 && equalsAny(args[1], "add", "accept", "remove")) {
      return ctx.filter(ctx.playerNames(), args[2]);
    }
    return List.of();
  }
}
