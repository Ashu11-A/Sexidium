package com.sexidium.core.data;

import com.sexidium.core.data.RankService;
import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;

/**
 * Resolves a player's {@link RankClass} from their summed score and paints it onto their in-game name
 * via the platform {@link com.sexidium.core.platform.RankTagAdapter} (native scoreboard team). Applied
 * when a player joins and re-applied on demand (e.g. {@code /sx rank} on yourself) so a level-up is
 * reflected. The class is derived from the player's <em>aggregated</em> score (summed across all the
 * Minecraft names linked to the same Discord account), matching the Discord-side tag.
 */
public final class RankTagService {
  private final ServerAdapter server;
  private final RankService ranks;

  public RankTagService(ServerAdapter server, RankService ranks) {
    this.server = server;
    this.ranks = ranks;
  }

  /** Computes and applies the player's current rank tag. Safe to call repeatedly. */
  public void apply(PlayerAdapter player) {
    if (player == null || !player.online()) {
      return;
    }
    RankClass rankClass = classFor(player);
    server.rankTags().applyRankTag(player, teamName(rankClass), prefixMini(rankClass), rankClass.hexColor());
  }

  public void clear(PlayerAdapter player) {
    if (player != null) {
      server.rankTags().clearRankTag(player);
    }
  }

  public RankClass classFor(PlayerAdapter player) {
    if (ranks == null || player == null) {
      return RankClass.OMEGA;
    }
    LeaderboardEntry entry = ranks.aggregateByName(player.name());
    if (entry == null) {
      return RankClass.OMEGA;
    }
    RankClass byName = RankClass.fromName(entry.rankClass());
    return byName != null ? byName : RankClass.forLevel(entry.level());
  }

  /** Stable team id, prefixed with a priority digit so the best rank sorts to the top of the tab list. */
  public static String teamName(RankClass rankClass) {
    int priority = RankClass.values().length - 1 - rankClass.ordinal();
    return priority + "_" + rankClass.name().toLowerCase(java.util.Locale.ROOT);
  }

  /** MiniMessage prefix shown before the player's name, e.g. {@code "<#E74C3C><bold>[Sigma]</bold> "}. */
  public static String prefixMini(RankClass rankClass) {
    return "<" + rankClass.hexColor() + "><bold>[" + rankClass.displayName() + "]</bold> ";
  }
}
