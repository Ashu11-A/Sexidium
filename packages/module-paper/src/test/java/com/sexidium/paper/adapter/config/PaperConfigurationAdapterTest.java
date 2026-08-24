package com.sexidium.paper.adapter.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemorySection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperConfigurationAdapterTest {

  private JavaPlugin plugin;
  private YamlConfiguration config;
  private PaperConfigurationAdapter adapter;

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    config = new YamlConfiguration();
    when(plugin.getConfig()).thenReturn(config);
    adapter = new PaperConfigurationAdapter(plugin);
  }

  @Test
  void getBoolean_returnsValue() {
    config.set("flag", true);
    assertTrue(adapter.getBoolean("flag", false));
  }

  @Test
  void getBoolean_returnsDefaultWhenMissing() {
    assertTrue(adapter.getBoolean("missing", true));
  }

  @Test
  void getInt_returnsValue() {
    config.set("count", 42);
    assertEquals(42, adapter.getInt("count", 0));
  }

  @Test
  void getInt_returnsDefaultWhenMissing() {
    assertEquals(7, adapter.getInt("missing", 7));
  }

  @Test
  void getLong_returnsValue() {
    config.set("big", 1234567890123L);
    assertEquals(1234567890123L, adapter.getLong("big", 0L));
  }

  @Test
  void getLong_returnsDefaultWhenMissing() {
    assertEquals(99L, adapter.getLong("missing", 99L));
  }

  @Test
  void getDouble_returnsValue() {
    config.set("pi", 3.14);
    assertEquals(3.14, adapter.getDouble("pi", 0.0));
  }

  @Test
  void getDouble_returnsDefaultWhenMissing() {
    assertEquals(2.5, adapter.getDouble("missing", 2.5));
  }

  @Test
  void getString_returnsValue() {
    config.set("name", "Sexidium");
    assertEquals("Sexidium", adapter.getString("name", "default"));
  }

  @Test
  void getString_returnsDefaultWhenMissing() {
    assertEquals("fallback", adapter.getString("missing", "fallback"));
  }

  @Test
  void getStringList_returnsValue() {
    config.set("list", List.of("a", "b", "c"));
    assertEquals(List.of("a", "b", "c"), adapter.getStringList("list"));
  }

  @Test
  void getStringList_emptyWhenMissing() {
    assertTrue(adapter.getStringList("missing").isEmpty());
  }

  @Test
  void getMapList_convertsKeysToStrings() {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("key1", 1);
    entry.put("key2", "value");
    config.set("list", List.of(entry));
    List<Map<String, Object>> result = adapter.getMapList("list");
    assertEquals(1, result.size());
    assertEquals(1, result.get(0).get("key1"));
    assertEquals("value", result.get(0).get("key2"));
  }

  @Test
  void getMapList_emptyWhenMissing() {
    assertTrue(adapter.getMapList("missing").isEmpty());
  }

  @Test
  void keys_returnsKeysOfSection() {
    config.set("section.a", 1);
    config.set("section.b", 2);
    Set<String> keys = adapter.keys("section");
    assertEquals(2, keys.size());
    assertTrue(keys.contains("a"));
    assertTrue(keys.contains("b"));
  }

  @Test
  void keys_emptyWhenSectionMissing() {
    assertTrue(adapter.keys("missing").isEmpty());
  }

  @Test
  void get_returnsValue() {
    config.set("foo", "bar");
    assertEquals("bar", adapter.get("foo"));
  }

  @Test
  void get_unwrapsMemorySection() {
    ConfigurationSection section = config.createSection("sub");
    section.set("a", 1);
    section.set("b", 2);
    Object result = adapter.get("sub");
    assertInstanceOf(Map.class, result);
    Map<?, ?> map = (Map<?, ?>) result;
    assertEquals(1, map.get("a"));
    assertEquals(2, map.get("b"));
  }

  @Test
  void get_returnsNullForMissingKey() {
    assertNull(adapter.get("missing"));
  }

  @Test
  void contains_returnsTrueForExistingKey() {
    config.set("foo", "bar");
    assertTrue(adapter.contains("foo"));
  }

  @Test
  void contains_returnsFalseForMissingKey() {
    assertFalse(adapter.contains("missing"));
  }

  @Test
  void set_storesValue() {
    adapter.set("foo", "bar");
    assertEquals("bar", config.getString("foo"));
  }

  @Test
  void reload_invokesPluginReload() {
    adapter.reload();
    org.mockito.Mockito.verify(plugin).reloadConfig();
  }

  @Test
  void save_invokesPluginSave() {
    adapter.save();
    org.mockito.Mockito.verify(plugin).saveConfig();
  }

  @Test
  void unwrap_returnsSameValueForNonSection() {
    assertEquals("plain", PaperConfigurationAdapter.unwrap("plain"));
    assertEquals(42, PaperConfigurationAdapter.unwrap(42));
    assertNull(PaperConfigurationAdapter.unwrap(null));
  }

  @Test
  void unwrap_expandsMemorySection() {
    YamlConfiguration yaml = new YamlConfiguration();
    ConfigurationSection section = yaml.createSection("nested");
    section.set("x", 10);
    Object result = PaperConfigurationAdapter.unwrap(section);
    assertInstanceOf(Map.class, result);
    Map<?, ?> map = (Map<?, ?>) result;
    assertEquals(10, map.get("x"));
  }

  // ===== startup overrides: -Dsexidium.<path>=<value> =========================================
  //
  // These are what let every node run off ONE shared config file. The file is identical across the
  // network except for six values describing the node itself, and a per-node copy of a 1700-line file
  // carrying six numbers is exactly how the copies drift.

  @org.junit.jupiter.api.AfterEach
  void clearOverrides() {
    System.getProperties().stringPropertyNames().stream()
        .filter(key -> key.startsWith(PaperConfigurationAdapter.OVERRIDE_PREFIX))
        .forEach(System::clearProperty);
  }

  @Test
  void override_beatsTheFile() {
    config.set("network.node.id", "from-the-shared-file");
    System.setProperty("sexidium.network.node.id", "worker-3");
    assertEquals("worker-3", adapter.getString("network.node.id", ""),
        "the shared file holds another node's identity; the command line is what says who this is");
  }

  @Test
  void override_appliesToNumbersAndBooleans() {
    config.set("api.port", 8800);
    config.set("network.enabled", false);
    System.setProperty("sexidium.api.port", "8830");
    System.setProperty("sexidium.network.enabled", "true");
    assertEquals(8830L, adapter.getLong("api.port", 0));
    assertEquals(8830, adapter.getInt("api.port", 0), "getInt must honour it too, not just getLong");
    assertTrue(adapter.getBoolean("network.enabled", false));
  }

  @Test
  void override_absentOrBlank_fallsBackToTheFile() {
    config.set("api.port", 8800);
    System.setProperty("sexidium.api.port", "   ");
    assertEquals(8800L, adapter.getLong("api.port", 0),
        "a blank property is 'not set', not 'set to empty' -- otherwise an unset shell variable in the"
            + " launcher would silently blank a real setting");
  }

  @Test
  void override_thatIsNotANumber_refusesToStart() {
    config.set("api.port", 8800);
    System.setProperty("sexidium.api.port", "not-a-port");
    // Falling back to the file here would boot this node on ANOTHER node's port, because the file is
    // shared. Loud is the only safe answer.
    assertThrows(IllegalStateException.class, () -> adapter.getLong("api.port", 0));
  }

  @Test
  void override_listIsCommaSeparated_andDashMeansEmpty() {
    config.set("network.node.capabilities", List.of("lobby"));
    System.setProperty("sexidium.network.node.capabilities", "experiences, minigames ,api-host");
    assertEquals(List.of("experiences", "minigames", "api-host"),
        adapter.getStringList("network.node.capabilities"));

    System.setProperty("sexidium.network.node.capabilities", "-");
    assertEquals(List.of(), adapter.getStringList("network.node.capabilities"),
        "a blank property means 'unset', so there has to be some way to say 'override to nothing'");
  }

  @Test
  void override_doesNotLeakAcrossUnrelatedKeys() {
    config.set("api.port", 8800);
    System.setProperty("sexidium.network.node.id", "worker-1");
    assertEquals(8800L, adapter.getLong("api.port", 0));
  }

}
