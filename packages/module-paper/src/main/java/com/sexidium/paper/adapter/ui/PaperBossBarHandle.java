package com.sexidium.paper.adapter.ui;

import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.PlayerAdapter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class PaperBossBarHandle implements BossBarHandle {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private final Server server;
  private final MessageService messageService;
  private final BossBar bossBar;
  private final Set<UUID> viewers = new LinkedHashSet<>();
  private LocalizedText title;

  public PaperBossBarHandle(Server server, MessageService messageService, BossBar bossBar, LocalizedText title) {
    this.server = server;
    this.messageService = messageService;
    this.bossBar = bossBar;
    this.title = title;
  }

  @Override
  public void title(LocalizedText localizedText) {
    title = localizedText;
    for (UUID viewerId : viewers) {
      Player player = server.getPlayer(viewerId);
      if (player != null) {
        String renderedMini = messageService.renderMini(new PaperPlayerAdapter(player), localizedText);
        bossBar.name(MINI_MESSAGE.deserialize(renderedMini));
      }
    }
  }

  @Override
  public void progress(float progress) {
    bossBar.progress(Math.max(0.0f, Math.min(1.0f, progress)));
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
    if (playerAdapter instanceof PaperPlayerAdapter paperAdapter) {
      viewers.add(paperAdapter.uniqueId());
      if (title != null) {
        bossBar.name(MINI_MESSAGE.deserialize(messageService.renderMini(playerAdapter, title)));
      }
      paperAdapter.handle().showBossBar(bossBar);
    }
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
    if (playerAdapter instanceof PaperPlayerAdapter paperAdapter) {
      viewers.remove(paperAdapter.uniqueId());
      paperAdapter.handle().hideBossBar(bossBar);
    }
  }

  @Override
  public void close() {
    for (UUID viewerId : Set.copyOf(viewers)) {
      Player player = server.getPlayer(viewerId);
      if (player != null) {
        player.hideBossBar(bossBar);
      }
    }
    viewers.clear();
  }
}
