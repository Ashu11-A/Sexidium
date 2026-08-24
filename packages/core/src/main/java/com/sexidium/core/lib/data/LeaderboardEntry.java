package com.sexidium.core.lib.data;

import java.util.List;

/**
 * One leaderboard row aggregated by Discord account. A single Discord user may have linked several
 * Minecraft player names (the one-to-many link), so the points/wins/kills/games here are the SUM
 * across every linked name and {@link #names()} lists them all. {@link #name()} is the representative
 * (highest-scoring) Minecraft name. {@code rankClass}/{@code rankColor} are derived from the summed
 * level so the Discord bot can render the tag without re-deriving the thresholds.
 *
 * <p>For an unlinked player (no Discord account) the entry degrades to a single-name row with a null
 * {@code discordUserId}.</p>
 */
public record LeaderboardEntry(
    String discordUserId,
    String name,
    List<String> names,
    int points,
    int level,
    int wins,
    int kills,
    int games,
    String rankClass,
    String rankColor) {
  public LeaderboardEntry {
    names = names == null ? List.of() : List.copyOf(names);
  }
}
