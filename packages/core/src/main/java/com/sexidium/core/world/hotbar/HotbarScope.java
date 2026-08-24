package com.sexidium.core.world.hotbar;

/**
 * The scope a player is in, which selects their hotbar {@link HotbarProfile}. (Named to avoid confusion
 * with {@link com.sexidium.core.world.WorldKind}, which is the unrelated world-lease lifecycle class.)
 *
 * <p>Only {@link #LOBBY} carries managed items today; {@link #MINIGAME} and {@link #EXPERIENCE} keep an
 * empty profile because their games own the player's kit. Entering a non-lobby scope therefore resolves
 * to zero managed items, and the platform strips managed items on that transition — the single mechanism
 * that guarantees lobby items never leak into a match and match items never leak back into the lobby.</p>
 */
public enum HotbarScope {
  LOBBY,
  MINIGAME,
  EXPERIENCE
}
