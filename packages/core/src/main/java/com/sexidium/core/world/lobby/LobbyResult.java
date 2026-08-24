package com.sexidium.core.world.lobby;

/**
 * The single outcome type for every {@link LobbyManager} operation, merging the old
 * {@code PartyManager.InviteResult}/{@code AcceptResult}, {@code MatchLobbyManager.Result} and
 * {@code MatchmakingManager.JoinResult}.
 */
public enum LobbyResult {
  // ----- successes -----
  JOINED, LEFT, QUEUED, DEQUEUED, CONFIGURED, STARTED, INVITE_SENT, DISBANDED, OK,
  TEAM_SELECTED, TEAM_LEFT,
  // ----- group / invite outcomes -----
  SELF, NOT_LEADER, FULL, TARGET_IN_PARTY, NO_INVITE, AMBIGUOUS, ALREADY_IN_PARTY,
  // ----- join outcomes -----
  NOT_FOUND, NOT_INVITED, ALREADY_IN, NOT_HOST, GONE,
  // ----- queue / start outcomes -----
  ALREADY_QUEUED, ALREADY_IN_MATCH, NOT_MINIGAME, TOO_FEW, FAILED
}
