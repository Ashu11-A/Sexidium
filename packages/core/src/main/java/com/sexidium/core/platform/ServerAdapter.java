package com.sexidium.core.platform;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.PlatformType;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A Minecraft <em>server</em> backend: everything in {@link NodeRuntime}, plus the world-bound half
 * that only a process owning worlds, inventories and menus can provide.
 *
 * <p>The split exists so a Velocity proxy can implement {@code NodeRuntime} alone. Nothing here
 * changed when it was drawn — the members below are the same ones this interface always had.</p>
 */
public interface ServerAdapter extends NodeRuntime {
  String serverName();

  PlatformType platformType();

  @Override
  Path dataDirectory();

  @Override
  ConfigurationAdapter configuration();

  @Override
  LoggerAdapter logger();

  @Override
  ResourceAdapter resources();

  @Override
  SchedulerAdapter scheduler();

  UiAdapter ui();

  @Override
  MessageAdapter messages();

  EventDispatcherAdapter events();

  @Override
  CommandDispatcherAdapter commands();

  WorldLeaseService worlds();

  @Override
  CommandSource console();

  /** Narrowed from {@link NodeRuntime}: a backend's players have a body. */
  @Override
  Collection<PlayerAdapter> onlinePlayers();

  @Override
  Optional<PlayerAdapter> player(UUID playerId);

  @Override
  Optional<PlayerAdapter> playerExact(String playerName);

  /**
   * Resolves the vanilla translation key for an item (e.g. {@code item.minecraft.iron_ingot}) so it
   * can be rendered as a translatable component that each client localizes into its own language.
   * Returns an empty string when the platform cannot resolve a key, in which case callers fall back to
   * a plain server-side name.
   */
  default String itemTranslationKey(ItemKey itemKey) {
    return "";
  }

  /**
   * Every registered item in the running game's registry (item form). Used by the Randomizer challenge
   * to draw a genuine full-registry pool instead of a small hard-coded list. Platforms override with a
   * cached enumeration; the default is empty so headless/test adapters keep working and callers fall
   * back to their bundled default pool.
   */
  default List<ItemKey> allItems() {
    return List.of();
  }

  /** Every registered block (as its item form). Empty by default; see {@link #allItems()}. */
  default List<ItemKey> allBlocks() {
    return List.of();
  }

  /**
   * Serializer used to persist/restore player inventories across match snapshots (reconnect/resume).
   * Platforms override this with a full-fidelity implementation; the default is inert so headless and
   * test adapters keep working.
   */
  default InventorySerializer inventorySerializer() {
    return InventorySerializer.NOOP;
  }

  /** Chest-GUI menu renderer (Paper: InvUI; NeoForge: vanilla container). Default renders nothing. */
  default MenuAdapter menus() {
    return MenuAdapter.NOOP;
  }

  /** Lobby NPC backend (Paper: FancyNpcs; NeoForge: native). Default spawns nothing. */
  default NpcAdapter npcs() {
    return NpcAdapter.NOOP;
  }

  /** In-world decor backend (Paper: native ItemDisplay/BlockDisplay/Interaction). Default spawns nothing. */
  default DecorAdapter decor() {
    return DecorAdapter.NOOP;
  }

  /** Coloured rank-class name tags via native scoreboard teams (Paper + NeoForge). Default no-op. */
  default RankTagAdapter rankTags() {
    return RankTagAdapter.NOOP;
  }

  /**
   * Live server info (address/port/online/version/TPS) for the Discord bot's server-info card and
   * status heartbeat. Default is a best-effort snapshot from the online-player count + server name;
   * Paper overrides with real Bukkit values.
   */
  default ServerInfoPort serverInfo() {
    return () -> new ServerInfoPort.ServerInfo("", 0, onlinePlayers().size(), 0, serverName(), "", -1.0);
  }

  /** Player-skin resolver (SkinsRestorer on Paper) for rendering rank-card avatars. Default resolves nothing. */
  default SkinPort skins() {
    return SkinPort.NOOP;
  }

  /** Console line stream for the Discord live-console relay. Default emits nothing. */
  default ConsoleTap consoleTap() {
    return ConsoleTap.NOOP;
  }
}
