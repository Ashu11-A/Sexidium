package com.sexidium.paper.adapter.npc;

import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.NpcAdapter;
import com.sexidium.core.platform.backend.Backend;
import com.sexidium.core.platform.capability.Capability;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The lobby-NPC seam as a {@link Backend}: FancyNpcs (+FancyHolograms for nameplates) when both are
 * installed and linkable, the inert {@link NpcAdapter#NOOP} otherwise — selected through the same
 * honest-capabilities contract as every other stacked seam.
 *
 * <p>This is the first backend migrated off its hand-rolled gate (Plan.md Stage 0b). The old gate
 * answered only "installed"; this one answers "capable", and can say WHY not — which is what
 * {@code /sx admin capabilities} prints and what a missing-nameplate bug report starts from.</p>
 *
 * <p>{@link #adapter()} re-probes lazily but binds once: plugins enable late, so an early "no" must
 * not be permanent, but once the real adapter is constructed its FancyNpcs/FancyHolograms classes are
 * linked and there is no going back. The probe itself never names either plugin's classes — plugin
 * presence comes from the manager, class linkability from {@code PlatformProbes.linkable} — so this
 * class is safe to load on a server that has never heard of them.</p>
 */
public final class PaperNpcBackend implements Backend<Capability> {

  private static final String FANCYNPCS_API = "de.oliver.fancynpcs.api.FancyNpcsPlugin";
  private static final String FANCYHOLOGRAMS_API = "de.oliver.fancyholograms.api.FancyHologramsPlugin";

  private final JavaPlugin plugin;
  private final LoggerAdapter logger;
  private volatile NpcAdapter bound;

  public PaperNpcBackend(JavaPlugin plugin) {
    this(plugin, null);
  }

  public PaperNpcBackend(JavaPlugin plugin, LoggerAdapter logger) {
    this.plugin = plugin;
    this.logger = logger == null
        ? new StdoutLoggerAdapter(plugin == null ? "PaperNpcBackend" : plugin.getName())
        : logger;
  }

  /** The working NPC adapter: FancyNpcs-backed when capable, the core no-op otherwise. Never null. */
  public NpcAdapter adapter() {
    NpcAdapter local = bound;
    if (local == null) {
      synchronized (this) {
        local = bound;
        if (local == null) {
          Optional<String> unavailable = unavailableReason();
          if (unavailable.isPresent()) {
            // Once per bind attempt, at the moment somebody actually asks for NPCs — not at boot,
            // where the overwhelming majority of servers run without these plugins.
            logger.info("Lobby NPCs disabled: " + unavailable.get());
            local = NpcAdapter.NOOP;
          } else {
            local = new PaperNpcAdapter(plugin);
          }
          bound = local;
        }
      }
    }
    return local;
  }

  @Override
  public Set<Capability> capabilities() {
    return unavailableReason().isPresent() ? Set.of() : EnumSet.of(Capability.LOBBY_NPCS);
  }

  /** Why the FancyNpcs backend cannot serve right now, or empty when it can. */
  public Optional<String> unavailableReason() {
    if (!pluginInstalled("FancyNpcs")) {
      return Optional.of("FancyNpcs is not installed");
    }
    if (!pluginInstalled("FancyHolograms")) {
      return Optional.of("FancyHolograms is not installed");
    }
    ClassLoader cl = getClass().getClassLoader();
    if (!com.sexidium.paper.adapter.util.PlatformProbes.linkable(FANCYNPCS_API, cl)) {
      return Optional.of("the FancyNpcs API on this server does not link against the version Sexidium"
          + " was built against");
    }
    if (!com.sexidium.paper.adapter.util.PlatformProbes.linkable(FANCYHOLOGRAMS_API, cl)) {
      return Optional.of("the FancyHolograms API on this server does not link against the version"
          + " Sexidium was built against");
    }
    return Optional.empty();
  }

  private boolean pluginInstalled(String name) {
    try {
      return Bukkit.getPluginManager().getPlugin(name) != null;
    } catch (RuntimeException | LinkageError noServerHere) {
      return false;
    }
  }
}
