package com.sexidium.core.game.team;

import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The shared right-side team card, contributed to the unified {@link com.sexidium.core.game.hud.GameHud}
 * instead of building one-off panels. Each viewer sees their own team highlighted (colour + allies) and
 * the rival teams with their sizes — the team-aware replacement for the old {@code TeamDisplay.build}
 * panels, so every minigame's team info renders through one HUD with the toggle/compact view.
 *
 * <p>FULL shows your team + allies + rivals; COMPACT trims to just your own team and allies. The
 * {@link Teams} are read through a supplier so a live team switch (Race) is reflected on the next
 * refresh.</p>
 */
public final class TeamHudContributor implements HudContributor {
  private final ServerAdapter server;
  private final Supplier<Teams> teams;

  public TeamHudContributor(ServerAdapter server, Supplier<Teams> teams) {
    this.server = server;
    this.teams = teams;
  }

  @Override
  public int order() {
    // Render the team card below a minigame's own scoreboard lines.
    return 100;
  }

  @Override
  public void describe(HudContext context) {
    Teams current = teams == null ? null : teams.get();
    PlayerAdapter viewer = context.player();
    if (current == null || current.isEmpty() || viewer == null) {
      return;
    }
    Team own = current.teamOf(viewer.uniqueId());
    if (own != null) {
      context.line(LocalizedText.of(MessageKey.TEAM_HUD_YOUR_TEAM, MessageArg.mini("team", own.coloredName())));
      context.line(LocalizedText.of(MessageKey.TEAM_HUD_ALLIES));
      for (UUID memberId : own.members()) {
        if (memberId.equals(viewer.uniqueId())) {
          continue;
        }
        context.line(LocalizedText.of(MessageKey.TEAM_HUD_ALLY, MessageArg.text("player", nameOf(memberId))));
      }
    }
    // COMPACT view: stop at the viewer's own team, omit the rival roster.
    if (context.compact()) {
      return;
    }
    if (own != null) {
      context.line(LocalizedText.of(MessageKey.TEAM_HUD_SPACER));
    }
    context.line(LocalizedText.of(MessageKey.TEAM_HUD_RIVALS));
    for (Team team : current.all()) {
      if (own != null && team.index() == own.index()) {
        continue;
      }
      context.line(LocalizedText.of(MessageKey.TEAM_HUD_RIVAL,
          MessageArg.mini("team", team.coloredName()), MessageArg.text("count", team.size())));
    }
  }

  private String nameOf(UUID playerId) {
    return server.player(playerId).map(PlayerAdapter::name).orElse("?");
  }
}
