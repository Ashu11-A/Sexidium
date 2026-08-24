package com.sexidium.paper.adapter.inventory;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperInventorySerializerTest {

  @Test
  void serialize_withNonPaperAdapter_returnsNull() {
    PaperInventorySerializer serializer = new PaperInventorySerializer();
    PlayerAdapter nonPaper = mock(PlayerAdapter.class);
    assertNull(serializer.serialize(nonPaper));
  }

  @Test
  void serialize_returnsBase64EncodedData() {
    PaperInventorySerializer serializer = new PaperInventorySerializer();
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    ItemStack[] contents = new ItemStack[36];
    when(player.getInventory()).thenReturn(inventory);
    when(inventory.getContents()).thenReturn(contents);

    PaperPlayerAdapter adapter = new PaperPlayerAdapter(player);
    String result = serializer.serialize(adapter);
    assertNotNull(result);
    byte[] decoded = Base64.getDecoder().decode(result);
    assertNotNull(decoded);
  }

  @Test
  void deserializeInto_withNonPaperAdapter_doesNothing() {
    PaperInventorySerializer serializer = new PaperInventorySerializer();
    PlayerAdapter nonPaper = mock(PlayerAdapter.class);
    serializer.deserializeInto(nonPaper, "fake");
  }

  @Test
  void deserializeInto_withNullData_doesNothing() {
    PaperInventorySerializer serializer = new PaperInventorySerializer();
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    PaperPlayerAdapter adapter = new PaperPlayerAdapter(player);
    serializer.deserializeInto(adapter, null);
  }

  @Test
  void deserializeInto_withBlankData_doesNothing() {
    PaperInventorySerializer serializer = new PaperInventorySerializer();
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    PaperPlayerAdapter adapter = new PaperPlayerAdapter(player);
    serializer.deserializeInto(adapter, "   ");
  }

  @Test
  void deserializeInto_withInvalidBase64_doesNothing() {
    PaperInventorySerializer serializer = new PaperInventorySerializer();
    Player player = mock(Player.class);
    PlayerInventory inventory = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inventory);
    PaperPlayerAdapter adapter = new PaperPlayerAdapter(player);
    serializer.deserializeInto(adapter, "!!!invalid-base64!!!");
  }
}
