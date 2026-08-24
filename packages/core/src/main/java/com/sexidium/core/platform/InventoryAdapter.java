package com.sexidium.core.platform;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;

import java.util.List;
import java.util.Map;

public interface InventoryAdapter {
  void clear();

  boolean contains(ItemKey itemKey);

  default int count(ItemKey itemKey) {
    if (itemKey == null) {
      return 0;
    }
    int total = 0;
    for (ItemStackData itemStackData : storageContents()) {
      if (itemStackData != null && itemKey.equals(itemStackData.itemKey())) {
        total += itemStackData.amount();
      }
    }
    return total;
  }

  void add(ItemStackData itemStackData);

  List<ItemStackData> storageContents();

  default int storageCapacity() {
    return 36;
  }

  void setStorageContents(List<ItemStackData> itemStacks);

  /**
   * Slot-indexed view of the storage inventory: the returned list has length {@link #storageCapacity()}
   * and preserves empty slots as {@code null} (unlike {@link #storageContents()}, which platforms may
   * compact). Used by the Shared Inventory challenge for slot-level diffing. The default rebuilds a
   * padded list from the (possibly compacted) {@link #storageContents()} so test/noop adapters keep
   * working; platforms override for true slot fidelity.
   */
  default List<ItemStackData> storageSlots() {
    List<ItemStackData> contents = storageContents();
    int capacity = storageCapacity();
    java.util.ArrayList<ItemStackData> slots = new java.util.ArrayList<>(capacity);
    for (int index = 0; index < capacity; index++) {
      slots.add(index < contents.size() ? contents.get(index) : null);
    }
    return slots;
  }

  /** Single storage slot ({@code null} = empty). Default reads through {@link #storageSlots()}. */
  default ItemStackData slot(int index) {
    List<ItemStackData> slots = storageSlots();
    return index >= 0 && index < slots.size() ? slots.get(index) : null;
  }

  /** Writes a single storage slot ({@code null} clears it). Default rebuilds via {@link #setStorageContents}. */
  default void setSlot(int index, ItemStackData stack) {
    if (index < 0 || index >= storageCapacity()) {
      return;
    }
    List<ItemStackData> slots = new java.util.ArrayList<>(storageSlots());
    while (slots.size() <= index) {
      slots.add(null);
    }
    slots.set(index, stack);
    setStorageContents(slots);
  }

  Map<String, List<ItemStackData>> equipmentContents();

  void setEquipmentContents(Map<String, List<ItemStackData>> equipmentContents);
}
