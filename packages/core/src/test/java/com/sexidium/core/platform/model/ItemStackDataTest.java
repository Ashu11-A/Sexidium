package com.sexidium.core.platform.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemStackDataTest {

  @Test
  void amount_clampedToOne() {
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), 0, null);
    assertEquals(1, data.amount());
  }

  @Test
  void amount_negativeClampedToOne() {
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), -5, null);
    assertEquals(1, data.amount());
  }

  @Test
  void amount_positiveRetained() {
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), 64, null);
    assertEquals(64, data.amount());
  }

  @Test
  void nullMetadata_becomesEmptyMap() {
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), 1, null);
    assertNotNull(data.metadata());
    assertTrue(data.metadata().isEmpty());
  }

  @Test
  void metadata_isCopied() {
    Map<String, String> meta = new HashMap<>();
    meta.put("key", "value");
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), 1, meta);
    meta.put("other", "extra");
    assertEquals(1, data.metadata().size());
  }

  @Test
  void metadata_isImmutable() {
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), 1, Map.of("k", "v"));
    assertThrows(UnsupportedOperationException.class, () -> data.metadata().put("x", "y"));
  }
}
