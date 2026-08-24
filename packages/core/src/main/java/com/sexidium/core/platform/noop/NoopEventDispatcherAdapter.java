package com.sexidium.core.platform.noop;

import com.sexidium.core.game.Game;
import com.sexidium.core.platform.EventDispatcherAdapter;

public final class NoopEventDispatcherAdapter implements EventDispatcherAdapter {
  @Override
  public void registerGame(Game game) {
  }

  @Override
  public void unregisterGame(Game game) {
  }
}
