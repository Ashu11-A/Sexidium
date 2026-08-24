package com.sexidium.core.decor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-minigame-mode decor styling: the sensible vanilla <b>base item</b> a podium shows when the
 * resource pack is absent (the {@code item_model} from {@link com.sexidium.core.menu.MenuArt#modeModel}
 * renders on top of it for pack-loaded clients), and the <b>glow color</b> of that mode's pedestal.
 *
 * <p>Both tables are curated per mode and guarded by {@code DecorPaletteCoverageTest} against the
 * minigame registry ({@link com.sexidium.core.menu.MenuArt#MODE_ICON_IDS}), exactly like
 * {@code MenuArtCoverageTest} guards the icon table — so a new mode cannot silently fall back to a
 * generic look. Unknown modes still resolve to non-null defaults so the adapter never NPEs.</p>
 */
public final class DecorPalette {
  private static final String DEFAULT_ITEM = "minecraft:nether_star";
  private static final int DEFAULT_GLOW = 0xFFFFFFFF;

  // Base vanilla item shown when the pack is declined/absent (pack-loaded clients see the item_model).
  private static final Map<String, String> BASE_ITEMS = mapOf(
      "race", "minecraft:clock",
      "gather", "minecraft:raw_gold",
      "tntwar", "minecraft:tnt",
      "combat", "minecraft:iron_sword",
      "fugitive", "minecraft:compass");

  // Pedestal glow color (packed ARGB) per mode; the adapter maps it to the nearest named team color.
  private static final Map<String, Integer> GLOW = glowMap();

  private DecorPalette() {
  }

  /** The base vanilla item id for a mode's podium ({@link #DEFAULT_ITEM} for an unknown mode). */
  public static String baseItem(String modeId) {
    String value = BASE_ITEMS.get(normalize(modeId));
    return value == null ? DEFAULT_ITEM : value;
  }

  /** The packed-ARGB pedestal glow color for a mode (opaque white for an unknown mode). */
  public static int glowArgb(String modeId) {
    Integer value = GLOW.get(normalize(modeId));
    return value == null ? DEFAULT_GLOW : value;
  }

  /** Whether this mode has a curated entry in BOTH tables (the coverage-test contract). */
  public static boolean hasCurated(String modeId) {
    String normalized = normalize(modeId);
    return BASE_ITEMS.containsKey(normalized) && GLOW.containsKey(normalized);
  }

  private static String normalize(String id) {
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }

  private static Map<String, Integer> glowMap() {
    Map<String, Integer> map = new LinkedHashMap<>();
    map.put("race", 0xFFFF5555);      // red
    map.put("gather", 0xFFFFAA00);    // gold
    map.put("tntwar", 0xFFFF5555);    // red
    map.put("combat", 0xFF55FF55);    // green
    map.put("fugitive", 0xFF55FFFF);  // aqua
    return Collections.unmodifiableMap(map);
  }

  private static Map<String, String> mapOf(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      map.put(pairs[index], pairs[index + 1]);
    }
    return Collections.unmodifiableMap(map);
  }
}
