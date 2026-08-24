package com.sexidium.paper.command;

import com.sexidium.paper.adapter.command.PaperCommandSource;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.core.command.CoreCommandService;
import com.sexidium.core.platform.CommandSource;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

/** Bridges the {@code /sexidium} (alias {@code /sx}) root command to the shared {@link CoreCommandService}. */
public final class PaperCommandBridge implements CommandExecutor, TabCompleter {
  private final CoreCommandService commandService;

  public PaperCommandBridge(CoreCommandService commandService) {
    this.commandService = commandService;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    return commandService.execute(sourceOf(sender), args);
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    return commandService.suggest(sourceOf(sender), args);
  }

  /** Shared sender→{@link CommandSource} mapping, reused by {@link PaperAliasCommand}. */
  static CommandSource sourceOf(CommandSender sender) {
    return sender instanceof Player player ? new PaperPlayerAdapter(player) : new PaperCommandSource(sender);
  }
}
