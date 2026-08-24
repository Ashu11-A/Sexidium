package com.sexidium.velocity.adapter;

import com.sexidium.core.platform.CommandSource;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Locale;

/** The proxy console as a core CommandSource. */
public final class VelocityConsoleSource implements CommandSource {

  private final ConsoleCommandSource console;
  private final Locale locale;

  public VelocityConsoleSource(ConsoleCommandSource console, Locale locale) {
    this.console = console;
    this.locale = locale;
  }

  @Override
  public String name() {
    return "CONSOLE";
  }

  @Override
  public Locale locale() {
    return locale;
  }

  @Override
  public boolean hasPermission(String permission) {
    return true;
  }

  @Override
  public void sendMiniMessage(String miniMessage) {
    console.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
  }

  @Override
  public void sendPlainMessage(String message) {
    console.sendMessage(Component.text(message));
  }
}
