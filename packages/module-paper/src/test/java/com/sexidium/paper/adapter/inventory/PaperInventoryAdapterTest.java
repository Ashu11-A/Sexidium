package com.sexidium.paper.adapter.inventory;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperInventoryAdapterTest {

  private PlayerInventory inventory;
  private PaperInventoryAdapter adapter;

  @BeforeEach
  void setUp() {
    inventory = mock(PlayerInventory.class);
    adapter = new PaperInventoryAdapter(inventory);
  }

  @Test
  void clear_invokesInventoryClear() {
    adapter.clear();
    Mockito.verify(inventory).clear();
  }

  @Test
  void contains_returnsTrueWhenMaterialPresent() {
    Material stone = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS)) {
      mockedMaterial.when(() -> Material.matchMaterial("STONE")).thenReturn(stone);
      when(inventory.contains(stone)).thenReturn(true);
      assertTrue(adapter.contains(ItemKey.minecraft("stone")));
    }
  }

  @Test
  void contains_returnsFalseWhenMaterialAbsent() {
    Material stone = mock(Material.class);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS)) {
      mockedMaterial.when(() -> Material.matchMaterial("STONE")).thenReturn(stone);
      when(inventory.contains(stone)).thenReturn(false);
      assertFalse(adapter.contains(ItemKey.minecraft("stone")));
    }
  }

  @Test
  void storageContents_emptyInventory() {
    when(inventory.getStorageContents()).thenReturn(new ItemStack[36]);
    assertTrue(adapter.storageContents().isEmpty());
  }

  @Test
  void storageContents_filtersOutAirAndNullFromData() {
    ItemStack stone = mockItemStack("stone", 5, false);
    ItemStack diamond = mockItemStack("diamond", 1, false);
    ItemStack air = mockItemStack("air", 1, true);
    ItemStack[] contents = new ItemStack[]{stone, null, air, diamond};
    when(inventory.getStorageContents()).thenReturn(contents);

    List<ItemStackData> result = adapter.storageContents();
    assertEquals(2, result.size());
    assertEquals("stone", result.get(0).itemKey().value());
    assertEquals("diamond", result.get(1).itemKey().value());
  }

  @Test
  void setStorageContents_withNullList_pushesEmpty() {
    adapter.setStorageContents(null);
    Mockito.verify(inventory).setStorageContents(any(ItemStack[].class));
  }

  @Test
  void equipmentContents_collectsArmorAndExtra() {
    ItemStack armor1 = mockItemStack("diamond_helmet", 1, false);
    ItemStack extra1 = mockItemStack("arrow", 10, false);
    when(inventory.getArmorContents()).thenReturn(new ItemStack[]{armor1});
    when(inventory.getExtraContents()).thenReturn(new ItemStack[]{extra1});

    Map<String, List<ItemStackData>> result = adapter.equipmentContents();
    assertEquals(2, result.size());
    assertTrue(result.containsKey("armor"));
    assertTrue(result.containsKey("extra"));
    assertEquals(1, result.get("armor").size());
    assertEquals(1, result.get("extra").size());
  }

  @Test
  void equipmentContents_includesNullsForAir() {
    ItemStack armor1 = mockItemStack("diamond_helmet", 1, false);
    when(inventory.getArmorContents()).thenReturn(new ItemStack[]{null, armor1});
    when(inventory.getExtraContents()).thenReturn(new ItemStack[0]);

    Map<String, List<ItemStackData>> result = adapter.equipmentContents();
    assertNull(result.get("armor").get(0));
    assertEquals("diamond_helmet", result.get("armor").get(1).itemKey().value());
    assertTrue(result.get("extra").isEmpty());
  }

  @Test
  void setEquipmentContents_withNullMap_doesNothing() {
    adapter.setEquipmentContents(null);
    Mockito.verify(inventory, Mockito.never()).setArmorContents(any());
    Mockito.verify(inventory, Mockito.never()).setExtraContents(any());
  }

  @Test
  void setEquipmentContents_withEmptyMap_doesNothing() {
    adapter.setEquipmentContents(new LinkedHashMap<>());
    Mockito.verify(inventory, Mockito.never()).setArmorContents(any());
    Mockito.verify(inventory, Mockito.never()).setExtraContents(any());
  }

  @Test
  void setEquipmentContents_setsBothArmorAndExtra() {
    Material armorMat = mock(Material.class);
    Material extraMat = mock(Material.class);
    when(armorMat.isAir()).thenReturn(false);
    when(extraMat.isAir()).thenReturn(false);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = Mockito.mockConstruction(ItemStack.class, (mock, context) -> {
           when(mock.clone()).thenReturn(mock);
         })) {
      mockedMaterial.when(() -> Material.matchMaterial("DIAMOND_HELMET")).thenReturn(armorMat);
      mockedMaterial.when(() -> Material.matchMaterial("ARROW")).thenReturn(extraMat);
      Map<String, List<ItemStackData>> equipment = new LinkedHashMap<>();
      equipment.put("armor", List.of(new ItemStackData(ItemKey.minecraft("diamond_helmet"), 1, Map.of())));
      equipment.put("extra", List.of(new ItemStackData(ItemKey.minecraft("arrow"), 10, Map.of())));
      adapter.setEquipmentContents(equipment);
    }
    Mockito.verify(inventory).setArmorContents(any());
    Mockito.verify(inventory).setExtraContents(any());
  }

  @Test
  void setEquipmentContents_setsOnlyArmorWhenExtraAbsent() {
    Material armorMat = mock(Material.class);
    when(armorMat.isAir()).thenReturn(false);
    try (MockedStatic<Material> mockedMaterial = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction<ItemStack> ignored = Mockito.mockConstruction(ItemStack.class, (mock, context) -> {
           when(mock.clone()).thenReturn(mock);
         })) {
      mockedMaterial.when(() -> Material.matchMaterial("DIAMOND_HELMET")).thenReturn(armorMat);
      Map<String, List<ItemStackData>> equipment = new LinkedHashMap<>();
      equipment.put("armor", List.of(new ItemStackData(ItemKey.minecraft("diamond_helmet"), 1, Map.of())));
      adapter.setEquipmentContents(equipment);
    }
    Mockito.verify(inventory).setArmorContents(any());
    Mockito.verify(inventory, Mockito.never()).setExtraContents(any());
  }

  private ItemStack mockItemStack(String key, int amount, boolean air) {
    Material material = mock(Material.class);
    when(material.isAir()).thenReturn(air);
    when(material.name()).thenReturn(key.toUpperCase());
    when(material.getKey()).thenReturn(org.bukkit.NamespacedKey.minecraft(key));
    ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(material);
    when(stack.getAmount()).thenReturn(amount);
    when(stack.isEmpty()).thenReturn(false);
    // The adapter now stashes the full serialized stack so a sync round-trip preserves durability and
    // components; the read path Base64-encodes these bytes, so the mock must return a non-null array.
    when(stack.serializeAsBytes()).thenReturn(new byte[]{1, 2, 3});
    return stack;
  }
}
