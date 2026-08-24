package com.sexidium.core.game;

import com.sexidium.core.game.GameEvents.CancellableGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.InventoryChangeGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDropItemGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.platform.model.ItemKey;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item-drop event, which exists because a mode had no way to REFUSE a drop — the platform only ever
 * reported one after the fact.
 */
class GameEventsDropItemTest {

  private static final class RecordingChallenge extends Challenge {
    private final List<String> seen = new java.util.ArrayList<>();

    private RecordingChallenge() {
      super("recorder", "Recorder");
    }

    @Override
    public void register(ChallengeRegistry registry) {
    }

    @Override
    public void onPlayerDropItem(PlayerDropItemGameEvent event) {
      seen.add("drop:" + (event.itemKey() == null ? "?" : event.itemKey().qualifiedName()));
      event.setCancelled(true);
    }

    @Override
    public void onInventoryChange(InventoryChangeGameEvent event) {
      seen.add("inventory");
    }
  }

  @Test
  void isCancellable_soAModeCanRefuseADrop() {
    PlayerDropItemGameEvent event = new PlayerDropItemGameEvent(null, ItemKey.minecraft("diamond"));

    assertInstanceOf(CancellableGameEvent.class, event);
    assertFalse(event.cancelled());

    event.setCancelled(true);
    assertTrue(event.cancelled());
  }

  /**
   * The reason this is a new event rather than a reuse of {@link InventoryChangeGameEvent}: a drop and an
   * inventory change have to stay distinguishable, or a refused drop would look like a refused reconcile
   * to the shared-inventory challenge that consumes the change event.
   */
  @Test
  void isNotAnInventoryChangeEvent() {
    GameEvent event = new PlayerDropItemGameEvent(null, ItemKey.minecraft("dirt"));

    assertFalse(event instanceof InventoryChangeGameEvent);
  }

  @Test
  void challengeOnEvent_routesItToItsOwnTypedHook() {
    RecordingChallenge challenge = new RecordingChallenge();

    challenge.onEvent(new PlayerDropItemGameEvent(null, ItemKey.minecraft("emerald")));
    challenge.onEvent(new InventoryChangeGameEvent(null));

    assertEquals(List.of("drop:minecraft:emerald", "inventory"), challenge.seen);
  }

  @Test
  void carriesWhatWasThrown_andToleratesAPlatformThatCannotSay() {
    assertEquals(ItemKey.minecraft("stone"),
        new PlayerDropItemGameEvent(null, ItemKey.minecraft("stone")).itemKey());
    assertNull(new PlayerDropItemGameEvent(null, null).itemKey(),
        "a platform that cannot identify the stack still gets to offer the drop");
  }
}
