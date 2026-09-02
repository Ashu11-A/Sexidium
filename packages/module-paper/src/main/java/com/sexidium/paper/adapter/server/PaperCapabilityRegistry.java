package com.sexidium.paper.adapter.server;

import com.sexidium.core.platform.capability.Capability;
import com.sexidium.core.platform.capability.CapabilityRegistry;
import com.sexidium.core.platform.version.ServerVersion;
import com.sexidium.paper.adapter.npc.PaperNpcBackend;
import com.sexidium.paper.adapter.player.PaperGeyser;
import com.sexidium.paper.adapter.player.PaperHardcoreView;
import com.sexidium.paper.adapter.util.PlatformProbes;
import com.sexidium.paper.adapter.util.SkinsRestorerSupport;
import org.bukkit.Bukkit;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The one {@link CapabilityRegistry} Paper ships: every {@link Capability} probed against the RUNNING
 * server at construction, each miss carrying its reason for the boot log and
 * {@code /sx admin capabilities}.
 *
 * <p>One probing registry, deliberately — not a {@code v26_1}/{@code v26_2} class pair. Probing is what
 * survived the 26.2 packet change; version-keying is what broke (Plan.md §4). Where a capability cannot
 * be probed through the API surface ({@code DIMENSION_STORAGE_KEYED}), the server version is the
 * tiebreaker, never the primary key.</p>
 *
 * <h2>Completeness is structural, not remembered</h2>
 * Each probe is a {@link Probe} in an {@link EnumMap} keyed by the capability it answers, and
 * {@link #probe} walks {@link Capability#values()} rather than the map. A constant with no probe is
 * therefore impossible to miss twice: it fails closed at runtime (the builder records it unsupported
 * with {@link CapabilityRegistry#UNPROBED_REASON}) and it fails loudly at build time, because
 * {@code PaperCapabilityRegistryTest} asserts every constant gets a real answer. The earlier shape —
 * a map of misses, support derived by subtraction — reported an unprobed capability as available on
 * every server.
 *
 * <p>Probes run once, at enable: these are server facts, which do not change while the process lives.
 * Plugin-backed capabilities keep their per-call re-evaluation where it matters — the HUD driver still
 * re-checks BetterHud on every open, because plugins enable late; the value here is only the boot-time
 * snapshot an operator reads.</p>
 */
public final class PaperCapabilityRegistry {

  /**
   * One capability's verdict: the reason it cannot be served, or empty when it can.
   *
   * <p>Returning the reason rather than a boolean is what keeps "unavailable" and "unexplained" from
   * ever coming apart — there is no way to record a miss without saying why.</p>
   */
  @FunctionalInterface
  interface Probe {
    Optional<String> unavailableReason();
  }

  private PaperCapabilityRegistry() {
  }

  /**
   * Probes everything. {@code hudOverlay} is supplied by the caller because whether BetterHud may draw
   * is the HUD stack's own three-gate decision (operator switch → linkability → pack-format window),
   * not something this class should re-implement.
   */
  public static CapabilityRegistry probe(BooleanSupplier hudOverlay, PaperNpcBackend npcBackend) {
    Map<Capability, Probe> probes = probes(hudOverlay, npcBackend);
    CapabilityRegistry.Probe result = CapabilityRegistry.probing();
    for (Capability capability : Capability.values()) {
      Probe probe = probes.get(capability);
      if (probe == null) {
        // Left unanswered on purpose: the builder fails it closed with UNPROBED_REASON, which is a
        // visible bug report in the boot log rather than a silent "yes".
        continue;
      }
      result.answer(capability, guarded(probe));
    }
    return result.build();
  }

  /**
   * The probe table. Package-private so the completeness test can assert it covers every constant —
   * the assertion that makes a forgotten probe a failing build rather than a boot-log surprise.
   */
  static Map<Capability, Probe> probes(BooleanSupplier hudOverlay, PaperNpcBackend npcBackend) {
    ServerVersion version = PaperServerVersionPort.read();
    Map<Capability, Probe> probes = new EnumMap<>(Capability.class);
    probes.put(Capability.ITEM_MODEL_COMPONENT, PaperCapabilityRegistry::itemModelComponent);
    probes.put(Capability.HARDCORE_VIEW_PACKET, PaperHardcoreView::unavailableReason);
    probes.put(Capability.DIMENSION_STORAGE_KEYED, () -> dimensionStorage(version));
    probes.put(Capability.FOLIA_REGION_SCHEDULER, PaperCapabilityRegistry::regionScheduler);
    probes.put(Capability.BEDROCK_FORMS, PaperCapabilityRegistry::bedrockForms);
    probes.put(Capability.SKIN_LOOKUP_OFFLINE, PaperCapabilityRegistry::skinsRestorer);
    probes.put(Capability.HUD_OVERLAY, () -> hudOverlay(hudOverlay));
    probes.put(Capability.LOBBY_NPCS, npcBackend::unavailableReason);
    return probes;
  }

  /**
   * Runs one probe so a thrown probe costs its own line and nothing else.
   *
   * <p>This whole registry is built during {@code onEnable}; an escaping {@link LinkageError} from a
   * soft-depend gate would take the plugin down over a diagnostic. A probe that cannot answer reports
   * the capability as unavailable, naming the failure — fail-closed, like every other miss.</p>
   */
  private static Optional<String> guarded(Probe probe) {
    try {
      return probe.unavailableReason();
    } catch (RuntimeException | LinkageError failed) {
      return Optional.of("probing this capability failed: " + failed);
    }
  }

  private static Optional<String> itemModelComponent() {
    try {
      org.bukkit.inventory.meta.ItemMeta.class
          .getMethod("setItemModel", org.bukkit.NamespacedKey.class);
      return Optional.empty();
    } catch (NoSuchMethodException | LinkageError missing) {
      return Optional.of("ItemMeta#setItemModel is missing on this server;"
          + " UI items keep the vanilla material icon");
    }
  }

  private static Optional<String> dimensionStorage(ServerVersion version) {
    // The one version-keyed entry, because there is no API surface to ask. Keyed dimension storage
    // arrived with MC 26.1's unified world layout; on anything older, keyed creation is a guess.
    return version.atLeast(26, 1)
        ? Optional.empty()
        : Optional.of("keyed dimension storage needs MC 26.1 or newer; running " + version);
  }

  private static Optional<String> regionScheduler() {
    return PlatformProbes.linkable("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler",
        PaperCapabilityRegistry.class.getClassLoader())
        ? Optional.empty()
        : Optional.of("the region scheduler family is absent; Folia-safe scheduling unavailable");
  }

  private static Optional<String> bedrockForms() {
    return PaperGeyser.bedrockUiAvailable()
        ? Optional.empty()
        : Optional.of("neither Floodgate nor Geyser exposes a form API here; Bedrock players get the"
            + " chest GUI like everyone else");
  }

  private static Optional<String> skinsRestorer() {
    boolean pluginPresent;
    try {
      pluginPresent = Bukkit.getPluginManager().getPlugin("SkinsRestorer") != null;
    } catch (RuntimeException | LinkageError noServerHere) {
      pluginPresent = false;
    }
    if (SkinsRestorerSupport.available()) {
      return Optional.empty();
    }
    return Optional.of(pluginPresent
        ? "SkinsRestorer is installed but its storage API does not link against the version Sexidium"
            + " was built against"
        : "SkinsRestorer is not installed; offline players resolve to their stored Mojang profile");
  }

  private static Optional<String> hudOverlay(BooleanSupplier hudOverlay) {
    return hudOverlay.getAsBoolean()
        ? Optional.empty()
        : Optional.of("BetterHud is absent, switched off, or its shaders do not match this Minecraft"
            + " version (surfaces render on the scoreboard sidebar)");
  }
}
