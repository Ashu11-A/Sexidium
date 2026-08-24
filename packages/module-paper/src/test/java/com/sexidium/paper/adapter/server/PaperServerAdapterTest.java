package com.sexidium.paper.adapter.server;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.paper.adapter.command.PaperCommandDispatcherAdapter;
import com.sexidium.paper.adapter.command.PaperCommandSource;
import com.sexidium.paper.adapter.config.PaperConfigurationAdapter;
import com.sexidium.paper.adapter.event.PaperEventDispatcherAdapter;
import com.sexidium.paper.adapter.ui.PaperMessageAdapter;
import com.sexidium.paper.adapter.ui.PaperUiAdapter;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperServerAdapterTest {

  private JavaPlugin plugin;
  private PaperConfigurationAdapter configurationAdapter;
  private PaperMessageAdapter messageAdapter;
  private PaperServerAdapter adapter;
  private MockedStatic<Bukkit> mockedBukkit;
  private Server server;

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    configurationAdapter = mock(PaperConfigurationAdapter.class);
    messageAdapter = mock(PaperMessageAdapter.class);
    adapter = new PaperServerAdapter(plugin, configurationAdapter, messageAdapter);
    server = mock(Server.class);
    mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS);
    mockedBukkit.when(Bukkit::getServer).thenReturn(server);
  }

  @AfterEach
  void tearDown() {
    mockedBukkit.close();
  }

  @Test
  void serverName_returnsBukkitServerName() {
    when(server.getName()).thenReturn("PaperServer");
    assertEquals("PaperServer", adapter.serverName());
  }

  @Test
  void platformType_returnsBukkitForPaper() {
    when(server.getName()).thenReturn("PaperServer");
    assertEquals(PlatformType.BUKKIT, adapter.platformType());
  }

  @Test
  void platformType_returnsHybridForMohist() {
    when(server.getName()).thenReturn("Mohist");
    assertEquals(PlatformType.HYBRID, adapter.platformType());
  }

  @Test
  void platformType_returnsHybridForArclight() {
    when(server.getName()).thenReturn("Arclight");
    assertEquals(PlatformType.HYBRID, adapter.platformType());
  }

  @Test
  void platformType_returnsHybridForMagma() {
    when(server.getName()).thenReturn("Magma");
    assertEquals(PlatformType.HYBRID, adapter.platformType());
  }

  @Test
  void dataDirectory_returnsPluginDataFolderPath() {
    java.io.File folder = new java.io.File("/tmp/fake-plugin-data");
    when(plugin.getDataFolder()).thenReturn(folder);
    assertEquals(folder.toPath(), adapter.dataDirectory());
  }

  @Test
  void configuration_returnsProvidedAdapter() {
    assertSame(configurationAdapter, adapter.configuration());
  }

  @Test
  void logger_isNonNull() {
    assertNotNull(adapter.logger());
  }

  @Test
  void resources_isNonNull() {
    assertNotNull(adapter.resources());
  }

  @Test
  void scheduler_isNonNull() {
    assertNotNull(adapter.scheduler());
  }

  @Test
  void ui_isNonNull() {
    assertNotNull(adapter.ui());
  }

  @Test
  void menus_returnsSharedAdapterInstance() {
    com.sexidium.paper.adapter.menu.PaperMenuAdapter menuAdapter =
        new com.sexidium.paper.adapter.menu.PaperMenuAdapter();
    PaperServerAdapter withMenu =
        new PaperServerAdapter(plugin, configurationAdapter, messageAdapter, menuAdapter);
    assertSame(menuAdapter, withMenu.menus());
  }

  @Test
  void messages_returnsProvidedAdapter() {
    assertSame(messageAdapter, adapter.messages());
  }

  @Test
  void events_returnsNewDispatcher() {
    assertNotNull(adapter.events());
    assertNotNull(adapter.events());
  }

  @Test
  void commands_returnsNewDispatcher() {
    assertNotNull(adapter.commands());
    assertNotNull(adapter.commands());
  }

  @Test
  void worlds_returnsService() {
    assertNotNull(adapter.worlds());
  }

  @Test
  void console_returnsPaperCommandSource() {
    ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
    when(server.getConsoleSender()).thenReturn(sender);
    PaperCommandSource source = (PaperCommandSource) adapter.console();
    assertNotNull(source);
  }

  @Test
  void onlinePlayers_mapsAllPlayersToAdapters() {
    Player player1 = mock(Player.class);
    Player player2 = mock(Player.class);
    java.util.Collection<Player> players = java.util.Arrays.asList(player1, player2);
    when(server.getOnlinePlayers()).thenReturn((java.util.Collection) players);
    List<PlayerAdapter> result = (List) adapter.onlinePlayers();
    assertEquals(2, result.size());
  }

  @Test
  void player_returnsAdapterWhenFound() {
    UUID id = UUID.randomUUID();
    Player player = mock(Player.class);
    when(server.getPlayer(id)).thenReturn(player);
    Optional<PlayerAdapter> result = adapter.player(id);
    assertTrue(result.isPresent());
    assertSame(player, ((PaperPlayerAdapter) result.get()).handle());
  }

  @Test
  void player_returnsEmptyWhenNotFound() {
    UUID id = UUID.randomUUID();
    when(server.getPlayer(id)).thenReturn(null);
    Optional<PlayerAdapter> result = adapter.player(id);
    assertTrue(result.isEmpty());
  }

  @Test
  void playerExact_returnsAdapterWhenFound() {
    Player player = mock(Player.class);
    when(server.getPlayerExact("alice")).thenReturn(player);
    Optional<PlayerAdapter> result = adapter.playerExact("alice");
    assertTrue(result.isPresent());
  }

  @Test
  void playerExact_returnsEmptyWhenNotFound() {
    when(server.getPlayerExact("ghost")).thenReturn(null);
    Optional<PlayerAdapter> result = adapter.playerExact("ghost");
    assertTrue(result.isEmpty());
  }
}
