package com.sexidium.core.game.hud;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;

/**
 * The minimal surface a {@link GameHud} (and its toggle {@link HudGestures}) needs from the game that
 * owns it: the shared game context (to reach the platform UI/scheduler/config) and the live online
 * participants. Every {@link com.sexidium.core.game.AbstractGame} implements this, so the unified HUD
 * works identically for an Experience, a Chaos game and every minigame — the single point of the
 * generalization (the experience-specific {@code ExperienceHost} extends this).
 */
public interface HudHost {
  GameContext gameContext();

  /** The online participants the HUD renders a panel for. */
  List<PlayerAdapter> online();
}
