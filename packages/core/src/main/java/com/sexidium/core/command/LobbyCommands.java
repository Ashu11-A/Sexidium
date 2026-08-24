package com.sexidium.core.command;

import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyResult;
import com.sexidium.core.world.lobby.LobbyEnums.LobbyVisibility;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.map.SpawnPointStore;
import com.sexidium.core.world.map.SpawnPoints;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.escape;
import static com.sexidium.core.command.CommandText.fmt;
import static com.sexidium.core.command.CommandText.lower;
import static com.sexidium.core.command.CommandText.parseIntOr;

/** Handles {@code /lobby} — the unified party/match-lobby/queue surface plus its admin spawn config. */
final class LobbyCommands {
  static final String LOBBY_USAGE =
      "<gray>/lobby <invite|accept|leave|kick|disband|list|join|mode|teams|size|visibility|start|queue></gray>";

  /** Admin-only spawn-config subcommands of {@code /lobby} (the bare {@code /lobby} is player-facing). */
  static final Set<String> LOBBY_ADMIN_SUBCOMMANDS = Set.of("setspawn", "addspawn", "spawns", "clearspawns");

  private final CommandContext ctx;
  private final SpawnPointStore lobbySpawns;

  LobbyCommands(CommandContext ctx, SpawnPointStore lobbySpawns) {
    this.ctx = ctx;
    this.lobbySpawns = lobbySpawns;
  }

  void handle(CommandSource source, String[] args) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    // Bare "/lobby" opens the unified lobby home (or the browser when not in one).
    if (args.length <= 1) {
      ctx.core.menus().openLobby(player);
      return;
    }
    switch (lower(args[1])) {
      case "invite" -> lobbyInvite(source, player, CommandText.arg(args, 2));
      case "accept" -> lobbyAccept(source, player, CommandText.arg(args, 2));
      case "leave" -> lobbyLeave(source, player);
      case "kick" -> lobbyKick(source, player, CommandText.arg(args, 2));
      case "disband" -> lobbyDisband(source, player);
      case "list" -> lobbyList(source, player);
      case "join" -> lobbyJoin(source, player, CommandText.arg(args, 2));
      case "mode" -> lobbyMode(source, player, CommandText.arg(args, 2));
      case "teams" -> lobbyTeams(source, player, CommandText.arg(args, 2));
      case "size" -> lobbySize(source, player, CommandText.arg(args, 2));
      case "visibility", "privacy" -> lobbyVisibility(source, player, CommandText.arg(args, 2));
      case "start" -> lobbyStart(source, player);
      case "queue" -> lobbyQueue(source, player, CommandText.arg(args, 2));
      case "setspawn", "addspawn", "spawns", "clearspawns" -> lobbySpawnCommand(source, player, lower(args[1]));
      default -> ctx.send(source, LOBBY_USAGE);
    }
  }

  // ----- lobby spawn points (admin) ----------------------------------------------------------------

  /**
   * Defines the lobby's player spawn locations, persisted to {@code sexidium-lobby.yml} in the lobby world
   * folder and read live by {@code worlds().lobbySpawn()} (join, respawn, void-redirect, leave-match): so
   * the lobby spawn is configured in-game like a TNT-War or Combat map, not hand-edited in config.yml.
   * {@code setspawn} pins THE spawn (point 1); {@code addspawn} appends one so joiners are spread out.
   */
  private void lobbySpawnCommand(CommandSource source, PlayerAdapter player, String action) {
    if (source == null || !source.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      ctx.send(source, MessageKey.COMMAND_NO_PERMISSION);
      return;
    }
    String lobbyName = ctx.server.worlds().lobbyName();
    java.nio.file.Path folder = ctx.server.worlds().lobbyDataFolder();
    SpawnPoints spawns = lobbySpawns.load(folder, lobbyName);
    spawns.setWorld(lobbyName);
    switch (action) {
      case "spawns" -> {
        if (!spawns.isReady()) {
          ctx.send(source, "<gray>No lobby spawns set. Stand where you want players to spawn and run "
              + "<white>/lobby setspawn</white>.</gray>");
          return;
        }
        ctx.send(source, "<aqua><bold>Lobby spawns (" + spawns.size() + "):</bold></aqua>");
        int index = 1;
        for (WorldPosition spawn : spawns.all()) {
          ctx.send(source, " <white>#" + index++ + "</white> <gray>" + fmt(spawn.coordinateX()) + ", "
              + fmt(spawn.coordinateY()) + ", " + fmt(spawn.coordinateZ()) + "</gray>");
        }
        return;
      }
      case "clearspawns" -> {
        spawns.clear();
        if (saveLobbySpawns(source, folder, spawns)) {
          ctx.send(source, "<yellow>Cleared all lobby spawns. The world's own spawn is used until you set one.</yellow>");
        }
        return;
      }
      default -> { /* setspawn / addspawn handled below */ }
    }
    if (player == null || player.position() == null) {
      ctx.send(source, "<red>Stand in the lobby where players should spawn, then run this command.</red>");
      return;
    }
    WorldPosition here = player.position().withWorldName(lobbyName);
    if (action.equals("setspawn")) {
      spawns.setPrimary(here);
    } else {
      spawns.add(here);
    }
    if (saveLobbySpawns(source, folder, spawns)) {
      ctx.send(source, "<green>Lobby spawn " + (action.equals("setspawn") ? "set" : "#" + spawns.size() + " added")
          + " at <white>" + fmt(here.coordinateX()) + ", " + fmt(here.coordinateY()) + ", " + fmt(here.coordinateZ())
          + "</white>. <gray>Active now for join/respawn/leave.</gray></green>");
    }
  }

  private boolean saveLobbySpawns(CommandSource source, java.nio.file.Path folder, SpawnPoints spawns) {
    try {
      lobbySpawns.save(folder, spawns);
      return true;
    } catch (java.io.IOException exception) {
      ctx.server.logger().warning("Failed to save lobby spawns", exception);
      ctx.send(source, "<red>Could not save the lobby spawns: " + exception.getMessage() + "</red>");
      return false;
    }
  }

  // ----- roster ops (reuse the localized party.* messages — the group is still "your party") -------

  private void lobbyInvite(CommandSource source, PlayerAdapter player, String targetName) {
    PlayerAdapter target = ctx.onlinePlayerOrUsage(source, targetName);
    if (target == null) {
      return;
    }
    switch (ctx.core.lobbies().invite(player, target)) {
      case INVITE_SENT -> {
        ctx.send(source, MessageKey.PARTY_INVITE_SENT, MessageArg.text("player", target.name()));
        ctx.send(target, MessageKey.PARTY_INVITE_RECEIVED, MessageArg.text("player", player.name()));
      }
      case SELF -> ctx.send(source, MessageKey.PARTY_INVITE_SELF);
      case NOT_LEADER -> ctx.send(source, MessageKey.PARTY_INVITE_NOT_LEADER);
      case FULL -> ctx.send(source, MessageKey.PARTY_INVITE_FULL);
      case TARGET_IN_PARTY -> ctx.send(source, MessageKey.PARTY_INVITE_TARGET_IN_PARTY);
      default -> ctx.send(source, "<red>Could not send the invite.</red>");
    }
  }

  private void lobbyAccept(CommandSource source, PlayerAdapter player, String hostName) {
    UUID lobbyId = null;
    String displayName = hostName == null ? "" : hostName;
    if (hostName != null && !hostName.isBlank()) {
      PlayerAdapter host = ctx.onlinePlayerOrUsage(source, hostName);
      if (host == null) {
        return;
      }
      Lobby hostLobby = ctx.core.lobbies().lobbyOf(host.uniqueId());
      lobbyId = hostLobby == null ? host.uniqueId() : hostLobby.id();
      displayName = host.name();
    }
    switch (ctx.core.lobbies().accept(player, lobbyId)) {
      case JOINED -> {
        ctx.send(source, MessageKey.PARTY_JOINED, MessageArg.text("player", displayName));
        Lobby joined = ctx.core.lobbies().lobbyOf(player.uniqueId());
        if (joined != null) {
          for (PlayerAdapter member : joined.onlineMembers(ctx.server)) {
            if (!member.uniqueId().equals(player.uniqueId())) {
              ctx.send(member, MessageKey.PARTY_JOINED_BROADCAST, MessageArg.text("player", player.name()));
            }
          }
        }
      }
      case NO_INVITE -> ctx.send(source, MessageKey.PARTY_NO_INVITE);
      case AMBIGUOUS -> ctx.send(source, MessageKey.PARTY_AMBIGUOUS);
      case FULL -> ctx.send(source, MessageKey.PARTY_INVITE_FULL);
      case DISBANDED -> ctx.send(source, MessageKey.PARTY_DISBANDED_INVITE);
      case ALREADY_IN_PARTY -> ctx.send(source, MessageKey.PARTY_ALREADY_IN_PARTY);
      default -> ctx.send(source, MessageKey.PARTY_NO_INVITE);
    }
  }

  private void lobbyLeave(CommandSource source, PlayerAdapter player) {
    ctx.send(source, ctx.core.lobbies().leave(player) == LobbyResult.LEFT
        ? MessageKey.PARTY_LEFT : MessageKey.PARTY_NOT_IN_PARTY);
  }

  private void lobbyKick(CommandSource source, PlayerAdapter player, String targetName) {
    PlayerAdapter target = ctx.onlinePlayerOrUsage(source, targetName);
    if (target == null) {
      return;
    }
    if (!ctx.core.lobbies().kick(player.uniqueId(), target.uniqueId())) {
      ctx.send(source, MessageKey.PARTY_KICK_FAILED);
      return;
    }
    ctx.send(source, MessageKey.PARTY_KICK_SUCCESS, MessageArg.text("player", target.name()));
    ctx.send(target, MessageKey.PARTY_KICKED);
  }

  private void lobbyDisband(CommandSource source, PlayerAdapter player) {
    if (ctx.core.lobbies().disband(player.uniqueId()) == LobbyResult.NOT_LEADER) {
      ctx.send(source, MessageKey.PARTY_INVITE_NOT_LEADER);
      return;
    }
    ctx.send(source, MessageKey.PARTY_DISBANDED);
  }

  // ----- browser + host-match configuration --------------------------------------------------------

  private void lobbyList(CommandSource source, PlayerAdapter player) {
    List<Lobby> open = ctx.core.lobbies().joinableFor(player.uniqueId(), null);
    if (open.isEmpty()) {
      ctx.send(source, "<gray>No open lobbies to join.</gray>");
      return;
    }
    ctx.send(source, "<aqua><bold>Open lobbies:</bold></aqua>");
    for (Lobby lobby : open) {
      String hostName = ctx.playerName(lobby.host());
      ctx.send(source, " <white>" + escape(hostName) + "</white> <gray>(" + lobby.modeId() + ", "
          + lobby.size() + " player(s))</gray> <yellow>/lobby join " + escape(hostName) + "</yellow>");
    }
  }

  private void lobbyJoin(CommandSource source, PlayerAdapter player, String hostName) {
    if (hostName == null || hostName.isBlank()) {
      ctx.send(source, "<red>Usage:</red> <white>/lobby join <host></white>");
      return;
    }
    ctx.send(source, switch (ctx.core.lobbies().joinByName(player, hostName)) {
      case JOINED -> "<green>Joined the lobby.</green>";
      case ALREADY_IN -> "<red>You are already in a lobby or group.</red>";
      case ALREADY_IN_MATCH -> "<red>Leave your current match first (/leave).</red>";
      case NOT_FOUND -> "<red>No such lobby.</red>";
      case NOT_INVITED -> "<red>That lobby is invite-only — ask the host for an invite.</red>";
      case FULL -> "<red>That lobby is full.</red>";
      default -> "<red>Could not join.</red>";
    });
  }

  private void lobbyMode(CommandSource source, PlayerAdapter player, String mode) {
    if (mode == null) {
      ctx.send(source, "<red>Usage:</red> <white>/lobby mode <minigame></white>");
      return;
    }
    ctx.send(source, switch (ctx.core.lobbies().configure(player, mode)) {
      case CONFIGURED -> "<green>Hosting <white>" + escape(mode)
          + "</white>. Invite friends, then <white>/lobby start</white>.</green>";
      case NOT_LEADER -> "<red>Only the group leader can pick the mode.</red>";
      case NOT_MINIGAME -> "<red>That is not a minigame.</red>";
      case ALREADY_IN_MATCH -> "<red>Leave your current match first (/leave).</red>";
      default -> "<red>Could not host that mode.</red>";
    });
  }

  private void lobbyTeams(CommandSource source, PlayerAdapter player, String value) {
    if (value == null) {
      ctx.send(source, "<red>Usage:</red> <white>/lobby teams <2-" + Lobby.MAX_TEAMS + "|ffa></white>");
      return;
    }
    int count = "ffa".equalsIgnoreCase(value) ? 0 : parseIntOr(value, -1);
    if (count < 0) {
      ctx.send(source, "<red>Enter a team count (2-" + Lobby.MAX_TEAMS + "), or 'ffa'.</red>");
      return;
    }
    ctx.send(source, switch (ctx.core.lobbies().setTeamCount(player, count)) {
      case OK -> "<green>Teams set to <white>" + (count == 0 ? "FFA" : count) + "</white>.</green>";
      case NOT_HOST -> "<red>Only the host can change the teams.</red>";
      default -> "<red>You are not hosting a lobby.</red>";
    });
  }

  private void lobbySize(CommandSource source, PlayerAdapter player, String value) {
    if (value == null) {
      ctx.send(source, "<red>Usage:</red> <white>/lobby size <players-per-team></white>");
      return;
    }
    int size = parseIntOr(value, -1);
    if (size < 1) {
      ctx.send(source, "<red>Enter the players per team (>= 1).</red>");
      return;
    }
    ctx.send(source, switch (ctx.core.lobbies().setTeamSize(player, size)) {
      case OK -> "<green>Players per team set to <white>" + size + "</white>.</green>";
      case NOT_HOST -> "<red>Only the host can change the team size.</red>";
      default -> "<red>You are not hosting a lobby.</red>";
    });
  }

  private void lobbyVisibility(CommandSource source, PlayerAdapter player, String value) {
    LobbyVisibility visibility = parseVisibility(value);
    if (visibility == null) {
      ctx.send(source, "<red>Usage:</red> <white>/lobby visibility <public|friends|invite></white>");
      return;
    }
    ctx.send(source, switch (ctx.core.lobbies().setVisibility(player, visibility)) {
      case OK -> "<green>Lobby is now <white>" + value.toLowerCase(Locale.ROOT) + "</white>.</green>";
      case NOT_HOST -> "<red>Only the host can change visibility.</red>";
      default -> "<red>You are not hosting a lobby.</red>";
    });
  }

  private void lobbyStart(CommandSource source, PlayerAdapter player) {
    ctx.send(source, switch (ctx.core.lobbies().start(player)) {
      case STARTED -> "<green>Starting the match…</green>";
      case TOO_FEW -> "<red>Not enough players online to start.</red>";
      case FULL -> "<red>Too many players for the configured team slots.</red>";
      case NOT_HOST -> "<red>Only the host can start the match.</red>";
      case NOT_MINIGAME -> "<red>Pick a mode first: <white>/lobby mode <minigame></white>.</red>";
      case FAILED -> "<red>Could not start (check the mode's minimum players).</red>";
      default -> "<red>You are not hosting a lobby.</red>";
    });
  }

  private void lobbyQueue(CommandSource source, PlayerAdapter player, String arg) {
    if (arg != null && equalsAny(arg, "leave", "cancel", "stop")) {
      ctx.send(source, ctx.core.lobbies().dequeue(player) == LobbyResult.DEQUEUED
          ? "<yellow>Left the quick-play queue.</yellow>"
          : "<gray>You are not in a queue.</gray>");
      return;
    }
    if (arg == null) {
      ctx.send(source, "<red>Usage:</red> <white>/lobby queue <minigame></white>");
      return;
    }
    ctx.send(source, switch (ctx.core.lobbies().queue(player, arg)) {
      case QUEUED, ALREADY_QUEUED -> "<green>You're in the quick-play queue for <white>" + escape(arg)
          + "</white>. We'll start when enough players are ready.</green>";
      case NOT_LEADER -> "<red>Only the group leader can queue. Leave your group first to play solo.</red>";
      case ALREADY_IN_MATCH -> "<red>Leave your current match first (/leave).</red>";
      case NOT_MINIGAME -> "<red>'<white>" + escape(arg) + "</white>' is not a minigame.</red>";
      default -> "<red>Could not join the queue.</red>";
    });
  }

  private static LobbyVisibility parseVisibility(String value) {
    if (value == null) {
      return null;
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "public", "open" -> LobbyVisibility.PUBLIC;
      case "friends", "friends-only", "friendsonly" -> LobbyVisibility.FRIENDS_ONLY;
      case "invite", "invite-only", "private" -> LobbyVisibility.INVITE_ONLY;
      default -> null;
    };
  }

  List<String> suggest(CommandSource source, String[] args) {
    if (args.length == 2) {
      List<String> options = new ArrayList<>(List.of("invite", "accept", "leave", "kick", "disband", "list", "join",
          "mode", "teams", "size", "visibility", "start", "queue"));
      if (source != null && source.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
        options.addAll(LOBBY_ADMIN_SUBCOMMANDS);
      }
      return ctx.filter(options, args[1]);
    }
    if (args.length == 3) {
      return switch (lower(args[1])) {
        case "invite", "kick", "accept" -> ctx.filter(ctx.playerNames(), args[2]);
        case "join" -> {
          List<String> hosts = new ArrayList<>();
          for (Lobby lobby : ctx.core.lobbies().all()) {
            if (!lobby.isIdle()) {
              hosts.add(ctx.playerName(lobby.host()));
            }
          }
          yield ctx.filter(hosts, args[2]);
        }
        case "mode" -> ctx.filter(ctx.minigameIds(), args[2]);
        case "queue" -> {
          List<String> options = new ArrayList<>(ctx.minigameIds());
          options.add("leave");
          yield ctx.filter(options, args[2]);
        }
        case "teams" -> ctx.filter(List.of("ffa", "2", "3", "4"), args[2]);
        case "size" -> ctx.filter(List.of("1", "2", "3", "4"), args[2]);
        case "visibility" -> ctx.filter(List.of("public", "friends", "invite"), args[2]);
        default -> List.of();
      };
    }
    return List.of();
  }
}
