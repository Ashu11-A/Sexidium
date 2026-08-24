package com.sexidium.core.platform;

import com.sexidium.core.game.Game;

public interface EventDispatcherAdapter {
  void registerGame(Game game);

  void unregisterGame(Game game);
}
