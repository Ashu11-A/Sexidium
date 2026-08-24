package com.sexidium.paper.adapter.ui;

import com.sexidium.core.i18n.Language;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperMessageAdapterTest {

  private JavaPlugin plugin;
  private Server server;
  private PaperMessageAdapter adapter;
  private final List<String> received = new ArrayList<>();
  private final CommandSource capturingSource = new CommandSource() {
    @Override
    public String name() { return "test"; }
    @Override
    public Locale locale() { return Locale.ENGLISH; }
    @Override
    public boolean hasPermission(String p) { return true; }
    @Override
    public boolean playerSource() { return false; }
    @Override
    public void sendMiniMessage(String m) { received.add(m); }
    @Override
    public void sendPlainMessage(String m) { received.add(m); }
  };

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    server = mock(Server.class);
    when(plugin.getServer()).thenReturn(server);
    adapter = new PaperMessageAdapter(plugin);
    received.clear();
  }

  @AfterEach
  void tearDown() {
  }

  @Test
  void send_withLocalizedText_usesDefaultPrefixWhenServiceNotReady() {
    adapter.send(capturingSource, LocalizedText.of(MessageKey.COMMAND_RELOAD));
    assertEquals(1, received.size());
    assertTrue(received.get(0).startsWith("<gold>Sexidium</gold> <dark_gray>»</dark_gray> "));
    assertTrue(received.get(0).contains(MessageKey.COMMAND_RELOAD.path()));
  }

  @Test
  void send_withString_usesDefaultPrefixWhenServiceNotReady() {
    adapter.send(capturingSource, "hello");
    assertEquals(1, received.size());
    assertEquals("<gold>Sexidium</gold> <dark_gray>»</dark_gray> hello", received.get(0));
  }

  @Test
  void send_withString_usesConfiguredBrandWhenServiceNotReady() {
    FileConfiguration config = mock(FileConfiguration.class);
    when(plugin.getConfig()).thenReturn(config);
    when(config.getString("branding.label", "Sexidium")).thenReturn("Ashu");

    adapter.send(capturingSource, "hello");

    assertEquals("<gold>Ashu</gold> <dark_gray>»</dark_gray> hello", received.get(0));
  }

  @Test
  void send_withNullString_usesDefaultPrefixWithEmpty() {
    adapter.send(capturingSource, (String) null);
    assertEquals(1, received.size());
    assertEquals("<gold>Sexidium</gold> <dark_gray>»</dark_gray> ", received.get(0));
  }

  @Test
  void raw_withLocalizedText_doesNotPrefixWhenServiceNotReady() {
    adapter.raw(capturingSource, LocalizedText.of(MessageKey.COMMAND_RELOAD));
    assertEquals(1, received.size());
    assertEquals(MessageKey.COMMAND_RELOAD.path(), received.get(0));
  }

  @Test
  void raw_withString_doesNotPrefix() {
    adapter.raw(capturingSource, "raw text");
    assertEquals(1, received.size());
    assertEquals("raw text", received.get(0));
  }

  @Test
  void raw_withNullString_sendsEmpty() {
    adapter.raw(capturingSource, (String) null);
    assertEquals(1, received.size());
    assertEquals("", received.get(0));
  }

  @Test
  void raw_withNullLocalizedText_sendsEmpty() {
    adapter.raw(capturingSource, (LocalizedText) null);
    assertEquals(1, received.size());
    assertEquals("", received.get(0));
  }

  @Test
  void raw_withNullMessageKey_sendsEmpty() {
    adapter.raw(capturingSource, new LocalizedText(null, List.of()));
    assertEquals(1, received.size());
    assertEquals("", received.get(0));
  }

  @Test
  void service_whenNotSet_throwsIllegalState() {
    assertThrows(IllegalStateException.class, () -> adapter.service());
  }

  @Test
  void service_afterUse_returnsInstance() {
    MessageService service = mock(MessageService.class);
    adapter.use(service);
    assertSame(service, adapter.service());
  }

  @Test
  void use_overridesService() {
    MessageService first = mock(MessageService.class);
    MessageService second = mock(MessageService.class);
    adapter.use(first);
    adapter.use(second);
    assertSame(second, adapter.service());
  }

  @Test
  void send_withServiceReady_usesServicePrefix() {
    MessageService service = mock(MessageService.class);
    when(service.prefixMiniMessage()).thenReturn("[Sexidium] ");
    when(service.renderMini(any(CommandSource.class), any(LocalizedText.class)))
        .thenReturn("rendered-content");
    adapter.use(service);

    adapter.send(capturingSource, LocalizedText.of(MessageKey.COMMAND_RELOAD));
    assertEquals(1, received.size());
    assertEquals("[Sexidium] rendered-content", received.get(0));
  }

  @Test
  void send_withServiceReady_passesCorrectArgs() {
    MessageService service = mock(MessageService.class);
    when(service.prefixMiniMessage()).thenReturn("PREFIX ");
    when(service.renderMini(any(CommandSource.class), any(LocalizedText.class))).thenReturn("CONTENT");
    adapter.use(service);

    LocalizedText text = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    adapter.send(capturingSource, text);
    verify(service).renderMini(capturingSource, text);
  }

  @Test
  void broadcast_withLocalizedText_sendsToOnlinePlayersAndConsole() {
    Player player1 = mock(Player.class);
    Player player2 = mock(Player.class);
    ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    java.util.List<Player> onlinePlayers = new java.util.ArrayList<>();
    onlinePlayers.add(player1);
    onlinePlayers.add(player2);
    Mockito.<java.util.Collection<? extends Player>>when(server.getOnlinePlayers()).thenReturn(onlinePlayers);
    when(server.getConsoleSender()).thenReturn(console);

    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.broadcast(LocalizedText.of(MessageKey.COMMAND_RELOAD));
    }

    verify(player1).sendMessage(any(net.kyori.adventure.text.Component.class));
    verify(player2).sendMessage(any(net.kyori.adventure.text.Component.class));
    verify(console).sendMessage(any(net.kyori.adventure.text.Component.class));
  }

  @Test
  void broadcast_withString_sendsViaServerSendMessage() {
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.broadcast("hello world");
      verify(server).sendMessage(any());
    }
  }

  @Test
  void broadcast_withNullString_sendsEmpty() {
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.broadcast((String) null);
      verify(server).sendMessage(any());
    }
  }

  @Test
  void broadcast_withServiceReady_usesServicePrefix() {
    MessageService service = mock(MessageService.class);
    when(service.prefixMiniMessage()).thenReturn("[S] ");
    when(service.renderMini(any(CommandSource.class), any(LocalizedText.class))).thenReturn("rendered");
    adapter.use(service);

    Player player = mock(Player.class);
    ConsoleCommandSender console = mock(ConsoleCommandSender.class);
    java.util.List<Player> onlinePlayers = new java.util.ArrayList<>();
    onlinePlayers.add(player);
    Mockito.<java.util.Collection<? extends Player>>when(server.getOnlinePlayers()).thenReturn(onlinePlayers);
    when(server.getConsoleSender()).thenReturn(console);

    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.broadcast(LocalizedText.of(MessageKey.COMMAND_RELOAD));
    }

    verify(player).sendMessage(any(net.kyori.adventure.text.Component.class));
    verify(console).sendMessage(any(net.kyori.adventure.text.Component.class));
  }

  @Test
  void send_doesNotInvokeServerWhenNotBroadcasting() {
    adapter.send(capturingSource, "hi");
    verify(server, never()).sendMessage(any());
  }
}
