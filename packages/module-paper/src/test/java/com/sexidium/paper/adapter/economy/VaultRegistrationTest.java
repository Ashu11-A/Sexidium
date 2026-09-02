package com.sexidium.paper.adapter.economy;

import com.sexidium.core.economy.CurrencyFormat;
import com.sexidium.core.economy.EconomyService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether Sexidium ends up as the server's economy provider, and — far more importantly — whether it
 * fails softly when it should not.
 *
 * <p>Every refusal path here is a DEGRADATION and must never throw: a plugin that refuses to enable
 * because VaultUnlocked is missing has traded a working server for a missing integration. The
 * {@code assertDoesNotThrow} calls are the point of the test, not decoration around it.</p>
 */
class VaultRegistrationTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private Database database;
  private PropertiesConfigurationAdapter configuration;
  private EconomyService economy;
  private Plugin plugin;
  private ServicesManager services;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(tmp.resolve("economy.db").toFile());
    configuration = new PropertiesConfigurationAdapter();
    economy = new EconomyService(configuration, SILENT, database,
        new CurrencyFormat(configuration), "test-node");

    services = mock(ServicesManager.class);
    Server server = mock(Server.class);
    when(server.getServicesManager()).thenReturn(services);
    plugin = mock(Plugin.class);
    when(plugin.getServer()).thenReturn(server);
    when(plugin.getLogger()).thenReturn(Logger.getLogger("VaultRegistrationTest"));
  }

  @AfterEach
  void tearDown() {
    if (economy != null) {
      economy.shutdown();
    }
    if (database != null) {
      database.close();
    }
  }

  /** Pretends VaultUnlocked is installed. Its plugin.yml name is literally {@code Vault}. */
  private MockedStatic<Bukkit> withVaultInstalled() {
    MockedStatic<Bukkit> bukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS);
    PluginManager pluginManager = mock(PluginManager.class);
    when(pluginManager.getPlugin(VaultUnlockedLink.PLUGIN_NAME)).thenReturn(mock(Plugin.class));
    bukkit.when(Bukkit::getPluginManager).thenReturn(pluginManager);
    return bukkit;
  }

  @Test
  @DisplayName("no database: nothing is registered and nothing throws")
  void noDatabase_registersNothing() {
    PaperEconomyBridge bridge = new PaperEconomyBridge(plugin, (EconomyService) null, configuration);
    assertDoesNotThrow(bridge::register);
    assertFalse(bridge.registered());
    verify(services, never()).register(any(), any(), any(), any());
  }

  @Test
  @DisplayName("economy.enabled: false — the operator's own switch, honoured before anything else")
  void economyDisabled_registersNothing() {
    configuration.set("economy.enabled", "false");
    PaperEconomyBridge bridge = new PaperEconomyBridge(plugin, economy, configuration);
    assertDoesNotThrow(bridge::register);
    assertFalse(bridge.registered());
    verify(services, never()).register(any(), any(), any(), any());
  }

  @Test
  void vaultProviderFalse_registersNothing() {
    configuration.set("economy.vault.provider", "false");
    try (MockedStatic<Bukkit> ignored = withVaultInstalled()) {
      PaperEconomyBridge bridge = new PaperEconomyBridge(plugin, economy, configuration);
      assertDoesNotThrow(bridge::register);
      assertFalse(bridge.registered());
    }
    verify(services, never()).register(any(), any(), any(), any());
  }

  @Test
  @DisplayName("VaultUnlocked absent: money still works, nothing is registered, nothing throws")
  void vaultAbsent_registersNothingAndDoesNotThrow() {
    // No Bukkit server at all, which is what a probe on a plugin-less environment sees. The link's
    // catch of RuntimeException | LinkageError is what turns that into "no", and this is the assertion
    // that keeps it there.
    PaperEconomyBridge bridge = new PaperEconomyBridge(plugin, economy, configuration);
    assertDoesNotThrow(bridge::register);
    assertFalse(bridge.registered());
    verify(services, never()).register(any(), any(), any(), any());
    // And the economy itself is untouched by any of it.
    assertTrue(economy.enabled());
  }

  @Test
  @DisplayName("happy path: exactly one vault2 registration and one legacy registration")
  void happyPath_registersBothInterfaces() {
    try (MockedStatic<Bukkit> ignored = withVaultInstalled()) {
      PaperEconomyBridge bridge = new PaperEconomyBridge(plugin, economy, configuration);
      bridge.register();
      assertTrue(bridge.registered());
    }
    verify(services).register(eq(net.milkbowl.vault2.economy.Economy.class),
        any(net.milkbowl.vault2.economy.Economy.class), eq(plugin), eq(ServicePriority.Normal));
    // BOTH, because VaultUnlocked does not bridge vault2 back to vault1 and most shop plugins still
    // ask for vault1 alone -- registering only the modern one is an economy no shop can see.
    verify(services).register(eq(net.milkbowl.vault.economy.Economy.class),
        any(net.milkbowl.vault.economy.Economy.class), eq(plugin), eq(ServicePriority.Normal));
  }

  @Test
  void registerLegacyFalse_registersOnlyTheModernInterface() {
    configuration.set("economy.vault.register-legacy", "false");
    try (MockedStatic<Bukkit> ignored = withVaultInstalled()) {
      new PaperEconomyBridge(plugin, economy, configuration).register();
    }
    verify(services).register(eq(net.milkbowl.vault2.economy.Economy.class), any(), eq(plugin), any());
    verify(services, never()).register(eq(net.milkbowl.vault.economy.Economy.class), any(), any(), any());
  }

  @Test
  void priority_comesFromTheConfig() {
    configuration.set("economy.vault.priority", "highest");
    try (MockedStatic<Bukkit> ignored = withVaultInstalled()) {
      new PaperEconomyBridge(plugin, economy, configuration).register();
    }
    verify(services).register(eq(net.milkbowl.vault2.economy.Economy.class), any(), eq(plugin),
        eq(ServicePriority.Highest));
  }

  @Test
  @DisplayName("unregister never throws, even with no services manager behind it")
  void unregister_isAlwaysSafe() {
    Plugin broken = mock(Plugin.class);
    when(broken.getServer()).thenThrow(new IllegalStateException("no server"));
    PaperEconomyBridge bridge = new PaperEconomyBridge(broken, economy, configuration);
    assertDoesNotThrow(bridge::unregister);
    assertFalse(bridge.registered());
  }
}
