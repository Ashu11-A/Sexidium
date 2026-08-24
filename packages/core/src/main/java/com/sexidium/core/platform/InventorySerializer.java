package com.sexidium.core.platform;

public interface InventorySerializer {
  /** Inert serializer used when a platform does not wire one (e.g. headless/test server adapters). */
  InventorySerializer NOOP = new InventorySerializer() {
    @Override
    public String serialize(PlayerAdapter playerAdapter) {
      return "";
    }

    @Override
    public void deserializeInto(PlayerAdapter playerAdapter, String serializedInventory) {
    }
  };

  String serialize(PlayerAdapter playerAdapter);

  void deserializeInto(PlayerAdapter playerAdapter, String serializedInventory);
}
