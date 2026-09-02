package com.sexidium.paper.adapter.server;

import com.sexidium.paper.adapter.command.PaperCommandDispatcherAdapter;
import com.sexidium.paper.adapter.command.PaperCommandSource;
import com.sexidium.paper.adapter.config.PaperConfigurationAdapter;
import com.sexidium.paper.adapter.decor.PaperDecorAdapter;
import com.sexidium.paper.adapter.event.PaperEventDispatcherAdapter;
import com.sexidium.paper.adapter.inventory.PaperInventorySerializer;
import com.sexidium.paper.adapter.inventory.PaperKitAdapter;
import com.sexidium.paper.adapter.logging.PaperLoggerAdapter;
import com.sexidium.paper.adapter.menu.PaperMenuAdapter;
import com.sexidium.paper.adapter.npc.PaperNpcAdapter;
import com.sexidium.paper.adapter.npc.PaperNpcBackend;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.paper.adapter.resource.PaperResourceAdapter;
import com.sexidium.paper.adapter.scheduler.PaperSchedulerAdapter;
import com.sexidium.paper.adapter.ui.PaperMessageAdapter;
import com.sexidium.paper.adapter.ui.PaperRankTagAdapter;
import com.sexidium.paper.adapter.ui.PaperUiAdapter;
import com.sexidium.paper.adapter.world.PaperWorldControl;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.network.NetworkSettings;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.core.platform.capability.CapabilityRegistry;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.DecorAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.InventorySerializer;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MenuAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.NpcAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.RankTagAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.ServerInfoPort;
import com.sexidium.core.platform.SkinPort;
import com.sexidium.core.platform.ConsoleTap;
import com.sexidium.core.platform.version.ServerVersionPort;
import com.sexidium.paper.adapter.npc.PaperSkinPort;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.PlatformType;
import io.papermc.paper.ServerBuildInfo;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class PaperServerAdapter implements ServerAdapter {
  private final JavaPlugin plugin;
  private final PaperConfigurationAdapter configurationAdapter;
  private volatile NodeIdentity nodeIdentity;
  private final PaperMessageAdapter messageAdapter;
  private final PaperLoggerAdapter loggerAdapter;
  private final PaperResourceAdapter resourceAdapter;
  private final PaperSchedulerAdapter schedulerAdapter;
  private final PaperUiAdapter uiAdapter;
  private final PaperWorldControl worldLeaseService;
  private final PaperInventorySerializer inventorySerializer = new PaperInventorySerializer();
  private final PaperMenuAdapter menuAdapter;
  private final PaperRankTagAdapter rankTagAdapter = new PaperRankTagAdapter();
  // Lazily bound lobby-NPC backend: FancyNpcs when installed and linkable, the inert core no-op
  // otherwise. The probing/gating lives in the backend (a Backend<Capability>); this class only
  // resolves it once.
  private volatile PaperNpcBackend npcBackend;
  private volatile CapabilityRegistry capabilityRegistry;
  private volatile ServerVersionPort versionPort;
  // Lazily created in-world decor backend (native display entities; no plugin dependency).
  private volatile DecorAdapter decorAdapter;
  // Cached full-registry enumerations (Material.values() is stable for the server lifetime).
  private volatile List<ItemKey> allItemsCache;
  private volatile List<ItemKey> allBlocksCache;
  // Lazily created bridge ports (skin resolution + console tap) for the Discord bot.
  private volatile SkinPort skinPort;
  private volatile ConsoleTap consoleTap;

  public PaperServerAdapter(JavaPlugin plugin, PaperConfigurationAdapter configurationAdapter, PaperMessageAdapter messageAdapter) {
    this(plugin, configurationAdapter, messageAdapter, new PaperMenuAdapter(plugin));
  }

  public PaperServerAdapter(JavaPlugin plugin, PaperConfigurationAdapter configurationAdapter, PaperMessageAdapter messageAdapter, PaperMenuAdapter menuAdapter) {
    this.plugin = plugin;
    this.configurationAdapter = configurationAdapter;
    this.messageAdapter = messageAdapter;
    this.loggerAdapter = new PaperLoggerAdapter(plugin.getLogger());
    this.resourceAdapter = new PaperResourceAdapter(plugin);
    this.schedulerAdapter = new PaperSchedulerAdapter(plugin);
    this.uiAdapter = new PaperUiAdapter(messageAdapter);
    this.worldLeaseService = new PaperWorldControl(plugin, configurationAdapter, loggerAdapter);
    this.menuAdapter = menuAdapter;
  }

  @Override
  public String serverName() {
    return Bukkit.getServer().getName();
  }

  /**
   * This node's place in the network, resolved once from {@code network.*}.
   *
   * <p>Cached because it is read on hot paths (every capability gate) and cannot change without a
   * restart — the node id is the routing key a world placement is pinned to, so re-resolving it live
   * would move worlds out from under players.</p>
   *
   * <p>Deliberately not derived from {@link #serverName()}: that returns the SOFTWARE name ("Paper"),
   * which is identical on all four backends.</p>
   */
  @Override
  public NodeIdentity identity() {
    NodeIdentity resolved = nodeIdentity;
    if (resolved == null) {
      resolved = NetworkSettings.resolve(configurationAdapter);
      nodeIdentity = resolved;
    }
    return resolved;
  }

  @Override
  public PlatformType platformType() {
    // ORDER IS LOAD-BEARING: the hybrid sniff runs FIRST, the brand check second.
    //
    // The brand check answers "is this Paper-compatible", which is the better question for everything
    // it can answer — but it is answered YES by a hybrid that is itself a Paper fork, and the modern
    // Mohist/Arclight lines are exactly that. Asking it first therefore returned BUKKIT for a server
    // running Forge mods beside Bukkit plugins and left the sniff below unreachable, silently
    // reclassifying the one platform this method exists to distinguish. The sniff is the only signal
    // that identifies a hybrid at all, so it goes first; the brand check then covers everything the
    // name cannot tell apart (Paper, Folia, Purpur), and the historical default closes it out.
    try {
      String name = Bukkit.getServer().getName().toLowerCase(Locale.ROOT);
      if (name.contains("mohist") || name.contains("arclight") || name.contains("magma")) {
        return PlatformType.HYBRID;
      }
    } catch (RuntimeException | LinkageError noServerName) {
      // No server, or one that will not answer its own name — the brand check below may still know
    }
    try {
      if (ServerBuildInfo.buildInfo().isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID)) {
        return PlatformType.BUKKIT;
      }
    } catch (RuntimeException | LinkageError unavailable) {
      // no ServerBuildInfo here (an older fork, or no server at all under test) — the default below
      // is what this method always answered before the brand check existed
    }
    return PlatformType.BUKKIT;
  }

  @Override
  public Path dataDirectory() {
    return plugin.getDataFolder().toPath();
  }

  @Override
  public ConfigurationAdapter configuration() {
    return configurationAdapter;
  }

  @Override
  public LoggerAdapter logger() {
    return loggerAdapter;
  }

  @Override
  public ResourceAdapter resources() {
    return resourceAdapter;
  }

  @Override
  public SchedulerAdapter scheduler() {
    return schedulerAdapter;
  }

  @Override
  public UiAdapter ui() {
    return uiAdapter;
  }

  @Override
  public MenuAdapter menus() {
    return menuAdapter;
  }

  @Override
  public RankTagAdapter rankTags() {
    return rankTagAdapter;
  }

  @Override
  public NpcAdapter npcs() {
    return npcBackend().adapter();
  }

  /** The lobby-NPC backend, resolved once. Probing and degradation live in {@link PaperNpcBackend}. */
  private PaperNpcBackend npcBackend() {
    PaperNpcBackend local = npcBackend;
    if (local == null) {
      synchronized (this) {
        local = npcBackend;
        if (local == null) {
          local = new PaperNpcBackend(plugin, loggerAdapter);
          npcBackend = local;
        }
      }
    }
    return local;
  }

  @Override
  public DecorAdapter decor() {
    DecorAdapter local = decorAdapter;
    if (local == null) {
      synchronized (this) {
        local = decorAdapter;
        if (local == null) {
          // Native ItemDisplay/BlockDisplay entities — no soft-depend, so always available.
          local = new PaperDecorAdapter(plugin);
          decorAdapter = local;
        }
      }
    }
    return local;
  }

  @Override
  public MessageAdapter messages() {
    return messageAdapter;
  }

  @Override
  public EventDispatcherAdapter events() {
    return new PaperEventDispatcherAdapter();
  }

  @Override
  public CommandDispatcherAdapter commands() {
    return new PaperCommandDispatcherAdapter();
  }

  @Override
  public WorldLeaseService worlds() {
    return worldLeaseService;
  }

  @Override
  public CommandSource console() {
    return new PaperCommandSource(Bukkit.getServer().getConsoleSender());
  }

  @Override
  public Collection<PlayerAdapter> onlinePlayers() {
    return Bukkit.getServer().getOnlinePlayers().stream().map(PaperPlayerAdapter::new).map(PlayerAdapter.class::cast).toList();
  }

  @Override
  public Optional<PlayerAdapter> player(UUID playerId) {
    Player player = Bukkit.getServer().getPlayer(playerId);
    return player == null ? Optional.empty() : Optional.of(new PaperPlayerAdapter(player));
  }

  @Override
  public Optional<PlayerAdapter> playerExact(String playerName) {
    Player player = Bukkit.getServer().getPlayerExact(playerName);
    return player == null ? Optional.empty() : Optional.of(new PaperPlayerAdapter(player));
  }

  @Override
  public InventorySerializer inventorySerializer() {
    return inventorySerializer;
  }

  @Override
  public List<ItemKey> allItems() {
    List<ItemKey> local = allItemsCache;
    if (local == null) {
      local = enumerate(true);
      allItemsCache = local;
    }
    return local;
  }

  @Override
  public List<ItemKey> allBlocks() {
    List<ItemKey> local = allBlocksCache;
    if (local == null) {
      local = enumerate(false);
      allBlocksCache = local;
    }
    return local;
  }

  /** Maps the live Material registry to ItemKeys, keeping real items (or blocks) and dropping legacy/air. */
  private static List<ItemKey> enumerate(boolean items) {
    List<ItemKey> keys = new ArrayList<>();
    for (Material material : Material.values()) {
      if (material.isLegacy() || material.isAir()) {
        continue;
      }
      if (items ? !material.isItem() : !material.isBlock()) {
        continue;
      }
      keys.add(ItemKey.minecraft(material.name().toLowerCase(Locale.ROOT)));
    }
    return List.copyOf(keys);
  }

  @Override
  public String itemTranslationKey(ItemKey itemKey) {
    if (itemKey == null) {
      return "";
    }
    Material material = Material.matchMaterial(itemKey.qualifiedName());
    if (material == null) {
      material = Material.matchMaterial(itemKey.value());
    }
    return material == null ? "" : material.translationKey();
  }

  /**
   * Tick health and capacity, published on every network heartbeat.
   *
   * <p>Scaled ×100 so it lands in an integer column: the value crosses sqlite, MySQL and Postgres,
   * and their DOUBLE behaviour is the one thing those three genuinely disagree about. Every reading
   * is defended individually — a Paper build that does not expose one of these must cost the reading
   * and not the heartbeat, because a node that stops checking in is reaped and loses its worlds.</p>
   */
  @Override
  public com.sexidium.core.platform.NodeHealthPort health() {
    return new com.sexidium.core.platform.NodeHealthPort() {
      @Override
      public int tpsTimes100() {
        try {
          double[] tps = Bukkit.getServer().getTPS();
          return tps == null || tps.length == 0 ? UNKNOWN : (int) Math.round(tps[0] * 100.0);
        } catch (Throwable unavailable) {
          return UNKNOWN;
        }
      }

      @Override
      public int msptTimes100() {
        try {
          return (int) Math.round(Bukkit.getServer().getAverageTickTime() * 100.0);
        } catch (Throwable unavailable) {
          return UNKNOWN;
        }
      }

      @Override
      public int maxPlayers() {
        try {
          return Math.max(0, Bukkit.getServer().getMaxPlayers());
        } catch (Throwable unavailable) {
          return 0;
        }
      }
    };
  }

  @Override
  public ServerInfoPort serverInfo() {
    return () -> {
      var server = Bukkit.getServer();
      double[] tps = server.getTPS();
      double firstTps = (tps != null && tps.length > 0) ? tps[0] : -1.0;
      String ip = server.getIp() == null ? "" : server.getIp();
      String motd = server.getMotd() == null ? "" : server.getMotd().replaceAll("§.", "");
      String bukkitVersion = server.getBukkitVersion();
      String version = bukkitVersion == null ? "" : bukkitVersion.split("-")[0];
      return new ServerInfoPort.ServerInfo(
          ip, server.getPort(), server.getOnlinePlayers().size(),
          server.getMaxPlayers(), motd, version, firstTps);
    };
  }

  @Override
  public SkinPort skins() {
    SkinPort port = skinPort;
    if (port == null) {
      port = new PaperSkinPort();
      skinPort = port;
    }
    return port;
  }

  @Override
  public ConsoleTap consoleTap() {
    ConsoleTap tap = consoleTap;
    if (tap == null) {
      tap = new PaperConsoleTap();
      consoleTap = tap;
    }
    return tap;
  }

  @Override
  public ServerVersionPort versions() {
    // Resolved once, like identity() and capabilities(): the running server's Minecraft version is a
    // fact about the process, and it cannot change without a restart. This used to re-run the whole
    // three-step probe chain on every call — including from the per-open HUD pack-format gate.
    ServerVersionPort local = versionPort;
    if (local == null) {
      synchronized (this) {
        local = versionPort;
        if (local == null) {
          local = PaperServerVersionPort.probe();
          versionPort = local;
        }
      }
    }
    return local;
  }

  @Override
  public CapabilityRegistry capabilities() {
    CapabilityRegistry local = capabilityRegistry;
    if (local == null) {
      synchronized (this) {
        local = capabilityRegistry;
        if (local == null) {
          // The HUD gate is the HUD stack's own honest answer; the NPC gate lives in the backend.
          // Both are asked once here — the boot-time snapshot the operator reads. Hot paths keep
          // their own per-call checks where late-enabling plugins matter.
          local = PaperCapabilityRegistry.probe(
              () -> !uiAdapter.betterHud().capabilities().isEmpty(),
              npcBackend());
          capabilityRegistry = local;
        }
      }
    }
    return local;
  }
}
