package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TntWarConfigTest {
  /** Minimal in-memory ConfigurationAdapter keyed by full dotted path. */
  private static final class MapConfig implements ConfigurationAdapter {
    private final Map<String, Object> values = new HashMap<>();

    MapConfig put(String key, Object value) {
      values.put(key, value);
      return this;
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
      return values.get(path) instanceof Boolean bool ? bool : defaultValue;
    }

    @Override
    public int getInt(String path, int defaultValue) {
      return values.get(path) instanceof Number number ? number.intValue() : defaultValue;
    }

    @Override
    public long getLong(String path, long defaultValue) {
      return values.get(path) instanceof Number number ? number.longValue() : defaultValue;
    }

    @Override
    public double getDouble(String path, double defaultValue) {
      return values.get(path) instanceof Number number ? number.doubleValue() : defaultValue;
    }

    @Override
    public String getString(String path, String defaultValue) {
      Object value = values.get(path);
      return value == null ? defaultValue : String.valueOf(value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
      return values.get(path) instanceof List<?> list ? (List<String>) list : List.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getMapList(String path) {
      return values.get(path) instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @Override
    public Set<String> keys(String path) {
      return Set.of();
    }

    @Override
    public Object get(String path) {
      return values.get(path);
    }

    @Override
    public boolean contains(String path) {
      return values.containsKey(path);
    }

    @Override
    public void set(String path, Object value) {
      values.put(path, value);
    }

    @Override
    public void reload() {
    }

    @Override
    public void save() {
    }
  }

  @Test
  void defaultsProvideTheFullPaletteAndVanillaTnt() {
    TntWarConfig config = new TntWarConfig(new MapConfig(), "minigames.tntwar");
    assertEquals(26, config.buildPalette().size());
    assertTrue(config.buildPalette().contains(ItemKey.minecraft("stone_bricks")));
    assertTrue(config.buildPalette().contains(ItemKey.minecraft("dispenser")));
    assertTrue(config.isCustomTnt(ItemKey.minecraft("tnt")));
    assertEquals(ItemKey.minecraft("chest"), config.buildMenuItem());
    assertEquals(ItemKey.minecraft("dispenser"), config.dispenserId());
    assertEquals(20, config.livesPerTeam());
    assertEquals(75, config.winDestructionPercent());
    assertTrue(config.maps().isEmpty());
    assertNull(config.chooseMap(0));
  }

  @Test
  void readsCustomTntAndMapsAndClampsWinPercent() {
    List<Map<String, Object>> maps = new ArrayList<>();
    Map<String, Object> entry = new HashMap<>();
    entry.put("id", "arena");
    entry.put("world", "tntwar/arena");
    maps.add(entry);

    MapConfig cfg = new MapConfig()
        .put("minigames.tntwar.lives-per-team", 5)
        .put("minigames.tntwar.win-destruction-percent", 250)
        .put("minigames.tntwar.custom-tnt-ids", List.of("minecraft:tnt", "moddedtnt:nuke"))
        .put("minigames.tntwar.maps", maps);
    cfg.set("minigames.tntwar.build-palette", List.of("minecraft:stone", "minecraft:dirt"));

    TntWarConfig config = new TntWarConfig(cfg, "minigames.tntwar");
    assertEquals(5, config.livesPerTeam());
    assertEquals(100, config.winDestructionPercent()); // clamped to <= 100
    assertTrue(config.isCustomTnt(new ItemKey("moddedtnt", "nuke")));
    assertFalse(config.isCustomTnt(ItemKey.minecraft("stone")));
    assertEquals(2, config.buildPalette().size());
    assertEquals(1, config.maps().size());
    assertEquals("arena", config.chooseMap(0).id());
    assertEquals("tntwar/arena", config.chooseMap(7).world()); // index wraps
  }
}
