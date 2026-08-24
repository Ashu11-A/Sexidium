package com.sexidium.core.command;

import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.CommandSource;

import java.util.Arrays;
import java.util.List;

import static com.sexidium.core.command.CommandText.lower;

/**
 * Handles the admin namespace {@code /sx admin <…>}, which groups every operator/build tool that used
 * to sit at the {@code /sx} root: {@code reload}, {@code stop}, {@code kit}, {@code bot}, {@code npc}
 * and the map editors ({@code map tntwar|combat|edit}). Each branch reslices the argument array so the
 * existing focused handler ({@link BotCommands}, {@link TntWarCommands}, …) receives exactly the shape
 * it did as a top-level command — the handlers only read tokens at index ≥1, so they need no change.
 */
final class AdminCommands {
  private static final String USAGE =
      "<gray>Usage: <white>/sx admin <reload|stop|kit|bot|npc|net|backup|selftest|broadcast"
          + "|map <tntwar|combat|edit>></white></gray>";
  private static final String BROADCAST_USAGE =
      "<gray>Usage: <white>/sx admin broadcast <seconds> <update|restart|shutdown></white></gray>";
  private static final String MAP_USAGE =
      "<gray>Usage: <white>/sx admin map <tntwar|combat|edit> ...</white></gray>";

  private final CommandContext ctx;
  private final GameCommands game;
  private final BotCommands bot;
  private final NpcCommands npc;
  private final TntWarCommands tntWar;
  private final CombatCommands combat;
  private final MapEditorCommands mapEditor;
  private final NetworkCommands network;
  private final BackupCommands backup;

  AdminCommands(CommandContext ctx, GameCommands game, BotCommands bot, NpcCommands npc,
                TntWarCommands tntWar, CombatCommands combat, MapEditorCommands mapEditor) {
    this.ctx = ctx;
    this.game = game;
    this.bot = bot;
    this.npc = npc;
    this.tntWar = tntWar;
    this.combat = combat;
    this.mapEditor = mapEditor;
    this.network = new NetworkCommands(ctx);
    this.backup = new BackupCommands(ctx);
  }

  /** {@code args = [admin, <tool>, …]}. */
  void handle(CommandSource source, String[] args) {
    if (args.length < 2) {
      ctx.send(source, USAGE);
      return;
    }
    String[] sub = Arrays.copyOfRange(args, 1, args.length); // [<tool>, …]
    switch (lower(args[1])) {
      case "reload" -> {
        ctx.reloadCallback.run();
        ctx.send(source, MessageKey.COMMAND_RELOAD);
      }
      case "stop" -> game.handleStop(source);
      case "kit" -> game.handleKit(source, sub);
      case "bot" -> bot.handle(source, sub);
      case "npc" -> npc.handle(source, sub);
      case "net" -> network.handle(source, sub);
      case "backup" -> backup.handle(source, sub);
      case "selftest" -> selfTest(source);
      case "broadcast" -> broadcast(source, sub);
      case "map" -> handleMap(source, args);
      default -> ctx.send(source, USAGE);
    }
  }

  /**
   * "Is this node fit to serve?", answered before a player finds out the hard way.
   *
   * <p>The rollback trigger for a rolling update: a node whose challenge catalog is stale or
   * half-broken generates <b>normal terrain into what should be an empty void SkyBlock</b>, silently
   * and permanently. This is what turns that into a decision the pipeline can make before the node
   * takes a player.</p>
   *
   * <p>Runs on the calling (main) thread, catches every {@link Throwable}, and emits exactly one
   * {@code SX-SELFTEST} line to the console — which is what an orchestrator tailing the container log
   * waits on. It never throws: a node that crashes while checking whether it is healthy has told
   * nobody anything useful.</p>
   */
  private void selfTest(CommandSource source) {
    com.sexidium.core.network.NodeSelfTest selfTest = ctx.core.network().selfTest();
    if (selfTest == null) {
      // Still exactly one SX-SELFTEST line, so a pipeline waiting on the prefix is not left hanging.
      String line = "SX-SELFTEST ok=false detail=no selftest is wired on this node";
      ctx.server.logger().warning(line);
      ctx.send(source, "<red>" + line + "</red>");
      return;
    }
    com.sexidium.core.network.NodeSelfTest.Result result;
    try {
      result = selfTest.run();
    } catch (Throwable failed) {
      // NodeSelfTest promises not to throw; this is the belt, because breaking that promise here
      // would abort a console command an operator is using to decide whether to roll back.
      String line = "SX-SELFTEST ok=false detail=" + failed.getClass().getSimpleName();
      ctx.server.logger().severe(line);
      ctx.send(source, "<red>" + line + "</red>");
      return;
    }
    String line = result.logLine();
    if (result.ok()) {
      ctx.server.logger().info(line);
      ctx.send(source, "<green>" + line + "</green>");
    } else {
      ctx.server.logger().warning(line);
      ctx.send(source, "<red>" + line.replace("<", "\\<") + "</red>");
    }
  }

  /**
   * {@code /sx admin broadcast <seconds> <update|restart|shutdown>} — the orchestrator's countdown.
   *
   * <p><b>The pipeline sends a number and a reason key, never a sentence.</b> It cannot do otherwise
   * and be correct: it does not know what language each player reads, and formatting the text on the
   * scripting side would be a second copy of a string that is already localized here — the wrong copy,
   * in one language, for everybody. So the wire is {@code (seconds, reason)} and the words are ours.</p>
   *
   * <p>Three properties the pipeline depends on, and all three are load-bearing:</p>
   *
   * <ul>
   *   <li><b>Never throws.</b> It is called from a machine that is mid-rollout; an exception here
   *       would surface as a failed control call and abort a roll over a chat message.</li>
   *   <li><b>A no-op when nobody is online</b>, which is the common case for a worker being rolled at
   *       04:00 — and the reason the pipeline can call it unconditionally.</li>
   *   <li><b>An unknown reason still warns.</b> A future pipeline sending a reason this build has
   *       never heard of gets the generic update wording rather than silence: the players must be told
   *       something, and the exact adjective matters less than the countdown.</li>
   * </ul>
   *
   * <p>{@code args = [broadcast, <seconds>, <reason>]}.</p>
   */
  private void broadcast(CommandSource source, String[] args) {
    if (args.length < 3) {
      ctx.send(source, BROADCAST_USAGE);
      return;
    }
    long seconds;
    try {
      seconds = Long.parseLong(args[1].trim());
    } catch (NumberFormatException notANumber) {
      ctx.send(source, BROADCAST_USAGE);
      return;
    }
    if (seconds < 0) {
      ctx.send(source, BROADCAST_USAGE);
      return;
    }
    MessageKey key = switch (lower(args[2])) {
      case "restart" -> MessageKey.NETWORK_BROADCAST_RESTART;
      case "shutdown" -> MessageKey.NETWORK_BROADCAST_SHUTDOWN;
      // "update" and anything else. See the javadoc: an unrecognised reason must still warn.
      default -> MessageKey.NETWORK_BROADCAST_UPDATE;
    };
    try {
      int online = ctx.server.onlinePlayers().size();
      if (online == 0) {
        // Deliberately still an OK answer to the caller, not a refusal: "there was nobody to tell"
        // is a success for a pipeline that broadcasts before every roll.
        ctx.send(source, "<gray>Nobody is online; nothing was broadcast.</gray>");
        return;
      }
      for (com.sexidium.core.platform.PlayerAdapter player : ctx.server.onlinePlayers()) {
        ctx.server.messages().send(player, com.sexidium.core.i18n.LocalizedText.of(
            key, com.sexidium.core.i18n.MessageArg.text("seconds", seconds)));
      }
      ctx.send(source, "<gray>Broadcast <white>" + lower(args[2]) + "</white> in <white>" + seconds
          + "</white>s to <white>" + online + "</white> player(s).</gray>");
    } catch (RuntimeException failed) {
      // Warned, never rethrown. A broadcast that could not be delivered must not fail the roll.
      ctx.server.logger().warning("Could not broadcast the " + lower(args[2]) + " notice: "
          + failed.getMessage());
    }
  }

  /** {@code args = [admin, map, <tool>, …]}. */
  private void handleMap(CommandSource source, String[] args) {
    if (args.length < 3) {
      ctx.send(source, MAP_USAGE);
      return;
    }
    String[] sub = Arrays.copyOfRange(args, 2, args.length); // [<tool>, …]
    switch (lower(args[2])) {
      case "tntwar" -> tntWar.handle(source, sub);
      case "combat" -> combat.handle(source, sub);
      case "edit" -> mapEditor.handle(source, sub);
      default -> ctx.send(source, MAP_USAGE);
    }
  }

  List<String> suggest(CommandSource source, String[] args) {
    if (args.length == 2) {
      return ctx.filter(
          List.of("reload", "stop", "kit", "bot", "npc", "net", "backup", "selftest",
              "broadcast", "map"),
          args[1]);
    }
    String[] sub = Arrays.copyOfRange(args, 1, args.length);
    if ("broadcast".equals(lower(args[1]))) {
      return args.length == 3 ? ctx.filter(List.of("60", "30", "10"), args[2])
          : args.length == 4 ? ctx.filter(List.of("update", "restart", "shutdown"), args[3])
          : List.of();
    }
    return switch (lower(args[1])) {
      case "kit" -> game.suggestKit(sub);
      case "bot" -> bot.suggest(sub);
      case "npc" -> npc.suggest(sub);
      case "net" -> network.suggest(source, sub);
      case "backup" -> backup.suggest(source, sub);
      case "map" -> suggestMap(args);
      default -> List.of();
    };
  }

  private List<String> suggestMap(String[] args) {
    if (args.length == 3) {
      return ctx.filter(List.of("tntwar", "combat", "edit"), args[2]);
    }
    String[] sub = Arrays.copyOfRange(args, 2, args.length);
    return switch (lower(args[2])) {
      case "tntwar" -> tntWar.suggest(sub);
      case "combat" -> combat.suggest(sub);
      case "edit" -> mapEditor.suggest(sub);
      default -> List.of();
    };
  }
}
