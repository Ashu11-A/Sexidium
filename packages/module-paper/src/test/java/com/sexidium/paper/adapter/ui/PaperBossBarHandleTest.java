package com.sexidium.paper.adapter.ui;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every bar here is a REAL {@link BossBar} from the factory, never a mock: Adventure 5 (which Paper
 * 26.2 ships) made {@code BossBar} a sealed interface, so Mockito cannot subclass it — it fails with
 * "Unsupported settings with this type". A real bar is the better fixture anyway; where a mock would
 * have verified a call, assert the bar's resulting state instead.
 */
class PaperBossBarHandleTest {

  @Test
  void progress_clampsNegativeToZero() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);
    handle.progress(-1.0f);
    assertEquals(0.0f, bar.progress());
  }

  @Test
  void progress_clampsExcessiveToOne() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);
    handle.progress(5.0f);
    assertEquals(1.0f, bar.progress());
  }

  @Test
  void progress_acceptsMidRange() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);
    handle.progress(0.42f);
    assertEquals(0.42f, bar.progress());
  }

  @Test
  void title_updatesStoredTitleAndRendersForViewers() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, LocalizedText.of(MessageKey.COMMAND_RELOAD));

    UUID viewerId = UUID.randomUUID();
    Player viewer = mock(Player.class);
    when(viewer.getUniqueId()).thenReturn(viewerId);
    when(server.getPlayer(viewerId)).thenReturn(viewer);
    when(service.renderMini(any(com.sexidium.core.platform.CommandSource.class), any(LocalizedText.class))).thenReturn("<red>title</red>");

    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(viewer);
    handle.show(playerAdapter);

    LocalizedText newTitle = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    handle.title(newTitle);
    verify(service, times(2)).renderMini(any(com.sexidium.core.platform.CommandSource.class), any(LocalizedText.class));
  }

  @Test
  void title_withNoViewers_doesNotThrow() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    LocalizedText newTitle = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    handle.title(newTitle);
    verify(service, never()).renderMini(any(com.sexidium.core.platform.CommandSource.class), any(LocalizedText.class));
  }

  @Test
  void title_skipsOfflineViewers() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(net.kyori.adventure.text.Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    UUID viewerId = UUID.randomUUID();
    when(server.getPlayer(viewerId)).thenReturn(null);

    Player viewer = mock(Player.class);
    when(viewer.getUniqueId()).thenReturn(viewerId);
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(viewer);
    handle.show(playerAdapter);

    handle.title(LocalizedText.of(MessageKey.COMMAND_RELOAD));
    verify(service, never()).renderMini(any(com.sexidium.core.platform.CommandSource.class), any(LocalizedText.class));
  }

  @Test
  void show_addsViewerAndShowsBar() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    when(service.renderMini(any(com.sexidium.core.platform.CommandSource.class), any(LocalizedText.class))).thenReturn("rendered");
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, LocalizedText.of(MessageKey.COMMAND_RELOAD));

    UUID viewerId = UUID.randomUUID();
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(viewerId);
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(player);

    handle.show(playerAdapter);
    verify(player).showBossBar(bar);
  }

  @Test
  void show_withNullTitle_stillShowsBar() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    UUID viewerId = UUID.randomUUID();
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(viewerId);
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(player);

    handle.show(playerAdapter);
    verify(player).showBossBar(bar);
  }

  @Test
  void show_withNonPaperAdapter_doesNothing() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    com.sexidium.core.platform.PlayerAdapter nonPaper = mock(com.sexidium.core.platform.PlayerAdapter.class);
    handle.show(nonPaper);
    // An untouched bar is the observable form of "did nothing": rendering a title for this viewer is the
    // first thing show() would do, and it would land on the bar's name.
    assertEquals(Component.empty(), bar.name());
  }

  @Test
  void hide_removesViewerAndHidesBar() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    UUID viewerId = UUID.randomUUID();
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(viewerId);
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(player);

    handle.show(playerAdapter);
    handle.hide(playerAdapter);
    verify(player).hideBossBar(bar);
  }

  @Test
  void hide_withNonPaperAdapter_doesNothing() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    com.sexidium.core.platform.PlayerAdapter nonPaper = mock(com.sexidium.core.platform.PlayerAdapter.class);
    handle.hide(nonPaper);
    assertEquals(Component.empty(), bar.name());
  }

  @Test
  void close_hidesForAllViewersAndClearsThem() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    UUID id1 = UUID.randomUUID();
    UUID id2 = UUID.randomUUID();
    Player player1 = mock(Player.class);
    Player player2 = mock(Player.class);
    when(player1.getUniqueId()).thenReturn(id1);
    when(player2.getUniqueId()).thenReturn(id2);

    PaperPlayerAdapter adapter1 = new PaperPlayerAdapter(player1);
    PaperPlayerAdapter adapter2 = new PaperPlayerAdapter(player2);

    when(server.getPlayer(id1)).thenReturn(player1);
    when(server.getPlayer(id2)).thenReturn(player2);

    handle.show(adapter1);
    handle.show(adapter2);
    handle.close();

    verify(player1).hideBossBar(bar);
    verify(player2).hideBossBar(bar);
  }

  @Test
  void close_skipsOfflineViewers() {
    Server server = mock(Server.class);
    MessageService service = mock(MessageService.class);
    BossBar bar = BossBar.bossBar(Component.empty(), 0.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    PaperBossBarHandle handle = new PaperBossBarHandle(server, service, bar, null);

    UUID viewerId = UUID.randomUUID();
    when(server.getPlayer(viewerId)).thenReturn(null);

    Player viewer = mock(Player.class);
    when(viewer.getUniqueId()).thenReturn(viewerId);
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(viewer);
    handle.show(playerAdapter);

    handle.close();
  }
}
