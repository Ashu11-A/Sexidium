package com.sexidium.core.command;

import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.Game;
import com.sexidium.core.game.modes.minigames.RaceGame;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;

import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.lower;

/** Handles {@code /sx race} — in-match Race actions (overtime vote, live team switch). */
final class RaceCommands {
  private final CommandContext ctx;

  RaceCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  void handle(CommandSource source, String[] args) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    ActiveMatch match = ctx.core.games().matchOf(player);
    Game game = match == null ? null : match.game();
    if (!(game instanceof RaceGame race)) {
      ctx.send(source, MessageKey.RACE_USAGE);
      return;
    }
    if (args.length < 2) {
      ctx.send(source, MessageKey.RACE_USAGE);
      return;
    }
    switch (lower(args[1])) {
      case "vote" -> {
        if (args.length < 3) {
          ctx.send(source, MessageKey.RACE_USAGE);
          return;
        }
        race.castOvertimeVote(player, equalsAny(args[2], "yes", "y", "true", "sim"));
      }
      case "switch" -> {
        if (args.length < 3) {
          ctx.send(source, MessageKey.RACE_USAGE);
          return;
        }
        race.switchTeam(player, args[2]);
      }
      case "allow" -> race.setSwitchAllowed(player, args.length < 3 || equalsAny(args[2], "on", "yes", "true", "open"));
      default -> ctx.send(source, MessageKey.RACE_USAGE);
    }
  }

  List<String> suggest(String[] args) {
    if (args.length == 2) {
      return ctx.filter(List.of("vote", "switch", "allow"), args[1]);
    }
    if (args.length == 3 && equalsAny(args[1], "vote")) {
      return ctx.filter(List.of("yes", "no"), args[2]);
    }
    if (args.length == 3 && equalsAny(args[1], "allow")) {
      return ctx.filter(List.of("on", "off"), args[2]);
    }
    return List.of();
  }
}
