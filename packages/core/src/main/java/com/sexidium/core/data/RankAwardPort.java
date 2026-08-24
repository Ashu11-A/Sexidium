package com.sexidium.core.data;

import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.platform.PlayerAdapter;

public interface RankAwardPort {
  void awardParticipation(PlayerAdapter playerAdapter);

  void awardKill(PlayerAdapter playerAdapter);

  void awardWin(PlayerAdapter playerAdapter, String modeId);

  /**
   * Awards points for time spent inside an open-ended experience world (Experience + Chaos modes),
   * which have no win/lose condition. Called periodically by the host with the number of whole seconds
   * elapsed since the previous tick; the implementation converts that into points via the
   * {@code ranks.award.experience-per-minute} rate. A no-op for elapsed &le; 0.
   */
  default void awardPlaytime(PlayerAdapter playerAdapter, long secondsElapsed) {
  }

  /**
   * The player's global, Discord-aggregated points/level/class (the same row the Discord bot shows), or
   * null when unknown or ranks are disabled. Lets in-game UI (the TNT-War HUD, the lobby board) display
   * the authoritative global score without depending on the full {@code RankService} read API.
   */
  default LeaderboardEntry lookup(String playerName) {
    return null;
  }

  /**
   * Points required to advance one level (config {@code ranks.points-per-level}), so UI can render a
   * progress bar toward the next level ({@code points % pointsPerLevel / pointsPerLevel}). Defaults to
   * the built-in 100 for the no-op port.
   */
  default int pointsPerLevel() {
    return 100;
  }

  static RankAwardPort noop() {
    return new RankAwardPort() {
      @Override
      public void awardParticipation(PlayerAdapter playerAdapter) {
      }

      @Override
      public void awardKill(PlayerAdapter playerAdapter) {
      }

      @Override
      public void awardWin(PlayerAdapter playerAdapter, String modeId) {
      }
    };
  }
}
