package com.sexidium.core.world.lobby;

import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/**
 * The quick-play queue / matchmaking responsibility extracted from {@link LobbyManager} (the old
 * {@code MatchmakingManager}). Owns the per-mode countdown evaluation and launch loop, operating on the
 * shared {@link LobbyContext}. Queue ordering (insertion order = matchmaking priority), countdown timing
 * and launch-retry behaviour are preserved exactly.
 */
final class LobbyQueue {
  private static final int MAX_LAUNCH_RETRIES = 6;

  private final LobbyContext ctx;

  LobbyQueue(LobbyContext ctx) {
    this.ctx = ctx;
  }

  /** Leader queues the whole roster for quick-play (leader-gated). */
  LobbyResult queue(PlayerAdapter leader, String mode) {
    if (leader == null) {
      return LobbyResult.FAILED;
    }
    GameModeDescriptor descriptor = ctx.modes.resolveMinigame(mode);
    if (descriptor == null) {
      return LobbyResult.NOT_MINIGAME;
    }
    if (ctx.games.matchOf(leader.uniqueId()) != null) {
      return LobbyResult.ALREADY_IN_MATCH;
    }
    Lobby lobby = ctx.orCreate(leader);
    if (!lobby.isLeader(leader.uniqueId())) {
      return LobbyResult.NOT_LEADER;
    }
    String canonical = descriptor.modeId();
    if (lobby.isQueued() && canonical.equals(lobby.modeId())) {
      return LobbyResult.ALREADY_QUEUED;
    }
    dropFromQueue(lobby);
    lobby.toQueued(canonical);
    ctx.queueByMode.computeIfAbsent(canonical, ignored -> new LinkedHashSet<>()).add(lobby.id());
    for (PlayerAdapter member : lobby.onlineMembers(ctx.server)) {
      member.sendActionBar("<aqua>Quick-play:</aqua> <gray>queued for <white>" + descriptor.displayName() + "</white>.</gray>");
    }
    onQueueChanged(canonical);
    return LobbyResult.QUEUED;
  }

  /** Cancel the lobby's quick-play queue (leader only), returning it to IDLE. */
  LobbyResult dequeue(PlayerAdapter player) {
    Lobby lobby = ctx.lobbyOf(player == null ? null : player.uniqueId());
    if (lobby == null || !lobby.isQueued()) {
      return LobbyResult.NOT_FOUND;
    }
    if (!lobby.isLeader(player.uniqueId())) {
      return LobbyResult.NOT_LEADER;
    }
    dropFromQueue(lobby);
    lobby.toIdle();
    return LobbyResult.DEQUEUED;
  }

  boolean isQueued(UUID playerId) {
    Lobby lobby = ctx.lobbyOf(playerId);
    return lobby != null && lobby.isQueued();
  }

  String queuedMode(UUID playerId) {
    Lobby lobby = ctx.lobbyOf(playerId);
    return lobby != null && lobby.isQueued() ? lobby.modeId() : null;
  }

  int queueSize(String mode) {
    return onlineQueuedCount(LobbyContext.normalize(mode));
  }

  int countdownSeconds(String mode) {
    Integer remaining = ctx.countdowns.get(LobbyContext.normalize(mode));
    return remaining == null ? -1 : remaining;
  }

  /** One matchmaking pass over every queue. Public so tests can drive it deterministically. */
  void tick() {
    for (String mode : new ArrayList<>(ctx.queueByMode.keySet())) {
      pruneQueue(mode);
      evaluate(mode);
    }
  }

  /** Reacts to a queued-roster membership change: start/cancel the countdown for one mode. */
  void onQueueChanged(String mode) {
    if (mode == null) {
      return;
    }
    LinkedHashSet<UUID> set = ctx.queueByMode.get(mode);
    int count = onlineQueuedCount(mode);
    if (set == null || set.isEmpty() || count == 0) {
      ctx.queueByMode.remove(mode);
      ctx.countdowns.remove(mode);
      return;
    }
    int min = ctx.modes.minPlayers(mode);
    if (count < min) {
      ctx.countdowns.remove(mode);
      broadcastQueue(mode, LobbyContext.searchingMessage(count, min));
      return;
    }
    if (!ctx.countdowns.containsKey(mode)) {
      int seconds = ctx.countdownSecondsConfig();
      ctx.countdowns.put(mode, seconds);
      broadcastQueue(mode, "<green>Match found!</green> <gray>Starting in <white>" + seconds + "</white>…</gray>");
    }
  }

  /** One countdown step for one mode. Called from {@link #tick()}; never shortens a running countdown. */
  private void evaluate(String mode) {
    LinkedHashSet<UUID> set = ctx.queueByMode.get(mode);
    if (set == null || set.isEmpty()) {
      ctx.countdowns.remove(mode);
      ctx.queueByMode.remove(mode);
      return;
    }
    int min = ctx.modes.minPlayers(mode);
    int count = onlineQueuedCount(mode);
    if (count < min) {
      ctx.countdowns.remove(mode);
      broadcastQueue(mode, LobbyContext.searchingMessage(count, min));
      return;
    }
    Integer remaining = ctx.countdowns.get(mode);
    if (remaining == null) {
      remaining = ctx.countdownSecondsConfig();
      ctx.countdowns.put(mode, remaining);
      broadcastQueue(mode, "<green>Match found!</green> <gray>Starting in <white>" + remaining + "</white>…</gray>");
      return;
    }
    if (remaining > 1) {
      int next = remaining - 1;
      ctx.countdowns.put(mode, next);
      broadcastQueue(mode, "<green>Starting in <white>" + next + "</white>…</green>");
      return;
    }
    launch(mode);
  }

  private void launch(String mode) {
    LinkedHashSet<UUID> set = ctx.queueByMode.get(mode);
    if (set == null) {
      return;
    }
    int cap = ctx.maxPlayers();
    List<UUID> roster = new ArrayList<>();
    List<Lobby> contributing = new ArrayList<>();
    // One entry per contributing lobby (its actually-added online members), so matchmaking can keep each
    // queued group together as a team via ctx.matchmakingTeamArgs.
    List<List<UUID>> groups = new ArrayList<>();
    for (UUID lobbyId : new ArrayList<>(set)) {
      Lobby lobby = ctx.lobbiesById.get(lobbyId);
      if (lobby == null) {
        set.remove(lobbyId);
        continue;
      }
      List<UUID> online = ctx.onlineMemberIds(lobby);
      if (online.isEmpty()) {
        continue;
      }
      // Don't split a group across the player cap: stop before adding a group that wouldn't fit whole.
      if (!roster.isEmpty() && roster.size() + online.size() > cap) {
        break;
      }
      List<UUID> added = new ArrayList<>();
      for (UUID memberId : online) {
        if (roster.size() >= cap) {
          break;
        }
        roster.add(memberId);
        added.add(memberId);
      }
      if (!added.isEmpty()) {
        groups.add(added);
        contributing.add(lobby);
      }
    }
    if (roster.isEmpty()) {
      return;
    }
    boolean started = ctx.games.startWithPlayers(mode, roster, null, ctx.matchmakingTeamArgs(mode, groups));
    if (started) {
      for (Lobby lobby : contributing) {
        set.remove(lobby.id());
        lobby.toIdle();
      }
      ctx.countdowns.remove(mode);
      ctx.launchFailures.remove(mode);
      onQueueChanged(mode);
      return;
    }
    ctx.countdowns.remove(mode);
    int failures = ctx.launchFailures.merge(mode, 1, Integer::sum);
    if (failures >= MAX_LAUNCH_RETRIES) {
      ctx.launchFailures.remove(mode);
      for (Lobby lobby : contributing) {
        set.remove(lobby.id());
        for (UUID memberId : ctx.onlineMemberIds(lobby)) {
          ctx.actionbar(memberId, "<red>Could not start the match — try again later.</red>");
        }
        lobby.toIdle();
      }
      onQueueChanged(mode);
      return;
    }
    for (UUID memberId : roster) {
      ctx.actionbar(memberId, "<red>Could not start the match — retrying…</red>");
    }
  }

  /** Drops a queued lobby that has gone fully offline back to IDLE so it stops blocking matchmaking. */
  private void pruneQueue(String mode) {
    LinkedHashSet<UUID> set = ctx.queueByMode.get(mode);
    if (set == null) {
      return;
    }
    for (UUID lobbyId : new ArrayList<>(set)) {
      Lobby lobby = ctx.lobbiesById.get(lobbyId);
      if (lobby == null) {
        set.remove(lobbyId);
        continue;
      }
      if (ctx.onlineMemberIds(lobby).isEmpty() || ctx.games.matchOf(lobby.leader()) != null) {
        set.remove(lobbyId);
        lobby.toIdle();
      }
    }
    if (set.isEmpty()) {
      ctx.queueByMode.remove(mode);
      ctx.countdowns.remove(mode);
    }
  }

  void dropFromQueue(Lobby lobby) {
    String mode = lobby.modeId();
    if (mode == null) {
      return;
    }
    LinkedHashSet<UUID> set = ctx.queueByMode.get(mode);
    if (set != null) {
      set.remove(lobby.id());
    }
    onQueueChanged(mode);
  }

  private int onlineQueuedCount(String mode) {
    LinkedHashSet<UUID> set = ctx.queueByMode.get(mode);
    if (set == null) {
      return 0;
    }
    int count = 0;
    for (UUID lobbyId : set) {
      Lobby lobby = ctx.lobbiesById.get(lobbyId);
      if (lobby != null) {
        count += ctx.onlineMemberIds(lobby).size();
      }
    }
    return count;
  }

  private void broadcastQueue(String mode, String miniMessage) {
    LinkedHashSet<UUID> set = ctx.queueByMode.get(mode);
    if (set == null) {
      return;
    }
    for (UUID lobbyId : set) {
      Lobby lobby = ctx.lobbiesById.get(lobbyId);
      if (lobby != null) {
        for (UUID memberId : lobby.members()) {
          ctx.actionbar(memberId, miniMessage);
        }
      }
    }
  }
}
