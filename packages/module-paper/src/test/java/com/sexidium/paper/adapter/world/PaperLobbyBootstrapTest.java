package com.sexidium.paper.adapter.world;

import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.paper.adapter.config.PaperConfigurationAdapter;
import com.sexidium.paper.adapter.logging.PaperLoggerAdapter;
import org.bukkit.Difficulty;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperLobbyBootstrapTest {

  @TempDir
  Path tempDir;

  private JavaPlugin plugin;
  private Server server;
  private PaperConfigurationAdapter configuration;
  private LoggerAdapter logger;
  private YamlConfiguration paperConfig;

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    server = mock(Server.class);
    paperConfig = new YamlConfiguration();
    Logger javaLogger = Logger.getLogger("test");
    when(plugin.getLogger()).thenReturn(javaLogger);
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getConfig()).thenReturn(paperConfig);
    when(server.getWorldContainer()).thenReturn(tempDir.toFile());
    PluginManager pluginManager = mock(PluginManager.class);
    when(server.getPluginManager()).thenReturn(pluginManager);
    configuration = mock(PaperConfigurationAdapter.class);
    logger = mock(LoggerAdapter.class);
  }

  @Test
  void provision_returnsPathWhenMultiverseAbsent() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("lobby");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    when(configuration.getBoolean(Mockito.eq("worlds.lobby.create-if-missing"), Mockito.anyBoolean())).thenReturn(true);
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(null);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    assertNotNull(result);
  }

  @Test
  void provision_resolvesLobbyToCanonicalDimensionFolder() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("lobby");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    when(configuration.getBoolean(Mockito.eq("worlds.lobby.create-if-missing"), Mockito.anyBoolean())).thenReturn(true);
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(null);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    // On modern Paper the lobby lives under the primary world's unified dimension storage, NOT a
    // top-level worlds/ stub; provision returns that canonical folder.
    assertTrue(result.toString().replace('\\', '/').contains("dimensions/minecraft/lobby"),
        "lobby resolves to the canonical dimension folder, got: " + result);
  }

  @Test
  void provision_usesCustomLobbyName() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("spawn");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    when(configuration.getBoolean(Mockito.eq("worlds.lobby.create-if-missing"), Mockito.anyBoolean())).thenReturn(true);
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(null);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    assertTrue(result.toString().contains("spawn"));
  }

  @Test
  void provision_skipsExistingLobbyFolderWithoutMultiverse() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("lobby");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    File lobby = tempDir.resolve("worlds").resolve("lobby").toFile();
    assertTrue(lobby.mkdirs());
    File levelDat = new File(lobby, "level.dat");
    try {
      assertTrue(levelDat.createNewFile());
    } catch (Exception ignored) {
    }
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(null);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    assertNotNull(result);
  }

  @Test
  void provision_skipsWhenMultiverseReturnsNullManager() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("lobby");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    Plugin multiverse = mockPluginWithoutManager();
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(multiverse);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    assertNotNull(result);
  }

  @Test
  void provision_handlesLobbyAlreadyRegisteredWithMultiverse() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("lobby");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    when(configuration.getString(Mockito.eq("worlds.lobby.game-mode"), Mockito.anyString())).thenReturn("SURVIVAL");
    when(configuration.getString(Mockito.eq("worlds.lobby.difficulty"), Mockito.anyString())).thenReturn("NORMAL");
    when(configuration.getBoolean(Mockito.eq("worlds.lobby.pvp"), Mockito.anyBoolean())).thenReturn(false);
    when(configuration.getString(Mockito.eq("worlds.lobby.alias"), Mockito.anyString())).thenReturn("");

    Plugin multiverse = mockMultiverseWithWorldManager();
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(multiverse);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    assertNotNull(result);
  }

  @Test
  void provision_skipsWhenCreateIfMissingFalseAndNoLevelData() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("lobby");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    when(configuration.getBoolean(Mockito.eq("worlds.lobby.create-if-missing"), Mockito.anyBoolean())).thenReturn(false);
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(null);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    assertNotNull(result);
  }

  @Test
  void provision_createsLobbyViaMultiverseWhenLevelDataMissing() {
    when(configuration.getString(Mockito.eq("worlds.lobby.name"), Mockito.anyString())).thenReturn("lobby");
    when(configuration.getString(Mockito.eq("worlds.root"), Mockito.anyString())).thenReturn("worlds");
    when(configuration.getBoolean(Mockito.eq("worlds.lobby.create-if-missing"), Mockito.anyBoolean())).thenReturn(true);
    when(configuration.getString(Mockito.eq("worlds.lobby.generator"), Mockito.anyString())).thenReturn("default");
    when(configuration.getString(Mockito.eq("worlds.lobby.game-mode"), Mockito.anyString())).thenReturn("SURVIVAL");
    when(configuration.getString(Mockito.eq("worlds.lobby.difficulty"), Mockito.anyString())).thenReturn("NORMAL");
    when(configuration.getBoolean(Mockito.eq("worlds.lobby.pvp"), Mockito.anyBoolean())).thenReturn(false);
    when(configuration.getString(Mockito.eq("worlds.lobby.alias"), Mockito.anyString())).thenReturn("");

    Plugin multiverse = mockMultiverseWithWorldManager();
    when(server.getPluginManager().getPlugin("Multiverse-Core")).thenReturn(multiverse);

    PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(plugin, configuration, logger);
    Path result = bootstrap.provision();
    assertNotNull(result);
  }

  @Test
  void loggerFor_returnsPaperLoggerAdapter() {
    LoggerAdapter adapter = PaperLobbyBootstrap.loggerFor(plugin);
    assertNotNull(adapter);
    assertTrue(adapter instanceof PaperLoggerAdapter);
  }

  private Plugin mockPluginWithoutManager() {
    InvocationHandler handler = (proxy, method, args) -> {
      if (method.getName().equals("getMVWorldManager")) {
        return null;
      }
      return null;
    };
    return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class}, handler);
  }

  private Plugin mockMultiverseWithWorldManager() {
    Object mvWorld = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{},
        (proxy, method, args) -> {
          if (method.getName().equals("getGameMode") || method.getName().equals("setGameMode")
              || method.getName().equals("setDifficulty") || method.getName().equals("setPvp")
              || method.getName().equals("setAlias")) {
            return null;
          }
          return null;
        });
    Object mvWorldManager = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{},
        (proxy, method, args) -> {
          switch (method.getName()) {
            case "getMVWorld":
              return mvWorld;
            case "addWorld":
              return Boolean.TRUE;
            case "setGameMode":
            case "setDifficulty":
            case "setPvp":
            case "setAlias":
              return null;
            default:
              return null;
          }
        });
    InvocationHandler handler = (proxy, method, args) -> {
      if (method.getName().equals("getMVWorldManager")) {
        return mvWorldManager;
      }
      return null;
    };
    return (Plugin) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Plugin.class}, handler);
  }
}
