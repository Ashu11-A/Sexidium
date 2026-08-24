package com.sexidium.paper.adapter.command;

import com.sexidium.core.platform.CommandDispatcherAdapter;
import org.bukkit.Bukkit;

public final class PaperCommandDispatcherAdapter implements CommandDispatcherAdapter {
  @Override
  public void dispatchFromConsole(String commandLine) {
    String cleanedCommand = commandLine == null ? "" : commandLine.trim();
    if (cleanedCommand.startsWith("/")) {
      cleanedCommand = cleanedCommand.substring(1);
    }
    if (!cleanedCommand.isBlank()) {
      Bukkit.getServer().dispatchCommand(Bukkit.getServer().getConsoleSender(), cleanedCommand);
    }
  }
}
