package com.sexidium.core.world.lobby;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified, time-boxed invite store for lobbies, replacing the old {@code PartyManager} invite map and the
 * untimed {@code MatchLobby.invited} set. Keyed {@code target -> {lobbyId -> expiryEpochMillis}}; a player
 * may hold pending invites from several lobbies at once (which is what produces the AMBIGUOUS accept path).
 */
public final class InviteBook {
  private final Map<UUID, Map<UUID, Long>> byTarget = new ConcurrentHashMap<>();

  public void invite(UUID target, UUID lobbyId, long ttlMillis) {
    if (target == null || lobbyId == null) {
      return;
    }
    byTarget.computeIfAbsent(target, ignored -> new ConcurrentHashMap<>())
        .put(lobbyId, System.currentTimeMillis() + Math.max(0L, ttlMillis));
  }

  public boolean isInvited(UUID target, UUID lobbyId) {
    if (target == null || lobbyId == null) {
      return false;
    }
    purgeExpired(target);
    Map<UUID, Long> pending = byTarget.get(target);
    return pending != null && pending.containsKey(lobbyId);
  }

  /** Removes the invite from one lobby; returns true when one was present. */
  public boolean decline(UUID target, UUID lobbyId) {
    Map<UUID, Long> pending = byTarget.get(target);
    if (pending == null) {
      return false;
    }
    boolean removed = pending.remove(lobbyId) != null;
    if (pending.isEmpty()) {
      byTarget.remove(target);
    }
    return removed;
  }

  /** The lobby ids that have a live invite for this target (expired ones purged first). */
  public List<UUID> pending(UUID target) {
    purgeExpired(target);
    Map<UUID, Long> pending = byTarget.get(target);
    return pending == null ? List.of() : new ArrayList<>(pending.keySet());
  }

  public void clear(UUID target) {
    if (target != null) {
      byTarget.remove(target);
    }
  }

  /** Drops a now-closed lobby from every target's pending set. */
  public void removeLobby(UUID lobbyId) {
    if (lobbyId == null) {
      return;
    }
    for (Map<UUID, Long> pending : byTarget.values()) {
      pending.remove(lobbyId);
    }
    byTarget.entrySet().removeIf(entry -> entry.getValue().isEmpty());
  }

  public void purgeExpired(UUID target) {
    Map<UUID, Long> pending = byTarget.get(target);
    if (pending == null) {
      return;
    }
    long now = System.currentTimeMillis();
    pending.values().removeIf(expiry -> expiry < now);
    if (pending.isEmpty()) {
      byTarget.remove(target);
    }
  }
}
