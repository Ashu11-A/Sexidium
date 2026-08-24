package com.sexidium.paper.adapter.ui;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.RankTagAdapter;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

/**
 * Paints a player's rank class onto their name using native Bukkit scoreboard {@link Team}s on the
 * server's main scoreboard: the team prefix renders before the nametag (above head) and in the tab
 * list, and the team colour drives the nametag colour. The player's tab-list entry is additionally set
 * to {@code [Class] name} via {@code playerListName} so the tag is visible in tab even for viewers who
 * are looking at a per-player sidebar scoreboard (e.g. the lobby HUD).
 */
public final class PaperRankTagAdapter implements RankTagAdapter {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  /** Prefix shared by every rank team so we can find/clear them without knowing all class names. */
  private static final String TEAM_PREFIX = "sxr_";

  @Override
  public void applyRankTag(PlayerAdapter playerAdapter, String teamName, String prefixMini, String hexColor) {
    Player player = handle(playerAdapter);
    Scoreboard scoreboard = mainScoreboard();
    if (player == null || scoreboard == null) {
      return;
    }
    String entry = player.getName();
    removeFromRankTeams(scoreboard, entry);

    String safeName = TEAM_PREFIX + teamName;
    Team team = scoreboard.getTeam(safeName);
    if (team == null) {
      team = scoreboard.registerNewTeam(safeName);
    }
    Component prefix = MINI_MESSAGE.deserialize(prefixMini);
    team.prefix(prefix);
    TextColor color = TextColor.fromHexString(hexColor);
    if (color != null) {
      team.color(NamedTextColor.nearestTo(color));
    }
    team.addEntry(entry);
    player.playerListName(prefix.append(Component.text(player.getName())));
  }

  @Override
  public void clearRankTag(PlayerAdapter playerAdapter) {
    Player player = handle(playerAdapter);
    Scoreboard scoreboard = mainScoreboard();
    if (player == null || scoreboard == null) {
      return;
    }
    removeFromRankTeams(scoreboard, player.getName());
    player.playerListName(null);
  }

  private static void removeFromRankTeams(Scoreboard scoreboard, String entry) {
    for (Team team : scoreboard.getTeams()) {
      if (team.getName().startsWith(TEAM_PREFIX) && team.hasEntry(entry)) {
        team.removeEntry(entry);
      }
    }
  }

  private static Scoreboard mainScoreboard() {
    ScoreboardManager manager = Bukkit.getScoreboardManager();
    return manager == null ? null : manager.getMainScoreboard();
  }

  private static Player handle(PlayerAdapter playerAdapter) {
    return playerAdapter instanceof PaperPlayerAdapter paperPlayerAdapter ? paperPlayerAdapter.handle() : null;
  }
}
