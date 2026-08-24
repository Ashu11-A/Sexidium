package com.sexidium.core.world.lobby;
import com.sexidium.core.data.FriendGraph;
import com.sexidium.core.world.lobby.LobbyEnums.*;

import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared substrate for the {@link LobbyManager} facade and its extracted modules ({@link LobbyQueue}).
 * Owns the platform adapters, the core lobby indices and the low-level helpers (reverse-index maintenance,
 * messaging, config reads and the small static formatters) that more than one responsibility needs.
 *
 * <p>All mutation runs on the server main thread; the maps are concurrent only for safe read access from
 * the menus / HUD.</p>
 */
final class LobbyContext {
  final ServerAdapter server;
  final MatchLauncher games;
  final FriendGraph friends; // nullable (no DB)
  final ConfigurationAdapter config;
  final ModeResolver modes;
  final InviteBook invites = new InviteBook();

  // id -> Lobby. id == the creating leader's UUID.
  /**
   * Mirrors membership into the shared database so a party survives its members being on different
   * nodes. Null standalone, where the maps below are already the whole truth.
   *
   * <p>WRITE-THROUGH, not read-through: these maps stay authoritative for the node that owns the
   * group, because the countdown state machine they feed is single-writer and ticked once a second.
   * The mirror exists so OTHER nodes can answer "are these two in a party?" -- see
   * {@code ExperienceService.canEnterDirectly}, which a worker runs about a party formed on the
   * lobby it never saw.</p>
   */
  private volatile com.sexidium.core.network.LobbyDirectory directory;
  private volatile String nodeId = "standalone";

  public void setDirectory(com.sexidium.core.network.LobbyDirectory directory, String nodeId) {
    this.directory = directory;
    this.nodeId = nodeId == null ? "standalone" : nodeId;
  }

  /** Push a lobby's current membership to the shared directory. No-op standalone. */
  void mirror(Lobby lobby) {
    com.sexidium.core.network.LobbyDirectory target = directory;
    if (target == null || lobby == null) {
      return;
    }
    target.upsertGroup(new com.sexidium.core.network.LobbyDirectory.Group(
        lobby.id(), lobby.leader(), lobby.modeId(), String.valueOf(lobby.state()),
        String.valueOf(lobby.visibility()), lobby.teamCount(), nodeId));
    for (UUID memberId : lobby.members()) {
      target.addMember(lobby.id(), memberId, null, -1);
    }
  }

  void mirrorRemoveMember(UUID lobbyId, UUID playerId) {
    com.sexidium.core.network.LobbyDirectory target = directory;
    if (target != null && lobbyId != null) {
      target.removeMember(lobbyId, playerId);
    }
  }

  void mirrorRemoveLobby(UUID lobbyId) {
    com.sexidium.core.network.LobbyDirectory target = directory;
    if (target != null && lobbyId != null) {
      target.removeGroup(lobbyId);
    }
  }

  final Map<UUID, Lobby> lobbiesById = new ConcurrentHashMap<>();
  // player -> the id of the lobby they belong to (the single reverse index).
  final Map<UUID, UUID> playerToLobby = new ConcurrentHashMap<>();
  // mode -> ordered lobby ids currently QUEUED for it (insertion order = matchmaking priority).
  final Map<String, LinkedHashSet<UUID>> queueByMode = new ConcurrentHashMap<>();
  // mode -> remaining countdown seconds (absent = not counting down yet).
  final Map<String, Integer> countdowns = new ConcurrentHashMap<>();
  // mode -> consecutive failed launches, so a permanently-unstartable mode does not loop forever.
  final Map<String, Integer> launchFailures = new ConcurrentHashMap<>();

  // Wired in after construction (mutual dependency between the facade modules).
  LobbyQueue queue;

  LobbyContext(ServerAdapter server, MatchLauncher games, FriendGraph friends) {
    this.server = server;
    this.games = games;
    this.friends = friends;
    this.config = server.configuration();
    this.modes = new ModeResolver(games);
  }

  void clearAll() {
    lobbiesById.clear();
    playerToLobby.clear();
    queueByMode.clear();
    countdowns.clear();
    launchFailures.clear();
  }

  // ----- registry / reverse index --------------------------------------------------------------

  /** The lobby this player belongs to, or null. Cleans a stale reverse-index entry as a side effect. */
  Lobby lobbyOf(UUID playerId) {
    if (playerId == null) {
      return null;
    }
    UUID id = playerToLobby.get(playerId);
    if (id == null) {
      return null;
    }
    Lobby lobby = lobbiesById.get(id);
    if (lobby == null || !lobby.contains(playerId)) {
      playerToLobby.remove(playerId);
      return null;
    }
    return lobby;
  }

  Lobby orCreate(PlayerAdapter player) {
    Lobby lobby = lobbyOf(player.uniqueId());
    if (lobby != null) {
      return lobby;
    }
    Lobby created = new Lobby(player.uniqueId());
    lobbiesById.put(created.id(), created);
    playerToLobby.put(player.uniqueId(), created.id());
    mirror(created);
    return created;
  }

  void detachSolo(UUID playerId) {
    UUID id = playerToLobby.remove(playerId);
    if (id == null) {
      return;
    }
    Lobby lobby = lobbiesById.get(id);
    if (lobby == null) {
      return;
    }
    lobby.remove(playerId);
    mirrorRemoveMember(lobby.id(), playerId);
    if (lobby.size() == 0) {
      removeLobby(lobby);
    }
  }

  void removeLobby(Lobby lobby) {
    if (lobby == null) {
      return;
    }
    queue.dropFromQueue(lobby);
    mirrorRemoveLobby(lobby.id());
    lobbiesById.remove(lobby.id());
    invites.removeLobby(lobby.id());
    for (UUID memberId : lobby.members()) {
      playerToLobby.remove(memberId);
    }
  }

  void closeLobby(Lobby lobby, String miniMessage) {
    if (lobby == null) {
      return;
    }
    String mode = lobby.modeId();
    for (UUID memberId : lobby.members()) {
      playerToLobby.remove(memberId);
      if (miniMessage != null) {
        server.player(memberId).filter(PlayerAdapter::online).ifPresent(member -> member.sendMiniMessage(miniMessage));
      }
    }
    if (mode != null) {
      LinkedHashSet<UUID> set = queueByMode.get(mode);
      if (set != null) {
        set.remove(lobby.id());
      }
    }
    lobbiesById.remove(lobby.id());
    invites.removeLobby(lobby.id());
    if (mode != null) {
      queue.onQueueChanged(mode);
    }
  }

  // ----- messaging / lookups -------------------------------------------------------------------

  List<UUID> onlineMemberIds(Lobby lobby) {
    List<UUID> online = new ArrayList<>();
    for (UUID memberId : lobby.members()) {
      if (server.player(memberId).filter(PlayerAdapter::online).isPresent()) {
        online.add(memberId);
      }
    }
    return online;
  }

  void broadcast(Lobby lobby, String miniMessage) {
    for (UUID memberId : lobby.members()) {
      server.player(memberId).filter(PlayerAdapter::online).ifPresent(member -> member.sendMiniMessage(miniMessage));
    }
  }

  void actionbar(UUID playerId, String miniMessage) {
    server.player(playerId).filter(PlayerAdapter::online).ifPresent(member -> member.sendActionBar(miniMessage));
  }

  String name(UUID playerId) {
    return server.player(playerId).map(PlayerAdapter::name).orElse("another player");
  }

  // ----- config --------------------------------------------------------------------------------

  int maxSize() {
    int legacy = config.getInt("party.max-size", 8);
    return Math.max(2, config.getInt("lobby.max-size", legacy));
  }

  long inviteTtlMillis() {
    long legacy = config.getLong("party.invite-expiry-seconds", 60L);
    return Math.max(5L, config.getLong("lobby.invite-expiry-seconds", legacy)) * 1000L;
  }

  int countdownSecondsConfig() {
    int legacy = config.getInt("matchmaking.countdown-seconds", 5);
    return Math.max(1, config.getInt("lobby.queue.countdown-seconds", legacy));
  }

  int maxPlayers() {
    int legacy = config.getInt("matchmaking.max-players", 12);
    return Math.max(2, config.getInt("lobby.queue.max-players", legacy));
  }

  /**
   * The mode args a quick-play launch should use for the given contributing groups (each queued lobby is
   * one group). When team matchmaking applies — {@code lobby.matchmaking.teams} ≥ 2, the mode is not in
   * {@code lobby.matchmaking.ffa-modes}, and at least two groups are present so no group must be split —
   * this returns {@code teams:<n>} plus an {@code assign:<uuid>:<index>} per player from
   * {@link TeamAllocator#balanceGroups} (groups kept intact, balanced into N teams). Otherwise it returns
   * {@code ffa} (the previous behaviour), so solo/FFA modes are unchanged. The mode's existing
   * {@code teamCountFromArgs}/{@code teamAssignmentsFromArgs} → {@code allocateAssigned} path consumes it.
   */
  List<String> matchmakingTeamArgs(String mode, List<List<UUID>> groups) {
    int configured = config.getInt("lobby.matchmaking.teams", 2);
    List<String> ffaModes = config.getStringList("lobby.matchmaking.ffa-modes");
    boolean ffaMode = ffaModes != null
        && ffaModes.stream().anyMatch(entry -> normalize(entry).equals(normalize(mode)));
    int groupCount = 0;
    for (List<UUID> group : groups) {
      if (group != null && !group.isEmpty()) {
        groupCount++;
      }
    }
    if (ffaMode || configured < 2 || groupCount < 2) {
      return List.of("ffa");
    }
    // Cap teams at the group count so no team is left empty, but never fewer than two sides.
    int teamCount = Math.max(2, Math.min(configured, groupCount));
    Map<UUID, Integer> assignments = com.sexidium.core.game.team.TeamAllocator.balanceGroups(groups, teamCount);
    List<String> args = new ArrayList<>();
    args.add("teams:" + teamCount);
    for (Map.Entry<UUID, Integer> entry : assignments.entrySet()) {
      args.add("assign:" + entry.getKey() + ":" + entry.getValue());
    }
    return args;
  }

  // ----- static formatters ---------------------------------------------------------------------

  static String label(LobbyVisibility visibility) {
    return switch (visibility) {
      case PUBLIC -> "public";
      case FRIENDS_ONLY -> "friends-only";
      case INVITE_ONLY -> "invite-only";
    };
  }

  static String searchingMessage(int size, int min) {
    return "<aqua>Searching for players…</aqua> <gray>(" + size + "/" + min + ")</gray>";
  }

  static String normalize(String modeId) {
    return modeId == null ? "" : modeId.toLowerCase(Locale.ROOT).trim();
  }

  static String escape(String value) {
    return value == null ? "" : value.replace("<", "&lt;").replace(">", "&gt;");
  }

  static String teamName(int teamIndex) {
    TeamColor[] palette = TeamColor.values();
    if (teamIndex >= 0 && teamIndex < palette.length) {
      return palette[teamIndex].colorize(palette[teamIndex].displayName());
    }
    return "Team " + (teamIndex + 1);
  }
}
