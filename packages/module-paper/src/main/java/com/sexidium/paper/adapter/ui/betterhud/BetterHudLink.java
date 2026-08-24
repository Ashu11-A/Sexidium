package com.sexidium.paper.adapter.ui.betterhud;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * Decides whether this server should be talking to BetterHud at all — and says why when the answer is
 * no. Names no BetterHud type anywhere, deliberately: every check here has to be safe to run on a
 * server that has never heard of the plugin, and a field of a BetterHud type would resolve at class
 * load and defeat that before the first check ran.
 *
 * <h2>Three gates, in this order</h2>
 * <ol>
 *   <li><b>The operator switch.</b> Checked FIRST and on purpose: a server that has not opted in must
 *       never reach the class lookup, let alone the plugin.</li>
 *   <li><b>Linkability.</b> {@code Class.forName} with {@code initialize=false}, catching
 *       {@code LinkageError} as well as {@code ClassNotFoundException}, so an API too old or too new
 *       to link degrades to no surface rather than to a crash in the middle of a match.</li>
 *   <li><b>Capability.</b> The new one — see below.</li>
 * </ol>
 *
 * <h2>Why installed is not the same as capable</h2>
 * BetterHud does not draw its HUD with text and textures alone: the resource pack it sends REPLACES
 * the client's vanilla core text shaders, and which shader set it sends is picked from a hardcoded
 * table of pack-format ranges inside the plugin. When the running Minecraft version falls outside the
 * range that table actually covers, the plugin still loads, still accepts our layouts, and still
 * reports every object present — while the client renders the readout as a row of unknown-character
 * boxes, and vanilla GUI text alongside it comes out wrong.
 *
 * <p>An integration that only asked "is it installed" had no way to tell those apart, so the failure
 * landed on the operator as a visual bug with no diagnosis. Asking here instead means the driver
 * reports no capabilities, the sidebar renderer picks every surface up, and the readout is simply on
 * the scoreboard instead of in the corner.</p>
 */
final class BetterHudLink {
  private static final String API_CLASS = "kr.toxicity.hud.api.BetterHudAPI";
  private static final String PLUGIN_NAME = "BetterHud";

  /**
   * The pack-format range BetterHud's newest shader overlay actually covers. Kept as data rather than
   * as prose in four places, which is what it used to be.
   */
  private static final int SUPPORTED_PACK_FORMAT_MIN = 84;
  private static final int SUPPORTED_PACK_FORMAT_MAX = 87;

  private final Consumer<String> log;
  private volatile boolean enabled;
  private volatile boolean probeCapability = true;
  private volatile boolean warned;

  BetterHudLink(Consumer<String> log) {
    this.log = log == null ? message -> { } : log;
  }

  void enabled(boolean value) {
    this.enabled = value;
  }

  boolean enabled() {
    return enabled;
  }

  void probeCapability(boolean value) {
    this.probeCapability = value;
  }

  /** Whether the plugin is present at all, regardless of the operator switch. */
  boolean installed() {
    return plugin() != null;
  }

  /**
   * Whether the driver may draw: opted in, linkable, and capable.
   *
   * <p>Re-evaluated on every call rather than cached. BetterHud can be enabled after Sexidium, and an
   * operator can switch our own gate mid-session; a cached "no" would outlive both.</p>
   */
  boolean available() {
    return enabled && linkable() && capable();
  }

  /**
   * Linkable without the operator switch — the one thing a disabled driver may still do, because
   * BetterHud persists worn layouts to {@code .users/<uuid>.yml} and re-applies them on join. A claim
   * made while the gate was open outlives the switch, so taking ours back off players has to work on
   * the disabled path too.
   */
  boolean linkable() {
    if (plugin() == null) {
      return false;
    }
    try {
      Class.forName(API_CLASS, false, getClass().getClassLoader());
      return true;
    } catch (ClassNotFoundException | LinkageError ignored) {
      return false;
    }
  }

  /**
   * Whether BetterHud's shaders match this server's Minecraft version closely enough to draw legible
   * text. Warns once, then answers false for the rest of the session.
   */
  private boolean capable() {
    if (!probeCapability) {
      return true;
    }
    int packFormat = serverPackFormat();
    if (packFormat < 0) {
      // The version string was not in a shape we recognise. Refusing on that basis would disable a
      // working setup over a parsing detail, so an unknown version is treated as capable.
      return true;
    }
    if (packFormat >= SUPPORTED_PACK_FORMAT_MIN && packFormat <= SUPPORTED_PACK_FORMAT_MAX) {
      return true;
    }
    warnOnce("BetterHud is installed, but its bundled shaders do not cover this server's Minecraft"
        + " version (pack format " + packFormat + "; its overlay genuinely covers "
        + SUPPORTED_PACK_FORMAT_MIN + "-" + SUPPORTED_PACK_FORMAT_MAX + "). Drawing through it would"
        + " give players a row of unknown-character boxes and mis-coloured vanilla GUI text, so"
        + " Sexidium is leaving it alone and rendering every declared surface on the scoreboard"
        + " sidebar instead. Set hud.betterhud.capability-probe to false to override this."
        + " NOTE: this stops SEXIDIUM using BetterHud; it does not stop BetterHud sending its own"
        + " pack. If you still see corrupted text, remove the plugin — that is the only complete fix.");
    return false;
  }

  /**
   * The resource-pack format of the running server, derived from its Minecraft version, or -1 when
   * the version string is not in a shape worth guessing from.
   */
  private int serverPackFormat() {
    try {
      return PackFormats.of(Bukkit.getMinecraftVersion());
    } catch (RuntimeException ignored) {
      // No server (unit tests), or an API that does not expose the version. Not a capability problem.
      return -1;
    }
  }

  private void warnOnce(String message) {
    if (!warned) {
      warned = true;
      log.accept(message);
    }
  }

  private Plugin plugin() {
    try {
      return Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
    } catch (RuntimeException | LinkageError ignored) {
      // No server running — the unit tests probe a bridge with no Bukkit at all, and every probe has
      // to fail softly rather than take the plugin down with it.
      return null;
    }
  }
}
