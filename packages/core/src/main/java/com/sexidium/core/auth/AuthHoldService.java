package com.sexidium.core.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players this node currently has frozen, and until when.
 *
 * <p>Node-local by nature — the player is connected to exactly one node and the freeze is that node's
 * event handlers — so this holds no database and no platform types. The durable half of the same fact
 * lives in {@code auth_requests.hold}, which is what lets the deciding node tell this one to let go.</p>
 */
public final class AuthHoldService {

  /** One held player. {@code previousGameMode} is what release must put back. */
  public record Hold(UUID playerId, String identityId, String requestId, String previousGameMode,
      long startedAt, long deadline) {
  }

  private final Map<UUID, Hold> held = new ConcurrentHashMap<>();

  public void hold(UUID playerId, String identityId, String requestId, String previousGameMode,
      long deadline) {
    if (playerId == null) {
      return;
    }
    held.put(playerId, new Hold(playerId, identityId, requestId, previousGameMode,
        System.currentTimeMillis(), deadline));
  }

  public Optional<Hold> of(UUID playerId) {
    return playerId == null ? Optional.empty() : Optional.ofNullable(held.get(playerId));
  }

  public boolean isHeld(UUID playerId) {
    return playerId != null && held.containsKey(playerId);
  }

  /** The hold on a given identity, whichever player uuid is carrying it. */
  public Optional<Hold> byIdentity(String identityId) {
    if (identityId == null) {
      return Optional.empty();
    }
    return held.values().stream().filter(hold -> identityId.equals(hold.identityId())).findFirst();
  }

  /** Stop holding a player. Returns what was being held, so the caller can restore it. */
  public Optional<Hold> release(UUID playerId) {
    return playerId == null ? Optional.empty() : Optional.ofNullable(held.remove(playerId));
  }

  public List<Hold> all() {
    return new ArrayList<>(held.values());
  }

  /**
   * Everything whose deadline has passed, removed as it is returned.
   *
   * <p>Removed rather than merely reported so a caller that ticks every second cannot kick the same
   * player twice — the second tick simply finds nothing.</p>
   */
  public List<Hold> tickExpired(long now) {
    List<Hold> expired = new ArrayList<>();
    for (Hold hold : new ArrayList<>(held.values())) {
      if (hold.deadline() <= now && held.remove(hold.playerId()) != null) {
        expired.add(hold);
      }
    }
    return expired;
  }

  public void clear() {
    held.clear();
  }
}
