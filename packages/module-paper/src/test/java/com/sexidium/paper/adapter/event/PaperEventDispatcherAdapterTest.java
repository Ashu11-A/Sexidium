package com.sexidium.paper.adapter.event;

import com.sexidium.core.game.Game;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PaperEventDispatcherAdapterTest {

  @Test
  void registerGame_doesNotThrow() {
    PaperEventDispatcherAdapter adapter = new PaperEventDispatcherAdapter();
    Game game = mock(Game.class);
    assertDoesNotThrow(() -> adapter.registerGame(game));
  }

  @Test
  void unregisterGame_doesNotThrow() {
    PaperEventDispatcherAdapter adapter = new PaperEventDispatcherAdapter();
    Game game = mock(Game.class);
    assertDoesNotThrow(() -> adapter.unregisterGame(game));
  }

  @Test
  void registerGame_acceptsNull() {
    PaperEventDispatcherAdapter adapter = new PaperEventDispatcherAdapter();
    assertDoesNotThrow(() -> adapter.registerGame(null));
  }

  @Test
  void unregisterGame_acceptsNull() {
    PaperEventDispatcherAdapter adapter = new PaperEventDispatcherAdapter();
    assertDoesNotThrow(() -> adapter.unregisterGame(null));
  }
}
