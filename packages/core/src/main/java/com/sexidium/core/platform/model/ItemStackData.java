package com.sexidium.core.platform.model;

import java.util.Map;

public record ItemStackData(ItemKey itemKey, int amount, Map<String, String> metadata) {
  public ItemStackData {
    amount = Math.max(1, amount);
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
  }
}
