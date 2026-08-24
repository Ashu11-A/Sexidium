package com.sexidium.paper.adapter.command;

import com.sexidium.core.platform.CommandSource;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

import java.util.Locale;

public class PaperCommandSource implements CommandSource {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  protected final CommandSender sender;

  public PaperCommandSource(CommandSender sender) {
    this.sender = sender;
  }

  @Override
  public String name() {
    return sender.getName();
  }

  @Override
  public Locale locale() {
    return Locale.ENGLISH;
  }

  @Override
  public boolean hasPermission(String permission) {
    return sender.hasPermission(permission);
  }

  @Override
  public void sendMiniMessage(String miniMessage) {
    String rendered = miniMessage == null ? "" : miniMessage;
    sender.sendMessage(MINI_MESSAGE.deserialize(rendered));
  }

  @Override
  public void sendPlainMessage(String message) {
    sender.sendPlainMessage(message == null ? "" : message);
  }
}
