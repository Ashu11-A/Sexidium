package com.sexidium.core.world;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.Branding;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.data.FriendService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player lobby scoreboard panel: plugin name, total players online, the player's ping and their
 * online friends. Runs on a single repeating timer and is fully platform-agnostic — it drives the
 * {@link com.sexidium.core.platform.UiAdapter#createPanel} scoreboard handle that each adapter
 * implements, so it works identically on Paper and NeoForge.
 */
public final class LobbyHud {
  private final ServerAdapter serverAdapter;
  private final GameManager gameManager;
  private final FriendService friendService;
  private final RankAwardPort ranks;
  private final LobbyGuardPolicy policy;
  private final Map<UUID, HudPanelHandle> panels = new HashMap<>();
  private final Map<UUID, Integer> lineCounts = new HashMap<>();
  private ScheduledTask task;

  public LobbyHud(ServerAdapter serverAdapter, GameManager gameManager, FriendService friendService, RankAwardPort ranks) {
    this.serverAdapter = serverAdapter;
    this.gameManager = gameManager;
    this.friendService = friendService;
    this.ranks = ranks == null ? RankAwardPort.noop() : ranks;
    this.policy = new LobbyGuardPolicy(serverAdapter.configuration(), gameManager);
  }

  public void start() {
    if (!serverAdapter.configuration().getBoolean("lobby.hud.enabled", true)) {
      return;
    }
    long period = HudCadence.ticks(serverAdapter.configuration(), "lobby.hud.refresh-ticks");
    task = serverAdapter.scheduler().runTimer(this::tick, period, period);
  }

  /** Rebuilds active panels so their localized title picks up the current configuration. */
  public void reload() {
    panels.values().forEach(HudPanelHandle::close);
    panels.clear();
    lineCounts.clear();
  }

  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    panels.values().forEach(HudPanelHandle::close);
    panels.clear();
    lineCounts.clear();
  }

  private void tick() {
    Collection<PlayerAdapter> online = serverAdapter.onlinePlayers();
    int totalPlayers = online.size();
    List<UUID> lobbyPlayers = new ArrayList<>();
    for (PlayerAdapter player : online) {
      if (inLobby(player)) {
        lobbyPlayers.add(player.uniqueId());
        render(player, totalPlayers);
      }
    }
    // Drop panels for players who left the lobby (joined a match or disconnected).
    panels.keySet().removeIf(playerId -> {
      if (lobbyPlayers.contains(playerId)) {
        return false;
      }
      HudPanelHandle panel = panels.get(playerId);
      PlayerAdapter player = serverAdapter.player(playerId).orElse(null);
      ActiveMatch match = player == null ? null : gameManager.matchOf(player);
      if (match != null && player != null && match.game().drawsSidebar(player)) {
        // They joined a match that puts up its own sidebar. Forget our lobby panel WITHOUT restoring the
        // previous board, otherwise we would clobber the match's board (the bug where the Race
        // items/teams card vanished a couple of seconds after joining).
        panel.forget(player);
      } else if (match != null) {
        // A match that draws no sidebar FOR THIS PLAYER (Death Resets, whose readout is a corner overlay
        // and whose whole point is an uncluttered screen — for the players its overlay actually reaches;
        // a Bedrock player in the same match takes the branch above). Nothing is going to replace this
        // board, so forgetting it would leave the lobby's server/points/rank card sitting there for the
        // entire match. Take it down.
        panel.hide(player);
        panel.close();
      } else {
        if (player != null) {
          panel.hide(player);
        }
        panel.close();
      }
      lineCounts.remove(playerId);
      return true;
    });
  }

  private boolean inLobby(PlayerAdapter player) {
    if (player == null || !player.online()) {
      return false;
    }
    WorldAdapter world = player.world();
    return world != null && policy.isLobbyWorld(world.name()) && gameManager.matchOf(player) == null;
  }

  private void render(PlayerAdapter player, int totalPlayers) {
    HudPanelHandle panel = panels.computeIfAbsent(player.uniqueId(), playerId -> {
      HudPanelHandle created = serverAdapter.ui().createPanel(LocalizedText.of(MessageKey.LOBBY_HUD_TITLE,
          MessageArg.text("brand", Branding.label(serverAdapter.configuration()))));
      created.show(player);
      return created;
    });

    int line = 0;
    panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_PLAYERS, MessageArg.text("count", totalPlayers)));
    int ping = player.ping();
    if (ping >= 0) {
      panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_PING, MessageArg.text("ping", ping)));
    }

    // Global progression: accumulated points, level and the colour-coded rank class (matches Discord).
    LeaderboardEntry profile = ranks.lookup(player.name());
    // Mirror progression onto the vanilla XP bar: the number is the player's level and the green fill is
    // how far their points have progressed toward the next level. Purely cosmetic; resetStatuses clears it
    // on match entry so it never leaks into a minigame that uses the XP bar as a health meter (F61).
    int perLevel = Math.max(1, ranks.pointsPerLevel());
    int points = profile == null ? 0 : profile.points();
    player.setExperienceBar(points / perLevel, (points % perLevel) / (float) perLevel);
    panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_SPACER));
    panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_POINTS, MessageArg.text("points", profile == null ? 0 : profile.points())));
    panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_LEVEL, MessageArg.text("level", profile == null ? 0 : profile.level())));
    String rankName = profile == null ? "Omega" : profile.rankClass();
    String rankColor = profile == null ? "#9AA0A6" : profile.rankColor();
    panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_RANK, MessageArg.mini("rank", "<" + rankColor + ">" + rankName)));
    panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_SPACER));

    List<String> onlineFriendNames = onlineFriendNames(player.uniqueId());
    panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_FRIENDS, MessageArg.text("count", onlineFriendNames.size())));
    if (onlineFriendNames.isEmpty()) {
      panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_NO_FRIENDS));
    } else {
      int maxFriendLines = Math.max(1, serverAdapter.configuration().getInt("lobby.hud.max-friend-lines", 5));
      for (String name : onlineFriendNames) {
        if (line - 2 >= maxFriendLines) {
          break;
        }
        panel.line(line++, LocalizedText.of(MessageKey.LOBBY_HUD_FRIEND_ENTRY, MessageArg.text("name", name)));
      }
    }

    // Remove lines left over from a previous, longer render (e.g. a friend went offline).
    int previousLineCount = lineCounts.getOrDefault(player.uniqueId(), 0);
    for (int staleLine = line; staleLine < previousLineCount; staleLine++) {
      panel.removeLine(staleLine);
    }
    lineCounts.put(player.uniqueId(), line);
    panel.refresh();
  }

  private List<String> onlineFriendNames(UUID playerId) {
    if (friendService == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (FriendService.Entry friend : friendService.friends(playerId)) {
      if (serverAdapter.player(friend.playerId()).filter(PlayerAdapter::online).isPresent()) {
        names.add(friend.playerName());
      }
    }
    return names;
  }
}
