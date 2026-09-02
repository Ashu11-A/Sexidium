package com.sexidium.paper.adapter.economy;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.economy.EconomyService;
import com.sexidium.core.platform.ConfigurationAdapter;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Registers Sexidium's balances into Bukkit's services manager, so every Vault consumer on the server
 * reads them.
 *
 * <p>The ONLY class {@code PaperSexidiumPlugin} names, and it carries no Vault type in any field or
 * method signature — for the same reason {@link VaultUnlockedLink} carries none. The two classes that
 * do ({@link SexidiumVaultEconomy}, {@link SexidiumLegacyVaultEconomy}) are constructed inside a
 * {@code try} AFTER the linkability gate, so on a server with no VaultUnlocked they are never loaded
 * and their absent supertypes are never resolved.</p>
 *
 * <h2>Every refusal is a DEGRADATION, not a failure</h2>
 * No database, economy off, no VaultUnlocked, an unlinkable VaultUnlocked — each one ends with a log
 * line and a return. Sexidium's own {@code /pay}, {@code /balance} and sidebar keep working in every
 * one of those cases; what is lost is other plugins being able to see the money. A plugin that
 * refused to enable over that would be trading a working server for a working integration.
 */
public final class PaperEconomyBridge {

  private final Plugin plugin;
  private final EconomyService economy;
  private final ConfigurationAdapter configuration;
  private final VaultUnlockedLink link = new VaultUnlockedLink();

  private volatile boolean registeredModern;
  private volatile boolean registeredLegacy;

  public PaperEconomyBridge(Plugin plugin, SexidiumCore core, ConfigurationAdapter configuration) {
    this(plugin, core == null ? null : core.economy(), configuration);
  }

  /**
   * The service directly, so a test can build this against a real {@link EconomyService} without
   * standing up a whole {@link SexidiumCore}. {@code economy} is nullable and means exactly what
   * {@code core.economy()} returning null means: this node has no database.
   */
  PaperEconomyBridge(Plugin plugin, EconomyService economy, ConfigurationAdapter configuration) {
    this.plugin = plugin;
    this.economy = economy;
    this.configuration = configuration;
  }

  /** Whether this node ended up as the server's economy provider. Visible for tests and the summary. */
  public boolean registered() {
    return registeredModern || registeredLegacy;
  }

  public void register() {
    Logger logger = plugin.getLogger();
    if (economy == null) {
      // Same shape as the boot-time capability lines, so one grep for SX-CAPABILIT finds it.
      logger.info("SX-CAPABILITY no=ECONOMY (no database)");
      return;
    }
    if (!economy.enabled()) {
      logger.info("Sexidium's economy is disabled (economy.enabled: false); no Vault provider registered.");
      return;
    }
    link.enabled(configuration.getBoolean("economy.vault.provider", true));
    if (!link.enabled()) {
      logger.info("economy.vault.provider is false; Sexidium's money works in-game but is not exposed"
          + " to other plugins.");
      return;
    }
    if (!link.installed()) {
      logger.info("VaultUnlocked is not installed; Sexidium's money works in-game but no other plugin"
          + " can see it. Install VaultUnlocked if you want shops and jobs to share these balances.");
      return;
    }
    if (!link.modernLinkable()) {
      logger.warning("VaultUnlocked is installed but its economy API does not link on this JVM"
          + " (probably a release built for a newer Java). No economy service was registered; Sexidium's"
          + " own money commands are unaffected.");
      return;
    }
    warnAboutExistingProviders(logger);
    registerProviders(logger, economy, priority());
    if (!registered()) {
      return;
    }
    logger.info("SX-ECONOMY provider=Sexidium vault2=" + registeredModern
        + " legacy=" + registeredLegacy
        + " priority=" + priority().name().toUpperCase(Locale.ROOT)
        + " currency=" + economy.format().currencyId());
  }

  /** Drops every service this plugin registered. Never throws — it runs on the way down. */
  public void unregister() {
    try {
      plugin.getServer().getServicesManager().unregisterAll(plugin);
    } catch (Throwable ignored) {
      // A services manager that is already gone, or a Vault class that unloaded first. Neither is
      // worth a stack trace in a shutdown log, and neither can be acted on.
    }
    registeredModern = false;
    registeredLegacy = false;
  }

  /**
   * Names whoever is already providing an economy, BEFORE we add ours.
   *
   * <p>A second provider is one of the failure modes this repo keeps running into: everything looks
   * registered, half the server reads one set of balances and half reads the other, and nothing in
   * any log says so. One line naming the incumbents turns that into something an operator can see on
   * the boot they caused it.</p>
   */
  private void warnAboutExistingProviders(Logger logger) {
    try {
      List<String> existing = new ArrayList<>();
      for (RegisteredServiceProvider<net.milkbowl.vault2.economy.Economy> registration
          : plugin.getServer().getServicesManager()
              .getRegistrations(net.milkbowl.vault2.economy.Economy.class)) {
        existing.add(registration.getProvider().getName() + " (" + registration.getPlugin().getName() + ")");
      }
      if (!existing.isEmpty()) {
        logger.warning("Another economy provider is already registered: " + existing
            + ". Two providers means some plugins read one set of balances and some read the other."
            + " Set economy.vault.provider to false if the other one is meant to win.");
      }
    } catch (RuntimeException | LinkageError ignored) {
      // A diagnostic that cannot run must never cost the registration it precedes.
    }
  }

  /**
   * The two registrations, each in its own {@code try}.
   *
   * <p>Separate blocks on purpose: the legacy interface is the one most likely to be missing from a
   * future VaultUnlocked, and losing it must not also lose the modern registration that already
   * succeeded.</p>
   */
  private void registerProviders(Logger logger, EconomyService economy, ServicePriority priority) {
    try {
      plugin.getServer().getServicesManager().register(
          net.milkbowl.vault2.economy.Economy.class,
          new SexidiumVaultEconomy(economy), plugin, priority);
      registeredModern = true;
    } catch (RuntimeException | LinkageError failed) {
      logger.warning("Could not register Sexidium as the Vault economy provider: " + failed);
      return;
    }
    if (!configuration.getBoolean("economy.vault.register-legacy", true) || !link.legacyLinkable()) {
      return;
    }
    try {
      plugin.getServer().getServicesManager().register(
          net.milkbowl.vault.economy.Economy.class,
          new SexidiumLegacyVaultEconomy(economy), plugin, priority);
      registeredLegacy = true;
    } catch (RuntimeException | LinkageError failed) {
      logger.warning("Could not register Sexidium's legacy (vault1) economy provider: " + failed
          + ". Modern Vault consumers still work; vault1-only shop plugins will not see the balances.");
    }
  }

  private ServicePriority priority() {
    return switch (configuration.getString("economy.vault.priority", "normal").trim().toLowerCase(Locale.ROOT)) {
      case "lowest" -> ServicePriority.Lowest;
      case "low" -> ServicePriority.Low;
      case "high" -> ServicePriority.High;
      case "highest" -> ServicePriority.Highest;
      default -> ServicePriority.Normal;
    };
  }
}
