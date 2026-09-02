package com.sexidium.paper.adapter.economy;

import com.sexidium.paper.adapter.util.PlatformProbes;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Decides whether this server can be handed an Economy provider at all — and never names a Vault type
 * while deciding it.
 *
 * <p>That restriction is the whole design, and it is the same one {@code BetterHudLink} lives under: a
 * field or a method signature of a Vault type resolves when THIS class loads, which is before any
 * check has run, so a server without VaultUnlocked would fail on the gate rather than at it. Every
 * reference here is a string constant, and the classes that genuinely name Vault types
 * ({@link SexidiumVaultEconomy}, {@link SexidiumLegacyVaultEconomy}) are only ever constructed from
 * behind {@link #modernLinkable()} / {@link #legacyLinkable()}.</p>
 *
 * <h2>Why the plugin name is "Vault"</h2>
 * VaultUnlocked's own {@code plugin.yml} declares {@code name: Vault} — it is a drop-in replacement,
 * so it takes the original's name. Asking the plugin manager for "VaultUnlocked" finds nothing on a
 * server that has it installed. Its {@code load: STARTUP} also means it is up before our
 * {@code onEnable}, which is why a soft-depend is enough and no delayed registration is needed.
 */
final class VaultUnlockedLink {

  /** VaultUnlocked's plugin.yml name. NOT "VaultUnlocked" — see the class javadoc. */
  static final String PLUGIN_NAME = "Vault";
  static final String VAULT2_ECONOMY = "net.milkbowl.vault2.economy.Economy";
  static final String LEGACY_ECONOMY = "net.milkbowl.vault.economy.Economy";
  static final String LEGACY_ABSTRACT = "net.milkbowl.vault.economy.AbstractEconomy";

  private volatile boolean enabled;

  void enabled(boolean value) {
    this.enabled = value;
  }

  boolean enabled() {
    return enabled;
  }

  /** Whether the broker plugin is present at all, regardless of the operator switch. */
  boolean installed() {
    return plugin() != null;
  }

  /**
   * Whether the modern {@code vault2} economy interface links here.
   *
   * <p>Through {@link PlatformProbes#linkable}, whose {@code LinkageError} catch is the point rather
   * than a formality: a VaultUnlocked release compiled at a class-file level this JVM will not link
   * has to degrade to "no economy service is registered" — money still works in game, no other plugin
   * can see it — and never to an {@code UnsupportedClassVersionError} out of {@code onEnable}, which
   * takes the whole plugin down with it.</p>
   */
  boolean modernLinkable() {
    return PlatformProbes.linkable(VAULT2_ECONOMY, getClass().getClassLoader());
  }

  /** The same question for the legacy {@code vault1} interface and its abstract helper. */
  boolean legacyLinkable() {
    return PlatformProbes.linkable(LEGACY_ECONOMY, getClass().getClassLoader())
        && PlatformProbes.linkable(LEGACY_ABSTRACT, getClass().getClassLoader());
  }

  /**
   * Opted in, installed, and linkable.
   *
   * <p>Re-evaluated on every call and never cached: VaultUnlocked can be enabled after Sexidium on a
   * server that reloads plugins, and a cached "no" would outlive the reason for it.</p>
   */
  boolean available() {
    return enabled && installed() && modernLinkable();
  }

  private Plugin plugin() {
    try {
      return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
    } catch (RuntimeException | LinkageError ignored) {
      // No server running — the unit tests probe this with no Bukkit at all, and every probe has to
      // fail softly rather than take the caller down with it.
      return null;
    }
  }
}
