package com.sexidium.paper.adapter.inventory;

import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.core.platform.InventorySerializer;
import com.sexidium.core.platform.PlayerAdapter;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

public final class PaperInventorySerializer implements InventorySerializer {
  @Override
  public String serialize(PlayerAdapter playerAdapter) {
    if (!(playerAdapter instanceof PaperPlayerAdapter paperAdapter)) {
      return null;
    }
    try {
      byte[] bytes = ItemStack.serializeItemsAsBytes(paperAdapter.handle().getInventory().getContents());
      return Base64.getEncoder().encodeToString(bytes);
    } catch (Throwable throwable) {
      return null;
    }
  }

  @Override
  public void deserializeInto(PlayerAdapter playerAdapter, String serializedInventory) {
    if (!(playerAdapter instanceof PaperPlayerAdapter paperAdapter)
        || serializedInventory == null
        || serializedInventory.isBlank()) {
      return;
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(serializedInventory);
      paperAdapter.handle().getInventory().setContents(ItemStack.deserializeItemsFromBytes(bytes));
    } catch (Throwable ignored) {
    }
  }
}
