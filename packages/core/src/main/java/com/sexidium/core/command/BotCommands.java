package com.sexidium.core.command;

import com.sexidium.core.bot.BotManager;
import com.sexidium.core.platform.CommandSource;

import java.util.List;

import static com.sexidium.core.command.CommandText.escape;
import static com.sexidium.core.command.CommandText.lower;

/** Handles {@code /sx admin bot} — Discord bot lifecycle, logs and config reporting. */
final class BotCommands {
  private final CommandContext ctx;

  BotCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  void handle(CommandSource source, String[] args) {
    String action = args.length < 2 ? "status" : lower(args[1]);
    BotManager bot = ctx.core.bot();
    switch (action) {
      case "status" -> sendBotStatus(source, bot.status());
      case "start" -> {
        bot.start();
        ctx.send(source, "<green>Discord bot start requested.</green>");
      }
      case "stop" -> {
        bot.stop();
        ctx.send(source, "<yellow>Discord bot stop requested.</yellow>");
      }
      case "restart" -> {
        bot.restart();
        ctx.send(source, "<green>Discord bot restart requested.</green>");
      }
      case "reload" -> {
        ctx.reloadCallback.run();
        bot.restart();
        ctx.send(source, "<green>Configuration reloaded and Discord bot restarted.</green>");
      }
      case "logs" -> {
        BotManager.BotStatus status = bot.status();
        if (status.recentLogs().isEmpty()) {
          ctx.send(source, "<gray>No recent bot logs.</gray>");
          return;
        }
        ctx.send(source, "<aqua>Recent bot logs:</aqua>");
        for (String line : status.recentLogs()) {
          ctx.send(source, "<gray>-</gray> <white>" + escape(line) + "</white>");
        }
      }
      case "config" -> sendBotConfig(source, bot.status());
      default -> ctx.send(source, "<red>Usage: <white>/sx admin bot [status|start|stop|restart|logs|reload|config]</white></red>");
    }
  }

  private void sendBotStatus(CommandSource source, BotManager.BotStatus status) {
    ctx.send(source, "<aqua>Bot:</aqua> <white>" + (status.running() ? "running" : status.launching() ? "starting" : "stopped") + "</white>"
        + " <gray>enabled=" + status.enabled() + " pid=" + escape(status.pid()) + " exit=" + escape(status.exitCode()) + "</gray>");
    if (!status.lastStartError().isBlank()) {
      ctx.send(source, "<red>Last start error:</red> <white>" + escape(status.lastStartError()) + "</white>");
    }
  }

  private void sendBotConfig(CommandSource source, BotManager.BotStatus status) {
    ctx.send(source, "<aqua>Bot config:</aqua> <gray>token=" + status.tokenState()
        + " apiToken=" + status.apiTokenState() + " guild=" + status.guildIdState() + " adminRole=" + status.adminRoleState() + "</gray>");
    ctx.send(source, "<gray>runtime=" + escape(status.runtimeCommand()) + " download=" + status.useBundledRuntime()
        + " entryExists=" + status.entryExists() + " db=" + escape(status.databasePath()) + "</gray>");
  }

  List<String> suggest(String[] args) {
    return args.length == 2
        ? ctx.filter(List.of("status", "start", "stop", "restart", "logs", "reload", "config"), args[1])
        : List.of();
  }
}
