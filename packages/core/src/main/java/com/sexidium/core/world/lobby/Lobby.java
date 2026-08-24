package com.sexidium.core.world.lobby;
import com.sexidium.core.data.FriendGraph;
import com.sexidium.core.world.lobby.LobbyEnums.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The single pre-match group, merging the old social {@code Party} and the host {@code MatchLobby}. A
 * Lobby is an ordered roster (leader first) plus a {@link LobbyState}: IDLE (just a group), QUEUED (one
 * quick-play ticket) or CONFIGURED (a staged host match with a mode + team shape). It is created the
 * moment a player needs a group (invite, queue or host) and survives matches so the same friends return
 * together ("play again").
 *
 * <p>Identity is the creating leader's UUID and never changes, even when the host role transfers — this
 * is what fixes the old name-keyed lobby id that collided across players and went stale after a host
 * hand-off.</p>
 */
public final class Lobby implements Roster {
  /** Hard cap on coloured teams (host-selectable). */
  public static final int MAX_TEAMS = 4;

  private final UUID id;
  private UUID leader;
  private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

  private LobbyState state = LobbyState.IDLE;
  private String modeId; // null until QUEUED / CONFIGURED
  private LobbyVisibility visibility = LobbyVisibility.PUBLIC;

  // ----- team config (only meaningful in CONFIGURED state) -----
  // Number of coloured teams: 0 = free-for-all, otherwise 2..MAX_TEAMS.
  private int teamCount;
  private int teamSize = 2; // players PER team (only meaningful when teamCount > 0)
  // Floored at 2 everywhere: a minigame match always needs an opponent. Seeded from the mode descriptor
  // on configure() and adjustable by the host, but never below 2.
  private int minPlayers = 2;
  private final Map<UUID, Integer> selectedTeams = new LinkedHashMap<>();

  public Lobby(UUID leader) {
    this.id = leader;
    this.leader = leader;
    members.add(leader);
  }

  public UUID id() {
    return id;
  }

  @Override
  public UUID leader() {
    return leader;
  }

  /** Alias for {@link #leader()} kept for host-centric call sites. */
  public UUID host() {
    return leader;
  }

  @Override
  public List<UUID> members() {
    // Defensive copy — the old Party.members()/MatchLobby.members() leaked the live internal set.
    return List.copyOf(members);
  }

  @Override
  public boolean isLeader(UUID playerId) {
    return playerId != null && playerId.equals(leader);
  }

  public boolean isHost(UUID playerId) {
    return isLeader(playerId);
  }

  @Override
  public boolean isMember(UUID playerId) {
    return playerId != null && members.contains(playerId);
  }

  public boolean contains(UUID playerId) {
    return isMember(playerId);
  }

  @Override
  public int size() {
    return members.size();
  }

  public boolean transferHost(UUID playerId) {
    if (playerId == null || !members.contains(playerId)) {
      return false;
    }
    leader = playerId;
    return true;
  }

  public UUID oldestMember() {
    for (UUID memberId : members) {
      return memberId;
    }
    return null;
  }

  public void add(UUID playerId) {
    if (playerId != null) {
      members.add(playerId);
    }
  }

  public void remove(UUID playerId) {
    members.remove(playerId);
    selectedTeams.remove(playerId);
  }

  // ----- state ------------------------------------------------------------------------------

  public LobbyState state() {
    return state;
  }

  public boolean isIdle() {
    return state == LobbyState.IDLE;
  }

  public boolean isQueued() {
    return state == LobbyState.QUEUED;
  }

  public boolean isConfigured() {
    return state == LobbyState.CONFIGURED;
  }

  public String modeId() {
    return modeId;
  }

  public void toIdle() {
    state = LobbyState.IDLE;
    modeId = null;
    selectedTeams.clear();
  }

  public void toQueued(String mode) {
    state = LobbyState.QUEUED;
    modeId = mode;
  }

  public void toConfigured(String mode) {
    state = LobbyState.CONFIGURED;
    modeId = mode;
  }

  // ----- visibility -------------------------------------------------------------------------

  public LobbyVisibility visibility() {
    return visibility;
  }

  public void setVisibility(LobbyVisibility visibility) {
    if (visibility != null) {
      this.visibility = visibility;
    }
  }

  /** Legacy convenience: true unless the lobby is invite-only. */
  public boolean isOpen() {
    return visibility != LobbyVisibility.INVITE_ONLY;
  }

  /**
   * Whether the given player may join: the host always, a holder of a live invite always, a PUBLIC lobby
   * anyone, a FRIENDS_ONLY lobby a friend of the host.
   */
  public boolean canJoin(UUID playerId, FriendGraph friends, boolean invited) {
    if (isHost(playerId) || invited) {
      return true;
    }
    return switch (visibility) {
      case PUBLIC -> true;
      case FRIENDS_ONLY -> friends != null && friends.areFriends(leader, playerId);
      case INVITE_ONLY -> false;
    };
  }

  // ----- team config (carried verbatim from MatchLobby) -------------------------------------

  /** Players allowed per team (>=1); only used when {@link #teamCount()} &gt; 0. */
  public int teamSize() {
    return teamSize;
  }

  public void setTeamSize(int teamSize) {
    this.teamSize = Math.max(1, teamSize);
    pruneTeamSelections();
  }

  /** Number of coloured teams: 0 = free-for-all, else 2..{@link #MAX_TEAMS}. */
  public int teamCount() {
    return teamCount;
  }

  public void setTeamCount(int teamCount) {
    this.teamCount = teamCount <= 0 ? 0 : Math.max(2, Math.min(MAX_TEAMS, teamCount));
    pruneTeamSelections();
  }

  public boolean teamsEnabled() {
    return teamCount >= 2;
  }

  /** Max players this lobby accepts when configured with teams; unbounded for free-for-all. */
  public int capacity() {
    return teamsEnabled() ? teamCount * Math.max(1, teamSize) : Integer.MAX_VALUE;
  }

  public int minPlayers() {
    return minPlayers;
  }

  public int requiredPlayersForStart() {
    return teamsEnabled() ? Math.max(minPlayers, teamCount) : minPlayers;
  }

  public void setMinPlayers(int minPlayers) {
    this.minPlayers = Math.max(2, minPlayers);
  }

  public Integer selectedTeam(UUID playerId) {
    if (!teamsEnabled() || playerId == null) {
      return null;
    }
    Integer teamIndex = selectedTeams.get(playerId);
    return teamIndex != null && teamIndex >= 0 && teamIndex < teamCount ? teamIndex : null;
  }

  public Map<UUID, Integer> selectedTeams() {
    return new LinkedHashMap<>(selectedTeams);
  }

  public boolean selectTeam(UUID playerId, int teamIndex) {
    if (!teamsEnabled() || playerId == null || !members.contains(playerId) || teamIndex < 0 || teamIndex >= teamCount) {
      return false;
    }
    Integer current = selectedTeam(playerId);
    if (current != null && current == teamIndex) {
      return true;
    }
    if (selectedTeamSize(teamIndex) >= teamSize) {
      return false;
    }
    selectedTeams.put(playerId, teamIndex);
    return true;
  }

  public void clearTeamSelection(UUID playerId) {
    selectedTeams.remove(playerId);
  }

  public int selectedTeamSize(int teamIndex) {
    if (!teamsEnabled() || teamIndex < 0 || teamIndex >= teamCount) {
      return 0;
    }
    int size = 0;
    for (Map.Entry<UUID, Integer> entry : selectedTeams.entrySet()) {
      if (members.contains(entry.getKey()) && entry.getValue() != null && entry.getValue() == teamIndex) {
        size++;
      }
    }
    return size;
  }

  public List<UUID> unselectedMembers() {
    List<UUID> unselected = new ArrayList<>();
    for (UUID memberId : members) {
      if (selectedTeam(memberId) == null) {
        unselected.add(memberId);
      }
    }
    return unselected;
  }

  public Map<UUID, Integer> assignedTeamsForStart(List<UUID> roster) {
    Map<UUID, Integer> assignments = new LinkedHashMap<>();
    if (!teamsEnabled() || roster == null || roster.isEmpty()) {
      return assignments;
    }
    int[] teamSizes = new int[teamCount];
    List<UUID> unassigned = new ArrayList<>();
    for (UUID playerId : roster) {
      Integer selected = selectedTeam(playerId);
      if (selected != null && teamSizes[selected] < teamSize) {
        assignments.put(playerId, selected);
        teamSizes[selected]++;
      } else {
        unassigned.add(playerId);
      }
    }

    Collections.shuffle(unassigned);
    for (UUID playerId : unassigned) {
      List<Integer> available = availableTeamIndexes(teamSizes);
      if (available.isEmpty()) {
        break;
      }
      int teamIndex = available.get(ThreadLocalRandom.current().nextInt(available.size()));
      assignments.put(playerId, teamIndex);
      teamSizes[teamIndex]++;
    }
    return assignments;
  }

  private List<Integer> availableTeamIndexes(int[] teamSizes) {
    List<Integer> available = new ArrayList<>();
    for (int teamIndex = 0; teamIndex < teamSizes.length; teamIndex++) {
      if (teamSizes[teamIndex] < teamSize) {
        available.add(teamIndex);
      }
    }
    return available;
  }

  private void pruneTeamSelections() {
    if (!teamsEnabled()) {
      selectedTeams.clear();
      return;
    }
    int[] teamSizes = new int[teamCount];
    Iterator<Map.Entry<UUID, Integer>> iterator = selectedTeams.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Integer> entry = iterator.next();
      Integer teamIndex = entry.getValue();
      if (!members.contains(entry.getKey()) || teamIndex == null || teamIndex < 0 || teamIndex >= teamCount
          || teamSizes[teamIndex] >= teamSize) {
        iterator.remove();
        continue;
      }
      teamSizes[teamIndex]++;
    }
  }
}
