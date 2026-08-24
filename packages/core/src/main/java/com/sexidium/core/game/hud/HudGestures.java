package com.sexidium.core.game.hud;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The two HUD-view toggle gestures shared by every game: a double-tap of the sneak key (PC) and holding
 * a straight-up look for one second (mobile). Both advance the per-player view in {@link GameHud}
 * (FULL → COMPACT → HIDDEN → FULL). This class owns only the gesture timing state; the owning game
 * forwards the sneak event and the per-tick poll, and cleans a leaving player's state.
 */
public final class HudGestures {
  private static final float LOOK_UP_PITCH = -75.0F;
  private static final long LOOK_UP_HOLD_MS = 1000L;
  private static final long DOUBLE_SNEAK_MS = 350L;

  private final HudHost host;
  private final GameHud hud;
  private final Map<UUID, Long> lastSneakTapMillis = new HashMap<>();
  private final Map<UUID, Long> lookUpSinceMillis = new HashMap<>();
  private final Set<UUID> lookUpToggled = new HashSet<>();

  public HudGestures(HudHost host, GameHud hud) {
    this.host = host;
    this.hud = hud;
  }

  /**
   * Mobile HUD toggle: looking straight up (pitch near the sky-pole) continuously for one second
   * advances the view. Fires once per sustained look-up — the player must look away and back to toggle
   * again. Polled here because a perfectly still look-up sends no move events.
   */
  public void pollLookUpToggle() {
    long now = System.currentTimeMillis();
    for (PlayerAdapter player : host.online()) {
      UUID id = player.uniqueId();
      WorldPosition position = player.position();
      if (position == null || position.pitch() > LOOK_UP_PITCH) {
        lookUpSinceMillis.remove(id);
        lookUpToggled.remove(id);
        continue;
      }
      Long since = lookUpSinceMillis.putIfAbsent(id, now);
      long start = since == null ? now : since;
      if (now - start >= LOOK_UP_HOLD_MS && lookUpToggled.add(id)) {
        hud.cycle(player);
      }
    }
  }

  /**
   * PC HUD toggle: a double-tap of the sneak key (two sneak-presses within the window). Single
   * sneaking is untouched, so normal play is unaffected.
   */
  public void onSneak(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    UUID id = player.uniqueId();
    long now = System.currentTimeMillis();
    Long previousTap = lastSneakTapMillis.remove(id);
    if (previousTap != null && now - previousTap <= DOUBLE_SNEAK_MS) {
      hud.cycle(player);
    } else {
      lastSneakTapMillis.put(id, now);
    }
  }

  /** Drops a leaving player's gesture timing state. */
  public void forget(UUID playerId) {
    lastSneakTapMillis.remove(playerId);
    lookUpSinceMillis.remove(playerId);
    lookUpToggled.remove(playerId);
  }
}
