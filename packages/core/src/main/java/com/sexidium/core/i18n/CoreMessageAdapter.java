package com.sexidium.core.i18n;

import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;

public final class CoreMessageAdapter implements MessageAdapter {
  private final ServerAdapter serverAdapter;
  private final MessageService messageService;

  public CoreMessageAdapter(ServerAdapter serverAdapter, MessageService messageService) {
    this.serverAdapter = serverAdapter;
    this.messageService = messageService;
  }

  @Override
  public void send(CommandSource commandSource, LocalizedText localizedText) {
    commandSource.sendMiniMessage(messageService.renderPrefixedMini(commandSource, localizedText));
  }

  @Override
  public void send(CommandSource commandSource, String miniMessage) {
    commandSource.sendMiniMessage(messageService.prefixMiniMessage() + (miniMessage == null ? "" : miniMessage));
  }

  @Override
  public void raw(CommandSource commandSource, LocalizedText localizedText) {
    commandSource.sendMiniMessage(messageService.renderMini(commandSource, localizedText));
  }

  @Override
  public void raw(CommandSource commandSource, String miniMessage) {
    commandSource.sendMiniMessage(miniMessage == null ? "" : miniMessage);
  }

  @Override
  public void broadcast(LocalizedText localizedText) {
    for (PlayerAdapter playerAdapter : serverAdapter.onlinePlayers()) {
      send(playerAdapter, localizedText);
    }
    send(serverAdapter.console(), localizedText);
  }

  @Override
  public void broadcast(String miniMessage) {
    for (PlayerAdapter playerAdapter : serverAdapter.onlinePlayers()) {
      send(playerAdapter, miniMessage);
    }
    send(serverAdapter.console(), miniMessage);
  }
}
