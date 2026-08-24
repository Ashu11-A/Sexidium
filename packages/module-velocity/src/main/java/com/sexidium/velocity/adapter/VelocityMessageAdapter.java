package com.sexidium.velocity.adapter;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.MessageAdapter;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Renders core's localized messages on the proxy.
 *
 * <p>Reuses {@link MessageService} unchanged — it needs only a ResourceAdapter, a
 * ConfigurationAdapter and a LoggerAdapter, all of which exist here, so the proxy speaks the same
 * languages from the same lang/*.properties the backends ship.</p>
 */
public final class VelocityMessageAdapter implements MessageAdapter {

  private final ProxyServer proxy;
  private final MessageService messages;

  public VelocityMessageAdapter(ProxyServer proxy, MessageService messages) {
    this.proxy = proxy;
    this.messages = messages;
  }

  public MessageService service() {
    return messages;
  }

  @Override
  public void send(CommandSource commandSource, LocalizedText localizedText) {
    commandSource.sendMiniMessage(messages.renderPrefixedMini(commandSource, localizedText));
  }

  @Override
  public void send(CommandSource commandSource, String miniMessage) {
    commandSource.sendMiniMessage(messages.prefixMiniMessage() + miniMessage);
  }

  @Override
  public void raw(CommandSource commandSource, LocalizedText localizedText) {
    commandSource.sendMiniMessage(messages.renderMini(commandSource, localizedText));
  }

  @Override
  public void raw(CommandSource commandSource, String miniMessage) {
    commandSource.sendMiniMessage(miniMessage);
  }

  @Override
  public void broadcast(LocalizedText localizedText) {
    // Rendered per player: each client gets its own language, which is the whole
    // point of LocalizedText and is lost by rendering once and fanning out.
    proxy.getAllPlayers().forEach(player -> {
      VelocityPlayer wrapped = new VelocityPlayer(player, "proxy");
      wrapped.sendMiniMessage(messages.renderPrefixedMini(wrapped, localizedText));
    });
  }

  @Override
  public void broadcast(String miniMessage) {
    proxy.sendMessage(MiniMessage.miniMessage().deserialize(messages.prefixMiniMessage() + miniMessage));
  }
}
