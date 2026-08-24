package com.sexidium.paper.adapter.game;

import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameRegistry;

public final class PaperGameRegistryFactory {
  private PaperGameRegistryFactory() {
  }

  public static GameRegistry create() {
    return CoreGameRegistryInitializer.create();
  }
}
