package com.sexidium.core.game.modes.minigames;

/**
 * Pure win/draw resolution for {@link TntWarGame}. Given each team's remaining lives and base-destruction
 * percentage (plus the configured win threshold), decides who — if anyone — has won. No platform
 * dependency, so the rule is unit-testable in isolation.
 *
 * <p>{@code RED}/{@code BLUE} mean that team WON. {@code DRAW} means the match should end in a draw.
 * {@code NONE} means the match continues.</p>
 */
final class TntWarOutcome {
  enum Result {
    NONE,
    RED,
    BLUE,
    DRAW
  }

  private TntWarOutcome() {
  }

  /**
   * Live outcome check during the fight. A team "loses" when it runs out of lives or its base passes the
   * win-destruction threshold. If both fell on the same tick, the team that wrecked more of the enemy base
   * wins, else it is a draw.
   */
  static Result check(int redLives, int blueLives, int redBaseDestroyed, int blueBaseDestroyed, int winPercent) {
    boolean redLost = redLives <= 0 || redBaseDestroyed >= winPercent;
    boolean blueLost = blueLives <= 0 || blueBaseDestroyed >= winPercent;
    if (redLost && blueLost) {
      if (redBaseDestroyed > blueBaseDestroyed) {
        return Result.BLUE;
      } else if (blueBaseDestroyed > redBaseDestroyed) {
        return Result.RED;
      }
      return Result.DRAW;
    } else if (redLost) {
      return Result.BLUE;
    } else if (blueLost) {
      return Result.RED;
    }
    return Result.NONE;
  }

  /** Time-limit resolution: whoever wrecked more of the enemy base wins, else a draw. */
  static Result onTime(int redBaseDestroyed, int blueBaseDestroyed) {
    if (blueBaseDestroyed > redBaseDestroyed) {
      return Result.RED;
    } else if (redBaseDestroyed > blueBaseDestroyed) {
      return Result.BLUE;
    }
    return Result.DRAW;
  }
}
