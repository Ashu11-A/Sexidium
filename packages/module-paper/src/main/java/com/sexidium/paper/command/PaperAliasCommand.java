package com.sexidium.paper.command;

import com.sexidium.core.command.CoreCommandService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

/**
 * A top-level shortcut command (e.g. {@code /exit}, {@code /menu}, {@code /lobby}, {@code /friend}) that
 * simply prepends its fixed subcommand token and forwards to the shared {@link CoreCommandService}. So
 * {@code /lobby invite Bob} routes exactly like {@code /sx lobby invite Bob}, permissions and all.
 */
public final class PaperAliasCommand implements CommandExecutor, TabCompleter {
  private final CoreCommandService commandService;
  private final String token;

  public PaperAliasCommand(CoreCommandService commandService, String token) {
    this.commandService = commandService;
    this.token = token;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    return commandService.execute(PaperCommandBridge.sourceOf(sender), prepend(args));
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    return commandService.suggest(PaperCommandBridge.sourceOf(sender), prepend(args));
  }

  private String[] prepend(String[] args) {
    String[] full = new String[args.length + 1];
    full[0] = token;
    System.arraycopy(args, 0, full, 1, args.length);
    return full;
  }
}
