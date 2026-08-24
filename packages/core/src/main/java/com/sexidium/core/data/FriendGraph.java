package com.sexidium.core.data;

import java.util.List;
import java.util.UUID;

/**
 * The read seam over the persistent friend graph that the lobby subsystem depends on: who a player knows.
 * Implemented by {@link FriendService}; kept as an interface so {@link Lobby#canJoin} and the invite GUI
 * depend on an abstraction (and tests can supply a POJO fake with no SQLite).
 */
public interface FriendGraph {
  boolean areFriends(UUID firstPlayerId, UUID secondPlayerId);

  List<FriendService.Entry> friends(UUID ownerId);
}
