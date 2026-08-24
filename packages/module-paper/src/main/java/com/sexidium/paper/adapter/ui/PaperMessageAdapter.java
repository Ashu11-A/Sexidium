package com.sexidium.paper.adapter.ui;

import com.sexidium.core.Branding;
import com.sexidium.paper.adapter.command.PaperCommandSource;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.java.JavaPlugin;

public final class PaperMessageAdapter implements MessageAdapter {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private final JavaPlugin plugin;
  private MessageService messageService;

  public PaperMessageAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  public void use(MessageService messageService) {
    this.messageService = messageService;
  }

  MessageService service() {
    if (messageService == null) {
      throw new IllegalStateException("MessageService is not ready.");
    }
    return messageService;
  }

  @Override
  public void send(CommandSource commandSource, LocalizedText localizedText) {
    commandSource.sendMiniMessage(prefixedRender(commandSource, localizedText));
  }

  @Override
  public void send(CommandSource commandSource, String miniMessage) {
    commandSource.sendMiniMessage(prefix() + safeText(miniMessage));
  }

  @Override
  public void raw(CommandSource commandSource, LocalizedText localizedText) {
    commandSource.sendMiniMessage(render(commandSource, localizedText));
  }

  @Override
  public void raw(CommandSource commandSource, String miniMessage) {
    commandSource.sendMiniMessage(safeText(miniMessage));
  }

  @Override
  public void broadcast(LocalizedText localizedText) {
    for (org.bukkit.entity.Player onlinePlayer : plugin.getServer().getOnlinePlayers()) {
      PaperPlayerAdapter adapter = new PaperPlayerAdapter(onlinePlayer);
      send(adapter, localizedText);
    }
    send(new PaperCommandSource(plugin.getServer().getConsoleSender()), localizedText);
  }

  @Override
  public void broadcast(String miniMessage) {
    String payload = prefix() + safeText(miniMessage);
    Component component = MINI_MESSAGE.deserialize(payload);
    plugin.getServer().sendMessage(component);
  }

  private String prefixedRender(CommandSource commandSource, LocalizedText localizedText) {
    return prefix() + render(commandSource, localizedText);
  }

  private String render(CommandSource commandSource, LocalizedText localizedText) {
    if (localizedText == null || localizedText.messageKey() == null) {
      return "";
    }
    if (messageService == null) {
      return localizedText.messageKey().path();
    }
    return messageService.renderMini(commandSource, localizedText);
  }

  private String prefix() {
    if (messageService == null) {
      String configuredLabel = plugin.getConfig() == null ? null
          : plugin.getConfig().getString(Branding.LABEL_PATH, Branding.DEFAULT_LABEL);
      return "<gold>" + Branding.label(configuredLabel) + "</gold> <dark_gray>»</dark_gray> ";
    }
    return messageService.prefixMiniMessage();
  }

  private static String safeText(String raw) {
    return raw == null ? "" : raw;
  }
}
