package com.sexidium.core.game;

import java.util.List;

@FunctionalInterface
public interface GameFactory {
  Game create(GameContext gameContext, String requestedModeId, List<String> modeArgs);
}
