package com.sexidium.core.game.presence;

import com.sexidium.core.platform.PlayerAdapter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who, right now, is not driving their own character.
 *
 * <h2>Why this exists</h2>
 * A client that freezes leaves the player standing in the world as a live, killable entity. In a mode
 * where one death destroys everyone's world that is not an inconvenience, it is the whole run: a
 * 55-in-game-day world was lost to a thirty-second network stall, because the victim died 27 seconds
 * INTO the freeze and 3 seconds BEFORE the disconnect the server could have reacted to.
 *
 * <h2>Derived, never stored — and that is the entire safety argument</h2>
 * <p>Nothing here is written to the player. No flag is set, no revoke is scheduled, and every question
 * is answered by reading this map at the moment it is asked. That is deliberate and it is not a style
 * preference.</p>
 *
 * <p>The obvious implementation — {@code setInvulnerable(true)} — is fail-OPEN here. That flag is
 * persistent NBT that nothing expires, so it is only safe when paired with a revoke scheduled at the
 * moment it is granted; see {@code ExperienceWorldReset.grantGrace}, which documents having caused
 * exactly this bug ("players are not taking damage", following them into the lobby and every later
 * match). That pairing needs a KNOWN DURATION. This state has none: it ends when the player acts,
 * which may be in half a second or never. So there is no revoke to schedule, and any flag written here
 * would be a flag nothing is guaranteed to clear.</p>
 *
 * <p>Derived state inverts the failure. The worst a wrong entry can do is grant one extra sampling
 * interval of protection before the next pass corrects it. It cannot outlive the process, cannot
 * follow a player into another match, and cannot survive a crash.</p>
 *
 * <h2>What "downed" deliberately conflates</h2>
 * Lag, a frozen client, a dropped connection and somebody who walked away all read the same, because
 * the signal is "no input has arrived", not "the socket is shut". In a mode whose entire stake is that
 * any single death is final, all four want the same answer.
 *
 * <p>Server-wide rather than per-match on purpose: the mob guard runs on the platform's target event,
 * which has a mob and a player in hand and no match at all.</p>
 */
public final class PlayerControlWatch {

  /** Why a player is not in control. */
  public enum Loss {
    /** No input has reached the server for long enough that nobody can be driving. */
    IDLE,
    /** The player is gone from the network; only a rejoin can lift this. */
    DISCONNECTED
  }

  private final Map<UUID, Loss> downed = new ConcurrentHashMap<>();
  private final Map<UUID, Long> recoveredAt = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public PlayerControlWatch() {
    this(System::currentTimeMillis);
  }

  /** Test seam: a clock that does not have to be waited out. */
  public PlayerControlWatch(LongSupplier clock) {
    this.clock = clock == null ? System::currentTimeMillis : clock;
  }

  /**
   * Samples one ONLINE player against the idle threshold.
   *
   * <p>An adapter that cannot report idle time answers -1, and -1 must never mark anybody: a platform
   * missing the seam would otherwise make every player on the server permanently unkillable, which is
   * a far worse failure than the one this class exists to prevent.</p>
   *
   * @return true only on the false -> true EDGE, so the caller can run the one-off de-aggro sweep
   *     without repeating it every pass
   */
  public boolean sample(PlayerAdapter player, long idleThresholdMillis) {
    if (player == null) {
      return false;
    }
    UUID playerId = player.uniqueId();
    if (playerId == null) {
      return false;
    }
    if (downed.get(playerId) == Loss.DISCONNECTED) {
      // Only a rejoin lifts a disconnect, and an offline player's idle time is not ours to read.
      return false;
    }
    long idle = player.idleMillis();
    if (idle < 0 || idleThresholdMillis <= 0) {
      return false;
    }
    if (idle < idleThresholdMillis) {
      sawInput(playerId);
      return false;
    }
    return downed.put(playerId, Loss.IDLE) == null;
  }

  /**
   * Evidence of life: an event the player CAUSED. Lifts an idle mark at once, without waiting for the
   * next sampling pass.
   *
   * <p>Never lifts a disconnect. An offline player generates no events, so this is belt and braces —
   * but the asymmetry is the rule, not an accident of what happens to reach here.</p>
   */
  public void sawInput(UUID playerId) {
    if (playerId == null || downed.get(playerId) == Loss.DISCONNECTED) {
      return;
    }
    if (downed.remove(playerId) != null) {
      recoveredAt.put(playerId, clock.getAsLong());
    }
  }

  /** The player left the network. Held until they come back or their slot is released. */
  public void markDisconnected(UUID playerId) {
    if (playerId != null) {
      downed.put(playerId, Loss.DISCONNECTED);
      recoveredAt.remove(playerId);
    }
  }

  /** The player is back and in control. Starts the recovery tail. */
  public void clear(UUID playerId) {
    if (playerId != null && downed.remove(playerId) != null) {
      recoveredAt.put(playerId, clock.getAsLong());
    }
  }

  /** The player's slot is gone. Leaves nothing behind — no mark, no tail. */
  public void forget(UUID playerId) {
    if (playerId != null) {
      downed.remove(playerId);
      recoveredAt.remove(playerId);
    }
  }

  public boolean downed(UUID playerId) {
    return downed(playerId, 0L);
  }

  /**
   * Whether this player is not in control, counting {@code tailMillis} of protection after they get it
   * back.
   *
   * <p>The tail is why somebody rescued while standing in lava gets a moment to move instead of taking
   * the whole backlog the frame their first input lands — which would turn a rescue into a delayed
   * execution. A tail of 0 means protection lifts on the very first input.</p>
   */
  public boolean downed(UUID playerId, long tailMillis) {
    if (playerId == null) {
      return false;
    }
    if (downed.containsKey(playerId)) {
      return true;
    }
    if (tailMillis <= 0) {
      return false;
    }
    Long recovered = recoveredAt.get(playerId);
    if (recovered == null) {
      return false;
    }
    if (clock.getAsLong() - recovered < tailMillis) {
      return true;
    }
    recoveredAt.remove(playerId);
    return false;
  }

  public Loss reason(UUID playerId) {
    return playerId == null ? null : downed.get(playerId);
  }

  /**
   * Whether anybody at all is down. The platform's mob-target guard asks this first, on a path that
   * fires whenever any mob anywhere re-evaluates a target, and the answer is almost always false.
   */
  public boolean anyDowned() {
    return !downed.isEmpty();
  }

  public Set<UUID> downedPlayers() {
    return Set.copyOf(downed.keySet());
  }
}
