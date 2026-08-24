package com.sexidium.core.world.lobby;

import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameModeDescriptor;

import java.util.Locale;

/**
 * Single source of minigame-id resolution and minimum-player lookup, replacing the duplicate scanners
 * that used to live in {@code MatchmakingManager} and {@code MatchLobbyManager}.
 */
public final class ModeResolver {
  private final MatchLauncher games;

  public ModeResolver(MatchLauncher games) {
    this.games = games;
  }

  /** Resolves a minigame id/alias to its descriptor, or null when it is not a minigame. */
  public GameModeDescriptor resolveMinigame(String modeId) {
    if (modeId == null) {
      return null;
    }
    String wanted = modeId.toLowerCase(Locale.ROOT).trim();
    for (GameModeDescriptor descriptor : games.descriptors()) {
      if (!CoreGameRegistryInitializer.CATEGORY_MINIGAMES.equals(descriptor.category())) {
        continue;
      }
      if (descriptor.modeId().equals(wanted) || descriptor.aliases().contains(wanted)) {
        return descriptor;
      }
    }
    return null;
  }

  /** The minimum players for a resolved canonical mode id. Minigames need an opponent, so floored at 2. */
  public int minPlayers(String modeId) {
    if (modeId == null) {
      return 2;
    }
    for (GameModeDescriptor descriptor : games.descriptors()) {
      if (descriptor.modeId().equals(modeId)) {
        return Math.max(2, descriptor.minPlayers());
      }
    }
    return 2;
  }
}
