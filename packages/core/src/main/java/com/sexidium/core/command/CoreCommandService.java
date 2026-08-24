package com.sexidium.core.command;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.world.map.SpawnPointStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.lower;

/**
 * Public facade for the {@code /sx} command tree. Adapters (Paper/NeoForge) and tests build this with
 * {@code (core, reloadCallback)} and call {@link #execute} / {@link #suggest}; the actual work is split
 * across focused, package-private handlers ({@link GameCommands}, {@link LobbyCommands}, …) that share a
 * {@link CommandContext}. This class owns only the permission gate and the root dispatch/suggestion table.
 */
public final class CoreCommandService {
  public static final String ADMIN_PERMISSION = "sexidium.admin";
  public static final String PLAY_PERMISSION = "sexidium.play";
  public static final String AUTH_PERMISSION = "sexidium.auth";

  private static final Set<String> PLAYER_SUBCOMMANDS = Set.of("menu", "exit", "leave", "join", "experience", "lobby", "friend", "top", "rank", "race");
  private static final Set<String> AUTH_SUBCOMMANDS = Set.of("auth");
  private static final Set<String> ADMIN_SUBCOMMANDS = Set.of("admin");
  private static final List<String> ROOT_ORDER = List.of(
      "help", "menu", "start", "exit", "leave", "join",
      "experience", "lobby", "friend", "top", "rank", "race", "auth", "admin");

  /** Stores for the unified in-world spawn sidecars edited by {@code /sx admin map combat} and {@code /sx lobby}. */
  private static final SpawnPointStore COMBAT_SPAWNS = new SpawnPointStore(SpawnPointStore.COMBAT);
  private static final SpawnPointStore LOBBY_SPAWNS = new SpawnPointStore(SpawnPointStore.LOBBY);

  private final ServerAdapter server;
  private final Runnable reloadCallback;

  private final CommandContext context;
  private final ExperienceCommands experienceCommands;
  private final GameCommands gameCommands;
  private final LobbyCommands lobbyCommands;
  private final FriendCommands friendCommands;
  private final NpcCommands npcCommands;
  private final TntWarCommands tntWarCommands;
  private final CombatCommands combatCommands;
  private final RaceCommands raceCommands;
  private final BotCommands botCommands;
  private final MapEditorCommands mapEditorCommands;
  private final AdminCommands adminCommands;

  public CoreCommandService(SexidiumCore core, Runnable reloadCallback) {
    Objects.requireNonNull(core, "core");
    this.server = core.gameContext().server();
    this.reloadCallback = reloadCallback == null ? core::reload : reloadCallback;

    this.context = new CommandContext(core, server, this.reloadCallback);
    this.experienceCommands = new ExperienceCommands(context);
    this.gameCommands = new GameCommands(context, experienceCommands);
    this.lobbyCommands = new LobbyCommands(context, LOBBY_SPAWNS);
    this.friendCommands = new FriendCommands(context);
    this.npcCommands = new NpcCommands(context);
    this.tntWarCommands = new TntWarCommands(context);
    this.combatCommands = new CombatCommands(context, COMBAT_SPAWNS);
    this.raceCommands = new RaceCommands(context);
    this.botCommands = new BotCommands(context);
    this.mapEditorCommands = new MapEditorCommands(context);
    this.adminCommands = new AdminCommands(context, gameCommands, botCommands, npcCommands,
        tntWarCommands, combatCommands, mapEditorCommands);
  }

  public boolean execute(CommandSource source, String[] args) {
    CommandSource safeSource = source == null ? server.console() : source;
    String[] safeArgs = args == null ? new String[0] : args;
    if (safeArgs.length == 0 || equalsAny(safeArgs[0], "help", "?")) {
      gameCommands.help(safeSource);
      return true;
    }

    String subcommand = lower(safeArgs[0]);
    if (!hasRootPermission(safeSource, subcommand)) {
      context.send(safeSource, MessageKey.COMMAND_NO_PERMISSION);
      return true;
    }

    switch (subcommand) {
      case "start" -> gameCommands.handleStart(safeSource, safeArgs);
      case "menu" -> gameCommands.handleMenu(safeSource);
      case "exit", "leave" -> gameCommands.handleExit(safeSource);
      case "join" -> gameCommands.handleJoin(safeSource, safeArgs);
      case "experience" -> experienceCommands.handle(safeSource, safeArgs);
      case "lobby" -> lobbyCommands.handle(safeSource, safeArgs);
      case "friend" -> friendCommands.handle(safeSource, safeArgs);
      case "top" -> gameCommands.handleTop(safeSource);
      case "rank" -> gameCommands.handleRank(safeSource, safeArgs.length > 1 ? safeArgs[1] : null);
      case "race" -> raceCommands.handle(safeSource, safeArgs);
      case "auth" -> gameCommands.handleAuth(safeSource, safeArgs);
      case "admin" -> adminCommands.handle(safeSource, safeArgs);
      default -> gameCommands.help(safeSource);
    }
    return true;
  }

  public List<String> suggest(CommandSource source, String[] args) {
    CommandSource safeSource = source == null ? server.console() : source;
    String[] safeArgs = args == null ? new String[0] : args;
    if (safeArgs.length <= 1) {
      List<String> values = new ArrayList<>();
      for (String value : ROOT_ORDER) {
        if (hasRootPermission(safeSource, value)) {
          values.add(value);
        }
      }
      return context.filter(values, safeArgs.length == 0 ? "" : safeArgs[0]);
    }

    String subcommand = lower(safeArgs[0]);
    if (!hasRootPermission(safeSource, subcommand)) {
      return List.of();
    }
    return switch (subcommand) {
      case "start" -> gameCommands.suggestStart(safeArgs);
      case "friend" -> friendCommands.suggest(safeArgs);
      case "join" -> gameCommands.suggestJoin(safeArgs);
      case "experience" -> experienceCommands.suggest(safeSource, safeArgs);
      case "lobby" -> lobbyCommands.suggest(safeSource, safeArgs);
      case "rank" -> safeArgs.length == 2 ? context.filter(context.playerNames(), safeArgs[1]) : List.of();
      case "auth" -> gameCommands.suggestAuth(safeSource, safeArgs);
      case "race" -> raceCommands.suggest(safeArgs);
      case "admin" -> adminCommands.suggest(safeSource, safeArgs);
      default -> List.of();
    };
  }

  private boolean hasRootPermission(CommandSource source, String subcommand) {
    if (subcommand.equals("start")) {
      // Players create their own experiences; admins additionally place players into minigames. Both
      // reach /sx start, and handleStart enforces the admin-only category/mode grammar internally.
      return source != null && (source.hasPermission(PLAY_PERMISSION) || source.hasPermission(ADMIN_PERMISSION));
    }
    if (PLAYER_SUBCOMMANDS.contains(subcommand)) {
      return source != null && source.hasPermission(PLAY_PERMISSION);
    }
    if (AUTH_SUBCOMMANDS.contains(subcommand)) {
      return source != null && (source.hasPermission(AUTH_PERMISSION) || source.hasPermission(PLAY_PERMISSION));
    }
    if (ADMIN_SUBCOMMANDS.contains(subcommand)) {
      return source != null && source.hasPermission(ADMIN_PERMISSION);
    }
    return true;
  }
}
