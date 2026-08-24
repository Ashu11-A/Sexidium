package com.sexidium.core.command;

import com.sexidium.core.auth.AuthResults.AuthCodeResult;
import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.lib.data.Profile;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static com.sexidium.core.command.CommandText.addCommaSeparated;
import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.normalizeCategory;

/**
 * Handles the core {@code /sx} verbs: start, kit, menu, exit, join, top/rank, auth, help and the
 * admin-only stop. Delegates the player-created {@code /sx start experience} grammar to
 * {@link ExperienceCommands}.
 */
final class GameCommands {
  private final CommandContext ctx;
  private final ExperienceCommands experiences;

  GameCommands(CommandContext ctx, ExperienceCommands experiences) {
    this.ctx = ctx;
    this.experiences = experiences;
  }

  // ----- start -------------------------------------------------------------------------------------

  void handleStart(CommandSource source, String[] args) {
    // Players create their own composable experience here. Anyone with sexidium.play
    // may run it; it brings the initiator and their online party into a freshly leased world.
    if (args.length >= 2 && equalsAny(args[1], "experience", "exp", "experiences")) {
      experiences.handleStart(source, args);
      return;
    }
    // Chaos: player-accessible like experience — brings the initiator + their online party into a fresh
    // shared world. Others hop in with /sx join chaos.
    if (args.length >= 2 && equalsAny(args[1], "chaos", "roulette", "random")) {
      if (ctx.requirePlayer(source) == null) {
        return;
      }
      List<PlayerAdapter> participants = ctx.participants(List.of(), source);
      if (participants.isEmpty()) {
        ctx.send(source, MessageKey.COMMAND_START_NO_PLAYERS);
        return;
      }
      boolean startedChaos = ctx.core.games().start(
          com.sexidium.core.game.chaos.ChaosGame.MODE_ID, participants, source, List.of());
      if (startedChaos && !ctx.core.games().isStarting()) {
        ctx.send(source, MessageKey.COMMAND_START_SUCCESS,
            MessageArg.text("mode", "chaos"), MessageArg.text("count", participants.size()));
      }
      return;
    }
    // Placing arbitrary players into a minigame by name (category-first grammar) is an admin action.
    if (source == null || !source.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      ctx.send(source, MessageKey.COMMAND_NO_PERMISSION);
      return;
    }
    StartRequest request = parseStart(args);
    if (request.modeId() == null || request.modeId().isBlank()) {
      ctx.send(source, MessageKey.COMMAND_START_USAGE);
      return;
    }
    // The category is mandatory: /sx start <minigames> <mode>.
    if (request.category() == null || request.category().isBlank()) {
      ctx.send(source, MessageKey.COMMAND_START_USAGE);
      return;
    }
    if (!ctx.categoryExists(request.category())) {
      ctx.send(source, MessageKey.COMMAND_START_UNKNOWN_CATEGORY, MessageArg.text("category", request.category()));
      return;
    }
    GameModeDescriptor descriptor = ctx.descriptor(request.modeId());
    if (descriptor == null
        || !normalizeCategory(request.category()).equals(normalizeCategory(descriptor.category()))) {
      ctx.send(source, MessageKey.COMMAND_START_UNKNOWN_CATEGORY, MessageArg.text("category", request.category()));
      return;
    }

    List<PlayerAdapter> participants = ctx.participants(request.playerNames(), source);
    if (participants.isEmpty()) {
      ctx.send(source, MessageKey.COMMAND_START_NO_PLAYERS);
      return;
    }
    boolean started = ctx.core.games().start(request.modeId(), participants, source, request.modeArgs());
    if (started && !ctx.core.games().isStarting()) {
      ctx.send(source, MessageKey.COMMAND_START_SUCCESS,
          MessageArg.text("mode", request.modeId().toLowerCase(Locale.ROOT)),
          MessageArg.text("count", participants.size()));
    }
  }

  private StartRequest parseStart(String[] args) {
    if (args.length < 2) {
      return new StartRequest(null, null, List.of(), List.of());
    }
    // Category-first grammar: /sx start <category> <mode> [players...].
    // - A single token after "start" is treated as a bare mode with a missing
    //   category, which handleStart rejects with the usage hint.
    // - Two or more tokens: the first is always the category slot (even when
    //   unknown, so handleStart can report it), the second is the mode.
    String first = args[1];
    String category;
    int index;
    if (ctx.categoryExists(first)) {
      category = first;
      index = 2;
    } else if (args.length == 2) {
      return new StartRequest(null, first, List.of(), List.of());
    } else {
      category = first;
      index = 2;
    }
    String modeId = index < args.length ? args[index++] : null;
    List<String> playerNames = new ArrayList<>();
    List<String> modeArgs = new ArrayList<>();
    while (index < args.length) {
      String arg = args[index++];
      if (arg == null || arg.isBlank()) {
        continue;
      }
      if (arg.startsWith("--players=")) {
        addCommaSeparated(playerNames, arg.substring("--players=".length()));
      } else if (arg.equals("--players") && index < args.length) {
        addCommaSeparated(playerNames, args[index++]);
      } else if (arg.startsWith("--")) {
        modeArgs.add(arg);
      } else {
        playerNames.add(arg);
      }
    }
    return new StartRequest(category, modeId, playerNames, modeArgs);
  }

  List<String> suggestStart(String[] args) {
    // First position: the start categories plus "experience". After "experience", every following
    // position lists the selectable challenge ids (multiple may be chosen). Otherwise the second
    // position lists the modes within the chosen category, then player names.
    if (args.length == 2) {
      List<String> first = new ArrayList<>(ctx.categories());
      if (!first.contains("experience")) {
        first.add("experience");
      }
      return ctx.filter(first, args[1]);
    }
    if (equalsAny(args[1], "experience", "exp", "experiences")) {
      return ctx.filter(experiences.challengeIds(), args[args.length - 1]);
    }
    if (args.length == 3 && ctx.categoryExists(args[1])) {
      return ctx.filter(ctx.modeIdsInCategory(args[1]), args[2]);
    }
    return ctx.filter(ctx.playerNames(), args[args.length - 1]);
  }

  // ----- stop --------------------------------------------------------------------------------------

  void handleStop(CommandSource source) {
    if (!ctx.core.games().isRunning()) {
      ctx.send(source, MessageKey.COMMAND_STOP_NOT_RUNNING);
      return;
    }
    ctx.core.games().stopActiveGame(LocalizedText.of(source.playerSource() ? MessageKey.STOP_BY_PLAYER : MessageKey.STOP_BY_CONSOLE,
        MessageArg.text("player", source.name())));
    ctx.send(source, MessageKey.COMMAND_STOP_SUCCESS);
  }

  // ----- kit ---------------------------------------------------------------------------------------

  void handleKit(CommandSource source, String[] args) {
    if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
      ctx.send(source, MessageKey.COMMAND_KIT_LIST, MessageArg.text("kits", String.join(", ", ctx.core.gameContext().kits().names())));
      return;
    }
    if (!args[1].equalsIgnoreCase("give")) {
      ctx.send(source, MessageKey.COMMAND_KIT_USAGE);
      return;
    }
    KitGive kitGive = parseKitGive(source, args);
    if (kitGive == null) {
      return;
    }
    PlayerAdapter target = kitGive.playerName() == null ? ctx.playerSource(source) : ctx.server.playerExact(kitGive.playerName()).orElse(null);
    if (target == null) {
      ctx.send(source, kitGive.playerName() == null ? MessageKey.COMMAND_KIT_CONSOLE_TARGET : MessageKey.COMMAND_KIT_PLAYER_OFFLINE,
          MessageArg.text("player", kitGive.playerName() == null ? "" : kitGive.playerName()));
      return;
    }
    if (!ctx.core.gameContext().kits().apply(target, kitGive.kitName())) {
      ctx.send(source, MessageKey.COMMAND_KIT_UNKNOWN, MessageArg.text("kit", kitGive.kitName()));
      return;
    }
    ctx.send(source, MessageKey.COMMAND_KIT_GIVEN_SENDER, MessageArg.text("player", target.name()), MessageArg.text("kit", kitGive.kitName()));
    ctx.send(target, MessageKey.COMMAND_KIT_GIVEN_TARGET, MessageArg.text("kit", kitGive.kitName()));
  }

  private KitGive parseKitGive(CommandSource source, String[] args) {
    if (args.length < 3) {
      ctx.send(source, MessageKey.COMMAND_KIT_GIVE_USAGE);
      return null;
    }
    if (args.length == 3) {
      return new KitGive(args[2], null);
    }
    String first = args[2];
    String second = args[3];
    boolean firstKit = ctx.core.gameContext().kits().exists(first);
    boolean secondKit = ctx.core.gameContext().kits().exists(second);
    if (firstKit || !secondKit) {
      return new KitGive(first, second);
    }
    return new KitGive(second, first);
  }

  List<String> suggestKit(String[] args) {
    if (args.length == 2) {
      return ctx.filter(List.of("list", "give"), args[1]);
    }
    if (!args[1].equalsIgnoreCase("give")) {
      return List.of();
    }
    if (args.length == 3) {
      List<String> values = new ArrayList<>(ctx.core.gameContext().kits().names());
      values.addAll(ctx.playerNames());
      return ctx.filter(values, args[2]);
    }
    if (args.length == 4) {
      List<String> values = ctx.core.gameContext().kits().exists(args[2]) ? ctx.playerNames() : new ArrayList<>(ctx.core.gameContext().kits().names());
      return ctx.filter(values, args[3]);
    }
    return List.of();
  }

  // ----- menu / exit / join ------------------------------------------------------------------------

  void handleMenu(CommandSource source) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    ctx.core.menus().openMain(player);
  }

  void handleExit(CommandSource source) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    if (!ctx.core.games().removePlayer(player, true)) {
      // Not in a match is not the same answer everywhere. On a node with no lobby world of its own,
      // a player who is not in a match is standing in that node's default overworld with no way out
      // -- "you are not in a match" is true and useless, and it is exactly what a failed arrival
      // strands them in. The return trip on a worker IS a transfer, so ask the router first and only
      // report the plain refusal where staying put is a sensible outcome (a lobby, or standalone).
      if (ctx.core.gameContext().routeToLobby(player)) {
        return;
      }
      ctx.send(source, MessageKey.COMMAND_EXIT_NOT_IN_GAME);
      return;
    }
    ctx.send(source, MessageKey.COMMAND_EXIT_SUCCESS);
  }

  void handleJoin(CommandSource source, String[] args) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    // Reconnect to your own persisted match (e.g. after a server restart) without the party/friend
    // gate. In-session reconnects happen automatically on login; this covers a manual /sx join.
    if (ctx.core.games().matchOf(player) == null
        && ctx.core.games().hasPersistedSession(player.uniqueId())
        && ctx.core.games().handleJoin(player)) {
      ActiveMatch rejoined = ctx.core.games().matchOf(player);
      ctx.send(source, MessageKey.COMMAND_JOIN_SUCCESS,
          MessageArg.text("mode", rejoined == null ? "" : rejoined.modeId()));
      return;
    }
    String modeId = args.length > 1 ? args[1] : null;
    if (modeId == null || modeId.isBlank()) {
      ctx.send(source, MessageKey.COMMAND_JOIN_USAGE);
      return;
    }
    // A player may only join a match where a friend or party member is already playing — never a
    // stranger's experience. Admins use /sx start to place arbitrary players.
    Set<UUID> relatedPlayerIds = relatedPlayers(player);
    GameManager.JoinResult result = ctx.core.games().joinInProgress(player, modeId.toLowerCase(Locale.ROOT), relatedPlayerIds);
    switch (result) {
      case JOINED -> ctx.send(source, MessageKey.COMMAND_JOIN_SUCCESS, MessageArg.text("mode", modeId.toLowerCase(Locale.ROOT)));
      case NOT_RUNNING -> ctx.send(source, MessageKey.COMMAND_JOIN_NOT_RUNNING, MessageArg.text("mode", modeId.toLowerCase(Locale.ROOT)));
      case NOT_RELATED -> ctx.send(source, MessageKey.COMMAND_JOIN_NOT_ALLOWED, MessageArg.text("mode", modeId.toLowerCase(Locale.ROOT)));
      case ALREADY_IN_MATCH -> ctx.send(source, MessageKey.COMMAND_JOIN_ALREADY_IN_MATCH);
      case PLAYER_OFFLINE -> ctx.send(source, MessageKey.COMMAND_KIT_PLAYER_OFFLINE, MessageArg.text("player", player.name()));
    }
  }

  /** Online lobby/group members + persisted friends of the player — the set whose matches they may join. */
  private Set<UUID> relatedPlayers(PlayerAdapter player) {
    Set<UUID> relatedPlayerIds = new HashSet<>();
    if (ctx.core.lobbies() != null) {
      Lobby lobby = ctx.core.lobbies().lobbyOf(player.uniqueId());
      if (lobby != null) {
        for (PlayerAdapter member : lobby.onlineMembers(ctx.server)) {
          relatedPlayerIds.add(member.uniqueId());
        }
      }
    }
    if (ctx.core.friends() != null) {
      for (FriendService.Entry friend : ctx.core.friends().friends(player.uniqueId())) {
        relatedPlayerIds.add(friend.playerId());
      }
    }
    return relatedPlayerIds;
  }

  List<String> suggestJoin(String[] args) {
    if (args.length == 2) {
      return ctx.filter(ctx.core.games().runningModeIds(), args[1]);
    }
    return List.of();
  }

  // ----- top / rank --------------------------------------------------------------------------------

  void handleTop(CommandSource source) {
    if (ctx.core.ranks() == null) {
      ctx.send(source, MessageKey.COMMAND_RANKS_UNAVAILABLE);
      return;
    }
    List<Profile> profiles = ctx.core.ranks().top(10);
    if (profiles.isEmpty()) {
      ctx.send(source, MessageKey.COMMAND_TOP_EMPTY);
      return;
    }
    ctx.send(source, MessageKey.COMMAND_TOP_TITLE);
    int rank = 1;
    for (Profile profile : profiles) {
      ctx.send(source, MessageKey.COMMAND_TOP_ROW,
          MessageArg.text("rank", rank++),
          MessageArg.text("player", profile.name()),
          MessageArg.text("points", profile.points()),
          MessageArg.text("level", profile.level()),
          MessageArg.text("wins", profile.wins()),
          MessageArg.text("kills", profile.kills()));
    }
  }

  void handleRank(CommandSource source, String playerName) {
    if (ctx.core.ranks() == null) {
      ctx.send(source, MessageKey.COMMAND_RANKS_UNAVAILABLE);
      return;
    }
    String targetName = playerName;
    if (targetName == null || targetName.isBlank()) {
      PlayerAdapter player = ctx.requirePlayer(source);
      if (player == null) {
        return;
      }
      targetName = player.name();
    }
    Profile profile = ctx.core.ranks().byName(targetName);
    if (profile == null) {
      ctx.send(source, MessageKey.COMMAND_RANK_NONE, MessageArg.text("player", targetName));
      return;
    }
    ctx.send(source, MessageKey.COMMAND_RANK_ROW,
        MessageArg.text("player", profile.name()),
        MessageArg.text("points", profile.points()),
        MessageArg.text("level", profile.level()),
        MessageArg.text("wins", profile.wins()),
        MessageArg.text("kills", profile.kills()),
        MessageArg.text("games", profile.games()));
  }

  // ----- auth --------------------------------------------------------------------------------------

  void handleAuth(CommandSource source, String[] args) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    if (ctx.core.auth() == null) {
      ctx.send(source, MessageKey.AUTH_UNAVAILABLE);
      return;
    }
    try {
      long ttlSeconds = Math.max(1L, ctx.server.configuration().getLong("auth.code-expiry-seconds", 600L));
      int length = ctx.server.configuration().getInt("auth.code-length", 6);
      String characters = ctx.server.configuration().getString("auth.code-characters", "23456789");
      AuthCodeResult result = ctx.core.auth().createCode(player.uniqueId().toString(), player.name(), length, ttlSeconds * 1000L, characters);
      switch (result.status()) {
        case CREATED -> ctx.send(source, MessageKey.AUTH_CODE_CREATED,
            MessageArg.text("code", result.code()), MessageArg.text("seconds", ttlSeconds));
        case ALREADY_LINKED -> ctx.send(source, MessageKey.AUTH_ALREADY_LINKED, MessageArg.text("discord", result.discordUserId()));
        case DISABLED -> ctx.send(source, MessageKey.AUTH_DISABLED);
      }
    } catch (SQLException exception) {
      ctx.server.logger().warning("Failed to create auth code for " + player.name(), exception);
      ctx.send(source, MessageKey.AUTH_UNAVAILABLE);
    }
  }

  List<String> suggestAuth(CommandSource source, String[] args) {
    if (args.length == 2 && source.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      return ctx.filter(List.of("code"), args[1]);
    }
    return List.of();
  }

  // ----- help --------------------------------------------------------------------------------------

  void help(CommandSource source) {
    ctx.send(source, MessageKey.COMMAND_HELP_TITLE);
    ctx.send(source, MessageKey.COMMAND_HELP_MENU);
    ctx.send(source, MessageKey.COMMAND_HELP_START);
    ctx.send(source, MessageKey.COMMAND_HELP_EXPERIENCE);
    ctx.send(source, MessageKey.COMMAND_HELP_LOBBY);
    ctx.send(source, MessageKey.COMMAND_HELP_FRIEND);
    ctx.send(source, MessageKey.COMMAND_HELP_EXIT);
    ctx.send(source, MessageKey.COMMAND_HELP_JOIN);
    ctx.send(source, MessageKey.COMMAND_HELP_TOP);
    ctx.send(source, MessageKey.COMMAND_HELP_RANK);
    ctx.send(source, MessageKey.COMMAND_HELP_AUTH);
    // Admins additionally see the /sx admin pointer (bot, npc, map, kit, reload, stop live there).
    if (source != null && source.hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      ctx.send(source, MessageKey.COMMAND_HELP_ADMIN);
    }
  }

  private record StartRequest(String category, String modeId, List<String> playerNames, List<String> modeArgs) {
  }

  private record KitGive(String kitName, String playerName) {
  }
}
