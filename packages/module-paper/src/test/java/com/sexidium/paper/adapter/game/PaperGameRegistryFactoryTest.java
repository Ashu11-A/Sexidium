package com.sexidium.paper.adapter.game;

import com.sexidium.core.game.GameRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaperGameRegistryFactoryTest {

  @Test
  void create_returnsNonNullGameRegistry() {
    GameRegistry registry = PaperGameRegistryFactory.create();
    assertNotNull(registry);
  }
}
