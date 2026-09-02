package com.sexidium.core.platform;

/**
 * Platform hook that paints a player's rank class onto their in-game name: the coloured tag above
 * their head and in the tab list. Implemented with native scoreboard Teams on both platforms (Paper
 * {@code org.bukkit.scoreboard.Team}) — no
 * runtime dependency, identical result on both. The default is a no-op so headless/test adapters and
 * servers without a scoreboard keep working.
 */
public interface RankTagAdapter {
  RankTagAdapter NOOP = new RankTagAdapter() {};

  /**
   * Assigns the player to the rank's team, (re)creating the team with the given colour and prefix.
   *
   * @param player     the player to tag
   * @param teamName   stable team id; prefix it with a priority digit so the best rank sorts to the
   *                   top of the tab list
   * @param prefixMini MiniMessage prefix rendered before the player name (e.g. {@code "<#E74C3C>[Sigma] "})
   * @param hexColor   {@code #RRGGBB} colour applied to the player's name (nearest named colour on Paper)
   */
  default void applyRankTag(PlayerAdapter player, String teamName, String prefixMini, String hexColor) {}

  /** Removes the player from any rank team (e.g. on quit). */
  default void clearRankTag(PlayerAdapter player) {}
}
