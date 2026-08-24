package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.modes.minigames.tntwar.TntWarConfig;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Pure builder of the TNT-War starting loadout: a TNT stack in slot 0, an optional flint-and-steel in
 * slot 1, and the build-menu chest opener pinned to the last hotbar slot (8). The backing list is sized
 * to the inventory's storage capacity and pre-filled with {@code null} for the empty slots — this is the
 * known TNT-War NPE fix (a sparse loadout must not collapse to a short list), so it is preserved exactly.
 */
final class TntWarLoadout {
  private TntWarLoadout() {
  }

  /** Builds the storage-contents list for one player given the inventory's storage capacity. */
  static List<ItemStackData> build(TntWarConfig config, int storageCapacity) {
    List<ItemStackData> hotbar = new ArrayList<>(Collections.nCopies(storageCapacity, null));
    hotbar.set(0, new ItemStackData(ItemKey.minecraft("tnt"), config.tntAmount(), Map.of()));
    if (config.giveFlintAndSteel()) {
      hotbar.set(1, new ItemStackData(ItemKey.minecraft("flint_and_steel"), 1, Map.of()));
    }
    hotbar.set(8, new ItemStackData(config.buildMenuItem(), 1, Map.of()));
    return hotbar;
  }
}
