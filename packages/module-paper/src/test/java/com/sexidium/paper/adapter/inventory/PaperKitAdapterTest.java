package com.sexidium.paper.adapter.inventory;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperKitAdapterTest {

  private JavaPlugin plugin;
  private YamlConfiguration config;
  private PaperKitAdapter adapter;

  @BeforeAll
  static void disableDefaultKitFactory() {
    PaperKitAdapter.setDefaultKitFactory(() -> PaperKitAdapter.paperKitForTest(Collections.emptyList()));
  }

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    config = new YamlConfiguration();
    when(plugin.getConfig()).thenReturn(config);
  }

  private MockedConstruction<ItemStack> mockItemStackConstruction() {
    return Mockito.mockConstruction(ItemStack.class, (mock, context) -> {
      when(mock.isEmpty()).thenReturn(false);
      when(mock.clone()).thenReturn(mock);
    });
  }

  @Test
  void reload_withEmptyKits_createsDefault() {
    try (MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      adapter = new PaperKitAdapter(plugin);
      assertTrue(adapter.exists("default"));
      assertTrue(adapter.names().contains("default"));
    }
  }

  @Test
  void reload_parsesConfiguredKits() {
    config.set("kits.warrior.items", List.of(
        Map.of("material", "DIAMOND_SWORD", "amount", 1),
        Map.of("material", "GOLDEN_APPLE", "amount", 4)
    ));
    Material diamondSword = mock(Material.class);
    Material goldenApple = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("DIAMOND_SWORD")).thenReturn(diamondSword);
      mockedMaterial.when(() -> Material.matchMaterial("GOLDEN_APPLE")).thenReturn(goldenApple);
      when(diamondSword.isAir()).thenReturn(false);
      when(goldenApple.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("warrior"));
  }

  @Test
  void reload_isCaseInsensitive() {
    config.set("kits.Warrior.items", List.of(
        Map.of("material", "DIAMOND_SWORD", "amount", 1)
    ));
    Material diamondSword = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("DIAMOND_SWORD")).thenReturn(diamondSword);
      when(diamondSword.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("warrior"));
    assertTrue(adapter.exists("WARRIOR"));
    assertTrue(adapter.exists("Warrior"));
  }

  @Test
  void reload_skipsAirMaterial() {
    config.set("kits.broken.items", List.of(
        Map.of("material", "STONE", "amount", 1)
    ));
    Material stone = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("STONE")).thenReturn(stone);
      when(stone.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("broken"));
  }

  @Test
  void reload_skipsUnknownMaterial() {
    config.set("kits.broken.items", List.of(
        Map.of("material", "NOT_A_REAL_MATERIAL", "amount", 1)
    ));
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS)) {
      mockedMaterial.when(() -> Material.matchMaterial("NOT_A_REAL_MATERIAL")).thenReturn(null);
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("broken"));
  }

  @Test
  void reload_skipsMissingMaterial() {
    config.set("kits.broken.items", List.of(
        Map.of("amount", 1)
    ));
    adapter = new PaperKitAdapter(plugin);
    assertTrue(adapter.exists("broken"));
  }

  @Test
  void reload_clampsAmountToMinimumOne() {
    config.set("kits.tiny.items", List.of(
        Map.of("material", "STONE", "amount", -5)
    ));
    Material stone = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("STONE")).thenReturn(stone);
      when(stone.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("tiny"));
  }

  @Test
  void reload_skipsNonNumberAmount() {
    config.set("kits.broken.items", List.of(
        Map.of("material", "STONE", "amount", "not-a-number")
    ));
    Material stone = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("STONE")).thenReturn(stone);
      when(stone.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("broken"));
  }

  @Test
  void reload_skipsNonSectionKitEntries() {
    config.set("kits.invalid", "this-is-not-a-section");
    try (MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      adapter = new PaperKitAdapter(plugin);
    }
    assertFalse(adapter.exists("invalid"));
    assertTrue(adapter.exists("default"));
  }

  @Test
  void apply_withNonPaperAdapter_returnsFalse() {
    try (MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      adapter = new PaperKitAdapter(plugin);
    }
    PlayerAdapter nonPaper = mock(PlayerAdapter.class);
    assertFalse(adapter.apply(nonPaper, "default"));
  }

  @Test
  void apply_withUnknownKit_returnsFalse() {
    try (MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      adapter = new PaperKitAdapter(plugin);
    }
    Player player = mock(Player.class);
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(player);
    assertFalse(adapter.apply(playerAdapter, "unknown"));
  }

  @Test
  void apply_withKnownKit_addsItemsToInventory() {
    config.set("kits.swords.items", List.of(
        Map.of("material", "IRON_SWORD", "amount", 1)
    ));
    Material ironSword = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("IRON_SWORD")).thenReturn(ironSword);
      when(ironSword.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }

    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(player);
    assertTrue(adapter.apply(playerAdapter, "swords"));
    verify(inventory, times(1)).addItem(org.mockito.ArgumentMatchers.any(ItemStack.class));
  }

  @Test
  void exists_returnsTrueForKnownKit() {
    try (MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("default"));
  }

  @Test
  void exists_returnsFalseForUnknownKit() {
    try (MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      adapter = new PaperKitAdapter(plugin);
    }
    assertFalse(adapter.exists("unknown"));
  }

  @Test
  void exists_handlesNullKitName() {
    try (MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      adapter = new PaperKitAdapter(plugin);
    }
    assertFalse(adapter.exists(null));
  }

  @Test
  void names_returnsConfiguredKitNames() {
    config.set("kits.alpha.items", List.of(Map.of("material", "STONE", "amount", 1)));
    config.set("kits.beta.items", List.of(Map.of("material", "DIRT", "amount", 1)));
    Material stone = mock(Material.class);
    Material dirt = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("STONE")).thenReturn(stone);
      mockedMaterial.when(() -> Material.matchMaterial("DIRT")).thenReturn(dirt);
      when(stone.isAir()).thenReturn(false);
      when(dirt.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }
    Set<String> names = adapter.names();
    assertEquals(2, names.size());
    assertTrue(names.contains("alpha"));
    assertTrue(names.contains("beta"));
  }

  @Test
  void reload_clearsPreviousKits() {
    config.set("kits.first.items", List.of(Map.of("material", "STONE", "amount", 1)));
    Material stone = mock(Material.class);
    Material dirt = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("STONE")).thenReturn(stone);
      mockedMaterial.when(() -> Material.matchMaterial("DIRT")).thenReturn(dirt);
      when(stone.isAir()).thenReturn(false);
      when(dirt.isAir()).thenReturn(false);
      adapter = new PaperKitAdapter(plugin);
    }
    assertTrue(adapter.exists("first"));

    config.set("kits.second.items", List.of(Map.of("material", "DIRT", "amount", 1)));
    config.set("kits.first", null);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = mockItemStackConstruction()) {
      mockedMaterial.when(() -> Material.matchMaterial("DIRT")).thenReturn(dirt);
      when(dirt.isAir()).thenReturn(false);
      adapter.reload();
    }
    assertFalse(adapter.exists("first"));
    assertTrue(adapter.exists("second"));
  }
}
