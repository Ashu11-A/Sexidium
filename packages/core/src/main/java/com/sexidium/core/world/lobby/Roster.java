package com.sexidium.core.world.lobby;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The shared "group" abstraction every command, menu and gameplay system operates on, regardless of
 * whether the group is just hanging out, queued for quick-play, or staging a host match. {@link Lobby}
 * is the only implementation; depending on this interface lets group broadcasts, queue group-pull and
 * experience access checks share one code path instead of the old scattered
 * {@code PartyManager.onlineMembers} / {@code MatchmakingManager.group} / lobby roster resolution.
 */
public interface Roster {
  /** The current leader / host of the group. */
  UUID leader();

  /** An immutable, insertion-ordered snapshot of the member ids (leader first). */
  List<UUID> members();

  default boolean isMember(UUID playerId) {
    return playerId != null && members().contains(playerId);
  }

  default boolean isLeader(UUID playerId) {
    return playerId != null && playerId.equals(leader());
  }

  default int size() {
    return members().size();
  }

  /** The online members of this roster, in roster order — the single fan-out helper for group ops. */
  default List<PlayerAdapter> onlineMembers(ServerAdapter server) {
    List<PlayerAdapter> online = new ArrayList<>();
    if (server == null) {
      return online;
    }
    for (UUID memberId : members()) {
      server.player(memberId).filter(PlayerAdapter::online).ifPresent(online::add);
    }
    return online;
  }
}
