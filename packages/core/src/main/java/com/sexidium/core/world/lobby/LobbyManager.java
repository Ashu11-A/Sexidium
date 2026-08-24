package com.sexidium.core.world.lobby;
import com.sexidium.core.data.FriendGraph;
import com.sexidium.core.world.lobby.LobbyEnums.*;

import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.ServerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single owner of the pre-match social subsystem, merging the old {@code PartyManager},
 * {@code MatchLobbyManager}, {@code MatchmakingManager} and {@code MatchParticipationCoordinator}. Every
 * online player belongs to at most one {@link Lobby}; that lobby is in exactly one {@link LobbyState}, so
 * the old "party XOR host-lobby XOR queue" invariant is now just a state check rather than three managers
 * forcing each other to release a player.
 *
 * <p>This class is the public facade. The shared state and low-level helpers live in {@link LobbyContext},
 * and the quick-play queue / matchmaking responsibility lives in {@link LobbyQueue}; the facade keeps the
 * group lifecycle, host-match configuration and player-lifecycle responsibilities and delegates the rest.</p>
 *
 * <p>All mutation runs on the server main thread ({@link #join}/{@link #queue}/… from commands and menu
 * clicks, {@link #tick()} from a 1&nbsp;Hz timer). The maps are concurrent only for safe read access from
 * the menus / HUD.</p>
 */
public final class LobbyManager {
  private static final long TICK_PERIOD_TICKS = 20L; // 1 second

  private final ServerAdapter server;
  private final MatchLauncher games;
  private final FriendGraph friends; // nullable (no DB)
  private final LobbyContext ctx;

  /**
   * Mirror party membership into the shared directory so a group survives its members being on
   * different nodes. No-op standalone.
   *
   * <p>On LobbyManager rather than exposing LobbyContext, which is package-private on purpose --
   * its maps feed a single-writer countdown and are not something other packages should reach into.</p>
   */
  public void setLobbyDirectory(com.sexidium.core.network.LobbyDirectory directory, String nodeId) {
    ctx.setDirectory(directory, nodeId);
  }
  private final LobbyQueue queue;

  private ScheduledTask tickTask;

  public LobbyManager(ServerAdapter server, MatchLauncher games, FriendGraph friends) {
    this.server = server;
    this.games = games;
    this.friends = friends;
    this.ctx = new LobbyContext(server, games, friends);
    this.queue = new LobbyQueue(ctx);
    this.ctx.queue = queue;
  }

  // ----- lifecycle ----------------------------------------------------------------------------

  public void start() {
    if (tickTask == null) {
      tickTask = server.scheduler().runTimer(this::tick, TICK_PERIOD_TICKS, TICK_PERIOD_TICKS);
    }
  }

  public void stop() {
    if (tickTask != null) {
      tickTask.cancel();
      tickTask = null;
    }
    ctx.clearAll();
  }

  // ----- queries --------------------------------------------------------------------------------

  /** The lobby this player belongs to, or null. Cleans a stale reverse-index entry as a side effect. */
  public Lobby lobbyOf(UUID playerId) {
    return ctx.lobbyOf(playerId);
  }

  public List<Lobby> all() {
    return new ArrayList<>(ctx.lobbiesById.values());
  }

  /** The lobby with this id (== its creating leader's UUID), or null. */
  public Lobby lobbyById(UUID lobbyId) {
    return lobbyId == null ? null : ctx.lobbiesById.get(lobbyId);
  }

  /** True when the player is in a multi-player group (the old {@code inParty}). A solo lobby is not. */
  public boolean inGroup(UUID playerId) {
    Lobby lobby = ctx.lobbyOf(playerId);
    return lobby != null && lobby.size() > 1;
  }

  public boolean isLeader(UUID playerId) {
    Lobby lobby = ctx.lobbyOf(playerId);
    return lobby != null && lobby.isLeader(playerId);
  }

  /** True when the player is in a lobby worth opening a dedicated screen for: a group, or queued/configured. */
  public boolean hasActiveLobby(UUID playerId) {
    Lobby lobby = ctx.lobbyOf(playerId);
    return lobby != null && (lobby.size() > 1 || !lobby.isIdle());
  }

  public int maxSize() {
    return ctx.maxSize();
  }

  /** Lobbies a viewer may see and join from the browser: active (QUEUED/CONFIGURED) and visible to them. */
  public List<Lobby> joinableFor(UUID viewer, String modeId) {
    String mode = modeId == null ? null : LobbyContext.normalize(modeId);
    List<Lobby> out = new ArrayList<>();
    for (Lobby lobby : ctx.lobbiesById.values()) {
      if (lobby.isIdle()) {
        continue;
      }
      if (mode != null && !mode.equals(lobby.modeId())) {
        continue;
      }
      boolean invited = ctx.invites.isInvited(viewer, lobby.id());
      if (lobby.canJoin(viewer, friends, invited)) {
        out.add(lobby);
      }
    }
    return out;
  }

  public List<UUID> pendingInviteLobbies(UUID playerId) {
    return ctx.invites.pending(playerId);
  }

  // ----- group lifecycle (was PartyManager) -----------------------------------------------------

  /** Invite a player into the inviter's lobby. Leader-gated (a solo player becomes their own leader). */
  public LobbyResult invite(PlayerAdapter inviter, PlayerAdapter target) {
    if (inviter == null || target == null) {
      return LobbyResult.FAILED;
    }
    if (inviter.uniqueId().equals(target.uniqueId())) {
      return LobbyResult.SELF;
    }
    if (inGroup(target.uniqueId())) {
      return LobbyResult.TARGET_IN_PARTY;
    }
    Lobby lobby = ctx.lobbyOf(inviter.uniqueId());
    if (lobby == null) {
      lobby = ctx.orCreate(inviter);
    } else if (!lobby.isLeader(inviter.uniqueId())) {
      return LobbyResult.NOT_LEADER;
    }
    if (lobby.size() >= ctx.maxSize()) {
      return LobbyResult.FULL;
    }
    ctx.invites.invite(target.uniqueId(), lobby.id(), ctx.inviteTtlMillis());
    return LobbyResult.INVITE_SENT;
  }

  /**
   * Accept a pending lobby invite. {@code lobbyId} (which equals the inviting leader's UUID) may be null
   * to auto-pick the only pending invite, or to report AMBIGUOUS when several are pending.
   */
  public LobbyResult accept(PlayerAdapter target, UUID lobbyId) {
    if (target == null) {
      return LobbyResult.NO_INVITE;
    }
    List<UUID> pending = ctx.invites.pending(target.uniqueId());
    if (pending.isEmpty()) {
      return LobbyResult.NO_INVITE;
    }
    UUID chosen = lobbyId;
    if (chosen == null) {
      if (pending.size() > 1) {
        return LobbyResult.AMBIGUOUS;
      }
      chosen = pending.get(0);
    } else if (!ctx.invites.isInvited(target.uniqueId(), chosen)) {
      return LobbyResult.NO_INVITE;
    }
    Lobby lobby = ctx.lobbiesById.get(chosen);
    if (lobby == null) {
      return LobbyResult.DISBANDED;
    }
    if (lobby.size() >= ctx.maxSize()) {
      return LobbyResult.FULL;
    }
    if (inGroup(target.uniqueId())) {
      return LobbyResult.ALREADY_IN_PARTY;
    }
    ctx.detachSolo(target.uniqueId());
    lobby.add(target.uniqueId());
    ctx.playerToLobby.put(target.uniqueId(), lobby.id());
    ctx.invites.clear(target.uniqueId());
    return LobbyResult.JOINED;
  }

  /** Decline (remove) a pending invite from the given lobby. Returns true when one was removed. */
  public boolean declineInvite(UUID playerId, UUID lobbyId) {
    return ctx.invites.decline(playerId, lobbyId);
  }

  /** Join a friend directly into their lobby with no invite, respecting visibility and capacity. */
  public LobbyResult joinFriendLobby(PlayerAdapter joiner, UUID friendId) {
    Lobby lobby = ctx.lobbyOf(friendId);
    if (lobby == null) {
      return LobbyResult.DISBANDED;
    }
    return join(joiner, lobby.id());
  }

  /** Leave the lobby. The host role transfers to the oldest remaining member; an empty lobby is removed. */
  public LobbyResult leave(PlayerAdapter player) {
    if (player == null) {
      return LobbyResult.FAILED;
    }
    UUID playerId = player.uniqueId();
    Lobby lobby = ctx.lobbyOf(playerId);
    if (lobby == null) {
      return LobbyResult.NOT_FOUND;
    }
    boolean wasHost = lobby.isHost(playerId);
    boolean wasQueued = lobby.isQueued();
    ctx.playerToLobby.remove(playerId);
    lobby.remove(playerId);
    ctx.invites.clear(playerId);
    if (lobby.size() == 0) {
      ctx.removeLobby(lobby);
      return LobbyResult.LEFT;
    }
    if (wasHost) {
      UUID newHost = lobby.oldestMember();
      if (!lobby.transferHost(newHost)) {
        ctx.closeLobby(lobby, "<yellow>The lobby was closed.</yellow>");
        return LobbyResult.LEFT;
      }
      ctx.broadcast(lobby, "<gray>" + LobbyContext.escape(player.name()) + " left the lobby.</gray> <yellow>"
          + LobbyContext.escape(ctx.name(newHost)) + " is now the host.</yellow>");
    } else {
      ctx.broadcast(lobby, "<gray>" + LobbyContext.escape(player.name()) + " left the lobby (" + lobby.size() + ").</gray>");
    }
    if (wasQueued) {
      queue.onQueueChanged(lobby.modeId());
    }
    return LobbyResult.LEFT;
  }

  public boolean kick(UUID leaderId, UUID targetId) {
    Lobby lobby = ctx.lobbyOf(leaderId);
    if (lobby == null || !lobby.isLeader(leaderId) || !lobby.contains(targetId) || targetId.equals(leaderId)) {
      return false;
    }
    boolean wasQueued = lobby.isQueued();
    lobby.remove(targetId);
    ctx.playerToLobby.remove(targetId);
    ctx.invites.clear(targetId);
    if (wasQueued) {
      queue.onQueueChanged(lobby.modeId());
    }
    return true;
  }

  /** Disband the whole lobby (leader only). */
  public LobbyResult disband(UUID leaderId) {
    Lobby lobby = ctx.lobbyOf(leaderId);
    if (lobby == null || !lobby.isLeader(leaderId)) {
      return LobbyResult.NOT_LEADER;
    }
    ctx.closeLobby(lobby, null);
    return LobbyResult.DISBANDED;
  }

  // ----- joining an existing lobby (was MatchLobbyManager.join) ---------------------------------

  public LobbyResult join(PlayerAdapter player, UUID lobbyId) {
    if (player == null) {
      return LobbyResult.FAILED;
    }
    UUID playerId = player.uniqueId();
    if (games.matchOf(playerId) != null) {
      return LobbyResult.ALREADY_IN_MATCH;
    }
    Lobby lobby = lobbyId == null ? null : ctx.lobbiesById.get(lobbyId);
    if (lobby == null) {
      return LobbyResult.NOT_FOUND;
    }
    if (lobby.contains(playerId)) {
      return LobbyResult.ALREADY_IN;
    }
    if (inGroup(playerId)) {
      return LobbyResult.ALREADY_IN;
    }
    boolean invited = ctx.invites.isInvited(playerId, lobby.id());
    if (!lobby.canJoin(playerId, friends, invited)) {
      return LobbyResult.NOT_INVITED;
    }
    int cap = lobby.isConfigured() ? lobby.capacity() : ctx.maxSize();
    if (lobby.size() >= cap) {
      return LobbyResult.FULL;
    }
    ctx.detachSolo(playerId);
    ctx.invites.decline(playerId, lobby.id());
    lobby.add(playerId);
    ctx.playerToLobby.put(playerId, lobby.id());
    ctx.broadcast(lobby, "<green>" + LobbyContext.escape(player.name()) + "</green> <gray>joined the lobby (" + lobby.size() + ").</gray>");
    return LobbyResult.JOINED;
  }

  /** Resolve a host display name to their current lobby, then join it (the {@code /lobby join <name>} path). */
  public LobbyResult joinByName(PlayerAdapter player, String hostName) {
    if (hostName == null || hostName.isBlank()) {
      return LobbyResult.NOT_FOUND;
    }
    PlayerAdapter host = server.playerExact(hostName).orElse(null);
    if (host == null) {
      for (PlayerAdapter candidate : server.onlinePlayers()) {
        if (candidate.name().equalsIgnoreCase(hostName)) {
          host = candidate;
          break;
        }
      }
    }
    if (host == null) {
      return LobbyResult.NOT_FOUND;
    }
    Lobby lobby = ctx.lobbyOf(host.uniqueId());
    if (lobby == null) {
      return LobbyResult.NOT_FOUND;
    }
    return join(player, lobby.id());
  }

  /** Host invites a player to this lobby; the invited player may then join even when it is invite-only. */
  public LobbyResult inviteToLobby(PlayerAdapter host, UUID playerId) {
    Lobby lobby = ctx.lobbyOf(host == null ? null : host.uniqueId());
    if (lobby == null) {
      return LobbyResult.NOT_FOUND;
    }
    if (!lobby.isHost(host.uniqueId())) {
      return LobbyResult.NOT_HOST;
    }
    if (playerId == null || lobby.contains(playerId)) {
      return LobbyResult.ALREADY_IN;
    }
    ctx.invites.invite(playerId, lobby.id(), ctx.inviteTtlMillis());
    return LobbyResult.INVITE_SENT;
  }

  // ----- host-match configuration (was MatchLobbyManager setters) -------------------------------

  /** Leader picks a mode and stages a host match (transitions the lobby to CONFIGURED). */
  public LobbyResult configure(PlayerAdapter leader, String mode) {
    if (leader == null) {
      return LobbyResult.FAILED;
    }
    GameModeDescriptor descriptor = ctx.modes.resolveMinigame(mode);
    if (descriptor == null) {
      return LobbyResult.NOT_MINIGAME;
    }
    if (games.matchOf(leader.uniqueId()) != null) {
      return LobbyResult.ALREADY_IN_MATCH;
    }
    Lobby lobby = ctx.orCreate(leader);
    if (!lobby.isLeader(leader.uniqueId())) {
      return LobbyResult.NOT_LEADER;
    }
    queue.dropFromQueue(lobby);
    lobby.toConfigured(descriptor.modeId());
    // Seed the lobby's minimum from the mode descriptor so the host screen and the start gate agree
    // with the rest of the menus (and never below the 2-player floor).
    lobby.setMinPlayers(ctx.modes.minPlayers(descriptor.modeId()));
    return LobbyResult.CONFIGURED;
  }

  public LobbyResult setVisibility(PlayerAdapter host, LobbyVisibility visibility) {
    Lobby lobby = requireHost(host);
    if (lobby == null) {
      return notHostResult(host);
    }
    lobby.setVisibility(visibility);
    ctx.broadcast(lobby, "<gray>Lobby is now <white>" + LobbyContext.label(visibility) + "</white>.</gray>");
    return LobbyResult.OK;
  }

  public LobbyResult setMinPlayers(PlayerAdapter host, int minPlayers) {
    Lobby lobby = requireHost(host);
    if (lobby == null) {
      return notHostResult(host);
    }
    lobby.setMinPlayers(minPlayers);
    ctx.broadcast(lobby, "<gray>Minimum players set to <white>" + lobby.minPlayers() + "</white>.</gray>");
    return LobbyResult.OK;
  }

  public LobbyResult setTeamSize(PlayerAdapter host, int teamSize) {
    Lobby lobby = requireHost(host);
    if (lobby == null) {
      return notHostResult(host);
    }
    lobby.setTeamSize(teamSize);
    ctx.broadcast(lobby, "<gray>Players per team set to <white>" + lobby.teamSize() + "</white>.</gray>");
    return LobbyResult.OK;
  }

  public LobbyResult setTeamCount(PlayerAdapter host, int teamCount) {
    Lobby lobby = requireHost(host);
    if (lobby == null) {
      return notHostResult(host);
    }
    lobby.setTeamCount(teamCount);
    ctx.broadcast(lobby, "<gray>Teams set to <white>" + (lobby.teamsEnabled() ? lobby.teamCount() : "FFA") + "</white>.</gray>");
    return LobbyResult.OK;
  }

  public LobbyResult chooseTeam(PlayerAdapter player, int teamIndex) {
    Lobby lobby = ctx.lobbyOf(player == null ? null : player.uniqueId());
    if (lobby == null) {
      return LobbyResult.NOT_FOUND;
    }
    if (!lobby.teamsEnabled() || teamIndex < 0 || teamIndex >= lobby.teamCount()) {
      return LobbyResult.FAILED;
    }
    UUID playerId = player.uniqueId();
    Integer current = lobby.selectedTeam(playerId);
    if (current != null && current == teamIndex) {
      lobby.clearTeamSelection(playerId);
      ctx.broadcast(lobby, "<gray>" + LobbyContext.escape(player.name()) + " left " + LobbyContext.teamName(teamIndex) + ".</gray>");
      return LobbyResult.TEAM_LEFT;
    }
    if (!lobby.selectTeam(playerId, teamIndex)) {
      return LobbyResult.FULL;
    }
    ctx.broadcast(lobby, "<gray>" + LobbyContext.escape(player.name()) + " joined " + LobbyContext.teamName(teamIndex) + ".</gray>");
    return LobbyResult.TEAM_SELECTED;
  }

  /** Leader starts the configured host match. The lobby returns to IDLE so the roster can play again. */
  public LobbyResult start(PlayerAdapter leader) {
    Lobby lobby = ctx.lobbyOf(leader == null ? null : leader.uniqueId());
    if (lobby == null) {
      return LobbyResult.NOT_FOUND;
    }
    if (!lobby.isHost(leader.uniqueId())) {
      return LobbyResult.NOT_HOST;
    }
    if (lobby.modeId() == null) {
      return LobbyResult.NOT_MINIGAME;
    }
    List<UUID> roster = ctx.onlineMemberIds(lobby);
    if (roster.isEmpty() || roster.size() < lobby.requiredPlayersForStart()) {
      return LobbyResult.TOO_FEW;
    }
    if (lobby.teamsEnabled() && roster.size() > lobby.capacity()) {
      return LobbyResult.FULL;
    }
    List<String> modeArgs;
    if (lobby.teamsEnabled()) {
      Map<UUID, Integer> assignments = lobby.assignedTeamsForStart(roster);
      if (assignments.size() < roster.size()) {
        return LobbyResult.FULL;
      }
      modeArgs = new ArrayList<>();
      modeArgs.add("teams:" + lobby.teamCount());
      modeArgs.add("size:" + lobby.teamSize());
      for (Map.Entry<UUID, Integer> assignment : assignments.entrySet()) {
        modeArgs.add("assign:" + assignment.getKey() + ":" + assignment.getValue());
      }
    } else {
      modeArgs = List.of("ffa");
    }
    boolean started = games.startWithPlayers(lobby.modeId(), roster, null, modeArgs);
    // Play-again: keep the roster together and return the lobby to IDLE regardless of outcome (a failed
    // start already messaged the members).
    queue.dropFromQueue(lobby);
    lobby.toIdle();
    return started ? LobbyResult.STARTED : LobbyResult.FAILED;
  }

  // ----- quick-play queue (was MatchmakingManager) ----------------------------------------------

  /** Leader queues the whole roster for quick-play (leader-gated). */
  public LobbyResult queue(PlayerAdapter leader, String mode) {
    return queue.queue(leader, mode);
  }

  /** Cancel the lobby's quick-play queue (leader only), returning it to IDLE. */
  public LobbyResult dequeue(PlayerAdapter player) {
    return queue.dequeue(player);
  }

  public boolean isQueued(UUID playerId) {
    return queue.isQueued(playerId);
  }

  public String queuedMode(UUID playerId) {
    return queue.queuedMode(playerId);
  }

  public int queueSize(String mode) {
    return queue.queueSize(mode);
  }

  public int countdownSeconds(String mode) {
    return queue.countdownSeconds(mode);
  }

  /** One matchmaking pass over every queue. Public so tests can drive it deterministically. */
  public void tick() {
    queue.tick();
  }

  // ----- player lifecycle -----------------------------------------------------------------------

  /** Unified cleanup when a player disconnects: drop them from their lobby and recompute. */
  public void onPlayerQuit(UUID playerId) {
    if (playerId == null) {
      return;
    }
    ctx.invites.clear(playerId);
    Lobby lobby = ctx.lobbyOf(playerId);
    if (lobby == null) {
      return;
    }
    boolean wasHost = lobby.isHost(playerId);
    boolean wasQueued = lobby.isQueued();
    lobby.remove(playerId);
    ctx.playerToLobby.remove(playerId);
    if (lobby.size() == 0) {
      ctx.removeLobby(lobby);
      return;
    }
    if (wasHost) {
      UUID newHost = lobby.oldestMember();
      if (!lobby.transferHost(newHost)) {
        ctx.closeLobby(lobby, null);
        return;
      }
      ctx.broadcast(lobby, "<yellow>" + LobbyContext.escape(ctx.name(newHost)) + " is now the host.</yellow>");
    }
    if (wasQueued) {
      queue.onQueueChanged(lobby.modeId());
    }
  }

  // ----- helpers --------------------------------------------------------------------------------

  private Lobby requireHost(PlayerAdapter host) {
    Lobby lobby = ctx.lobbyOf(host == null ? null : host.uniqueId());
    if (lobby == null || !lobby.isHost(host.uniqueId())) {
      return null;
    }
    return lobby;
  }

  private LobbyResult notHostResult(PlayerAdapter host) {
    return ctx.lobbyOf(host == null ? null : host.uniqueId()) == null ? LobbyResult.NOT_FOUND : LobbyResult.NOT_HOST;
  }
}
