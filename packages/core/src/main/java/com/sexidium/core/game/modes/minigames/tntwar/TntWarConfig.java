package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot of the {@code minigames.tntwar} config block, parsed once at match start so the per-tick
 * destruction rescan never re-walks the YAML tree. Holds the build-palette item list (the blocks/items
 * offered in the chest GUI), the set of item ids treated as TNT (auto-primed on place and fed into
 * dispensers — add modded TNT here), the team-lives and base-destruction win thresholds, and the list
 * of cloneable maps.
 */
public final class TntWarConfig {
  /** Item id of one configured map: the {@code id} and the template {@code world} folder it lives in. */
  public record MapEntry(String id, String world) {
  }

  /**
   * The default build palette: every block/item the goal lists as usable for building TNT-War machines.
   * Configurable via {@code minigames.tntwar.build-palette}; these are the fallback when unset.
   */
  public static final List<String> DEFAULT_PALETTE = List.of(
      "stone_bricks", "stone_brick_stairs", "ladder", "oak_trapdoor", "oak_fence",
      "stone_pressure_plate", "stone_slab", "piston", "sticky_piston", "lever",
      "dispenser", "stone_button", "redstone", "redstone_torch", "repeater",
      "redstone_block", "sand", "slime_block", "tnt", "water_bucket",
      "diamond_pickaxe", "diamond_axe", "diamond_shovel", "minecart", "lava_bucket", "emerald");

  private final boolean friendlyFire;
  private final int warmupSeconds;
  private final int matchSeconds;
  private final int voidLevel;
  private final boolean autoPrime;
  private final int primeFuseTicks;
  private final int tntAmount;
  private final boolean giveFlintAndSteel;
  private final int livesPerTeam;
  private final int winDestructionPercent;
  private final int refreshTicks;
  private final int maxScanBlocks;
  private final boolean infiniteBlocks;
  private final boolean lockDispensers;
  private final ItemKey buildMenuItem;
  private final List<ItemKey> buildPalette;
  private final Set<ItemKey> customTntIds;
  private final ItemKey dispenserId;
  private final List<MapEntry> maps;
  private final boolean generateMissingMap;
  private final int generatedSeparation;
  private final int generatedBaseWidth;
  private final int generatedBaseHeight;
  private final ItemKey generatedBaseBlock;
  private final ItemKey generatedPlatformBlock;

  public TntWarConfig(ConfigurationAdapter cfg, String prefix) {
    // Inherit the shared game.* defaults when the mode does not override them.
    this.friendlyFire = cfg.getBoolean(prefix + ".friendly-fire", cfg.getBoolean("game.friendly-fire", false));
    this.warmupSeconds = Math.max(0, cfg.getInt(prefix + ".warmup-seconds", cfg.getInt("game.warmup-seconds", 5)));
    this.matchSeconds = Math.max(0, cfg.getInt(prefix + ".match-seconds", 600));
    this.voidLevel = cfg.getInt(prefix + ".void-level", 0);
    this.autoPrime = cfg.getBoolean(prefix + ".auto-prime", true);
    this.primeFuseTicks = Math.max(1, cfg.getInt(prefix + ".prime-fuse-ticks", 40));
    this.tntAmount = Math.max(1, cfg.getInt(prefix + ".tnt-amount", 64));
    this.giveFlintAndSteel = cfg.getBoolean(prefix + ".give-flint-and-steel", true);
    this.livesPerTeam = Math.max(1, cfg.getInt(prefix + ".lives-per-team", 20));
    this.winDestructionPercent = clampPercent(cfg.getInt(prefix + ".win-destruction-percent", 75));
    this.refreshTicks = (int) HudCadence.ticks(cfg, prefix + ".refresh-ticks");
    this.maxScanBlocks = Math.max(0, cfg.getInt(prefix + ".max-scan-blocks", 40000));
    this.infiniteBlocks = cfg.getBoolean(prefix + ".infinite-blocks", true);
    this.lockDispensers = cfg.getBoolean(prefix + ".lock-tnt-dispensers", true);
    this.buildMenuItem = parseItemKey(cfg.getString(prefix + ".build-menu-item", "chest"));
    this.dispenserId = parseItemKey(cfg.getString(prefix + ".dispenser-id", "dispenser"));
    this.buildPalette = parsePalette(cfg.getStringList(prefix + ".build-palette"));
    this.customTntIds = parseTntIds(cfg.getStringList(prefix + ".custom-tnt-ids"));
    this.maps = parseMaps(cfg.getMapList(prefix + ".maps"));
    this.generateMissingMap = cfg.getBoolean(prefix + ".generated.enabled", true);
    this.generatedSeparation = Math.max(24, cfg.getInt(prefix + ".generated.separation", 80));
    this.generatedBaseWidth = Math.max(3, cfg.getInt(prefix + ".generated.base-width", 9));
    this.generatedBaseHeight = Math.max(2, cfg.getInt(prefix + ".generated.base-height", 5));
    this.generatedBaseBlock = orDefault(parseItemKey(cfg.getString(prefix + ".generated.base-block", "bricks")), "bricks");
    this.generatedPlatformBlock = orDefault(parseItemKey(cfg.getString(prefix + ".generated.platform-block", "stone")), "stone");
  }

  public boolean friendlyFire() {
    return friendlyFire;
  }

  public int warmupSeconds() {
    return warmupSeconds;
  }

  public int matchSeconds() {
    return matchSeconds;
  }

  public int voidLevel() {
    return voidLevel;
  }

  public boolean autoPrime() {
    return autoPrime;
  }

  public int primeFuseTicks() {
    return primeFuseTicks;
  }

  public int tntAmount() {
    return tntAmount;
  }

  public boolean giveFlintAndSteel() {
    return giveFlintAndSteel;
  }

  public int livesPerTeam() {
    return livesPerTeam;
  }

  public int winDestructionPercent() {
    return winDestructionPercent;
  }

  public int refreshTicks() {
    return refreshTicks;
  }

  public int maxScanBlocks() {
    return maxScanBlocks;
  }

  public boolean infiniteBlocks() {
    return infiniteBlocks;
  }

  public boolean lockDispensers() {
    return lockDispensers;
  }

  public ItemKey buildMenuItem() {
    return buildMenuItem;
  }

  public ItemKey dispenserId() {
    return dispenserId;
  }

  public List<ItemKey> buildPalette() {
    return buildPalette;
  }

  public Set<ItemKey> customTntIds() {
    return customTntIds;
  }

  /** True when the given placed/held item is a configured TNT variant (vanilla or modded). */
  public boolean isCustomTnt(ItemKey itemKey) {
    return itemKey != null && customTntIds.contains(itemKey);
  }

  /** True when the given item is part of the build palette (so placing it should not deplete it). */
  public boolean isPalette(ItemKey itemKey) {
    return itemKey != null && buildPalette.contains(itemKey);
  }

  public List<MapEntry> maps() {
    return maps;
  }

  public boolean hasMaps() {
    return !maps.isEmpty();
  }

  /** When no ready map is available, procedurally build two opposing bases into the generated world. */
  public boolean generateMissingMap() {
    return generateMissingMap;
  }

  public int generatedSeparation() {
    return generatedSeparation;
  }

  public int generatedBaseWidth() {
    return generatedBaseWidth;
  }

  public int generatedBaseHeight() {
    return generatedBaseHeight;
  }

  public ItemKey generatedBaseBlock() {
    return generatedBaseBlock;
  }

  public ItemKey generatedPlatformBlock() {
    return generatedPlatformBlock;
  }

  /** Picks a map by index (wraps), or null when none are configured. */
  public MapEntry chooseMap(int index) {
    if (maps.isEmpty()) {
      return null;
    }
    int safe = Math.floorMod(index, maps.size());
    return maps.get(safe);
  }

  private static List<ItemKey> parsePalette(List<String> configured) {
    List<String> source = configured == null || configured.isEmpty() ? DEFAULT_PALETTE : configured;
    List<ItemKey> palette = new ArrayList<>();
    for (String entry : source) {
      ItemKey key = parseItemKey(entry);
      if (key != null && !palette.contains(key)) {
        palette.add(key);
      }
    }
    return List.copyOf(palette);
  }

  private static Set<ItemKey> parseTntIds(List<String> configured) {
    Set<ItemKey> ids = new LinkedHashSet<>();
    if (configured == null || configured.isEmpty()) {
      ids.add(ItemKey.minecraft("tnt"));
    } else {
      for (String entry : configured) {
        ItemKey key = parseItemKey(entry);
        if (key != null) {
          ids.add(key);
        }
      }
    }
    return Set.copyOf(ids);
  }

  private static List<MapEntry> parseMaps(List<Map<String, Object>> configured) {
    List<MapEntry> maps = new ArrayList<>();
    if (configured == null) {
      return List.of();
    }
    for (Map<String, Object> entry : configured) {
      if (entry == null) {
        continue;
      }
      String id = string(entry.get("id"));
      String world = string(entry.get("world"));
      if (id.isBlank()) {
        continue;
      }
      maps.add(new MapEntry(id, world.isBlank() ? id : world));
    }
    return List.copyOf(maps);
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private static ItemKey orDefault(ItemKey key, String fallback) {
    return key != null ? key : ItemKey.minecraft(fallback);
  }

  private static int clampPercent(int value) {
    return Math.max(1, Math.min(100, value));
  }

  /** Parses {@code namespace:path} (or a bare path, defaulting to {@code minecraft}) into an ItemKey. */
  public static ItemKey parseItemKey(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    int colon = normalized.indexOf(':');
    if (colon > 0) {
      return new ItemKey(normalized.substring(0, colon), normalized.substring(colon + 1));
    }
    return ItemKey.minecraft(normalized);
  }
}
