package com.sexidium.paper.adapter.inventory;

import com.sexidium.paper.adapter.util.PaperConverters;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PaperInventoryAdapter implements InventoryAdapter {
  // Metadata key carrying the FULL native item (durability + all data components) as Base64-encoded bytes,
  // so a round-trip through the platform-neutral ItemStackData reconstructs the exact stack instead of a
  // bare material+amount. Shared Inventory re-snapshots and rebroadcasts inventories every few ticks; without
  // this, every sync rebuilt a fresh ItemStack and reset tool damage / wiped enchants & components.
  private static final String RAW_KEY = "paper:item-bytes";
  // Optional MiniMessage display name carried in metadata (e.g. the map-editor hotbar tools). Only applied
  // when the value carries a MiniMessage tag, so legacy callers that stash a bare translation-key path here
  // (which were always ignored before) keep their default vanilla item name.
  private static final String NAME_KEY = "name";
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

  private final PlayerInventory inventory;

  public PaperInventoryAdapter(PlayerInventory inventory) {
    this.inventory = inventory;
  }

  /** Captures an item as ItemStackData, stashing the full serialized stack so the write side restores it
   * exactly. Returns null for air/empty so callers can treat it as an empty slot. */
  private static ItemStackData toData(ItemStack itemStack) {
    if (itemStack == null || itemStack.getType().isAir()) {
      return null;
    }
    String raw = Base64.getEncoder().encodeToString(itemStack.serializeAsBytes());
    return new ItemStackData(
        PaperConverters.toCore(itemStack.getType()), itemStack.getAmount(), Map.of(RAW_KEY, raw));
  }

  /** Rebuilds the exact native item from the serialized bytes when present (preserving durability and every
   * component); otherwise falls back to a fresh material+amount stack. Null/air yields a null (empty) slot. */
  private static ItemStack toItemStack(ItemStackData itemStackData) {
    if (itemStackData == null) {
      return null;
    }
    String raw = itemStackData.metadata().get(RAW_KEY);
    if (raw != null) {
      try {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(raw));
      } catch (RuntimeException ignored) {
        // Corrupt/cross-version blob — fall through to the material+amount reconstruction below.
      }
    }
    Material material = PaperConverters.toNative(itemStackData.itemKey());
    if (material == Material.AIR) {
      return null;
    }
    ItemStack stack = new ItemStack(material, itemStackData.amount());
    String name = itemStackData.metadata().get(NAME_KEY);
    if (name != null && name.indexOf('<') >= 0) {
      ItemMeta meta = stack.getItemMeta();
      if (meta != null) {
        meta.displayName(MINI_MESSAGE.deserialize(name).decoration(TextDecoration.ITALIC, false));
        stack.setItemMeta(meta);
      }
    }
    return stack;
  }

  @Override
  public void clear() {
    inventory.clear();
  }

  @Override
  public boolean contains(ItemKey itemKey) {
    return inventory.contains(PaperConverters.toNative(itemKey));
  }

  @Override
  public void add(ItemStackData itemStackData) {
    // Route through toItemStack so a supplied display name (and any serialized blob) is honoured — a
    // bare material+amount add silently dropped custom item names such as the fugitive tracker compass.
    ItemStack stack = toItemStack(itemStackData);
    if (stack != null) {
      inventory.addItem(stack);
    }
  }

  @Override
  public List<ItemStackData> storageContents() {
    List<ItemStackData> contents = new ArrayList<>();
    for (ItemStack itemStack : inventory.getStorageContents()) {
      ItemStackData data = toData(itemStack);
      if (data != null) {
        contents.add(data);
      }
    }
    return contents;
  }

  @Override
  public int storageCapacity() {
    return inventory.getStorageContents().length;
  }

  @Override
  public List<ItemStackData> storageSlots() {
    // Slot-indexed: preserve empty slots as null (do NOT compact like storageContents).
    ItemStack[] raw = inventory.getStorageContents();
    List<ItemStackData> slots = new ArrayList<>(raw.length);
    for (ItemStack itemStack : raw) {
      slots.add(toData(itemStack));
    }
    return slots;
  }

  @Override
  public ItemStackData slot(int index) {
    if (index < 0 || index >= inventory.getStorageContents().length) {
      return null;
    }
    return toData(inventory.getItem(index));
  }

  @Override
  public void setSlot(int index, ItemStackData stack) {
    if (index < 0 || index >= inventory.getStorageContents().length) {
      return;
    }
    inventory.setItem(index, toItemStack(stack));
  }

  @Override
  public void setStorageContents(List<ItemStackData> itemStacks) {
    List<ItemStack> convertedItems = new ArrayList<>();
    if (itemStacks != null) {
      for (ItemStackData itemStackData : itemStacks) {
        // A null entry is an EMPTY slot — loadouts (e.g. TNT War) pass sparse lists with items at a
        // few indices and null everywhere else. toItemStack maps null/air to a null slot.
        convertedItems.add(toItemStack(itemStackData));
      }
    }
    inventory.setStorageContents(convertedItems.toArray(ItemStack[]::new));
  }

  @Override
  public Map<String, List<ItemStackData>> equipmentContents() {
    Map<String, List<ItemStackData>> equipment = new LinkedHashMap<>();
    equipment.put("armor", convert(inventory.getArmorContents()));
    equipment.put("extra", convert(inventory.getExtraContents()));
    return equipment;
  }

  @Override
  public void setEquipmentContents(Map<String, List<ItemStackData>> equipmentContents) {
    if (equipmentContents == null) {
      return;
    }
    if (equipmentContents.containsKey("armor")) {
      inventory.setArmorContents(toItemStacks(equipmentContents.get("armor")));
    }
    if (equipmentContents.containsKey("extra")) {
      inventory.setExtraContents(toItemStacks(equipmentContents.get("extra")));
    }
  }

  private List<ItemStackData> convert(ItemStack[] itemStacks) {
    List<ItemStackData> convertedItems = new ArrayList<>();
    if (itemStacks == null) {
      return convertedItems;
    }
    for (ItemStack itemStack : itemStacks) {
      convertedItems.add(toData(itemStack));
    }
    return convertedItems;
  }

  private ItemStack[] toItemStacks(List<ItemStackData> itemStacks) {
    if (itemStacks == null) {
      return new ItemStack[0];
    }
    List<ItemStack> convertedItems = new ArrayList<>();
    for (ItemStackData itemStackData : itemStacks) {
      convertedItems.add(toItemStack(itemStackData));
    }
    return convertedItems.toArray(ItemStack[]::new);
  }
}
