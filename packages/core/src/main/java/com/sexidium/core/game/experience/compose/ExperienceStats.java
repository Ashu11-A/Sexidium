package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.experience.ExperienceHost;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Standardized, persistent stat counters shared across an experience's challenges, so common
 * information (deaths, total blocks broken) is tracked ONCE centrally rather than each challenge
 * re-counting it. Backed by the shared {@link com.sexidium.core.game.experience.ExperienceState}
 * under a {@code stats.} namespace, so counts survive disconnects and restarts.
 *
 * <p>Well-known counters have named helpers; challenges may also keep their own counters via the
 * generic {@link #add}/{@link #get} keyed by {@code <challengeId>.<name>}.</p>
 *
 * <h2>Two scopes, and why the difference matters</h2>
 * Most counters here describe the WORLD: blocks broken in it, deaths that happened in it. When a mode
 * replaces its world ({@code deathresets}) those numbers describe somewhere that no longer exists, and
 * the reset is right to drop them — the shared state lives in the world folder and dies with it.
 *
 * <p>A second family, under {@link #RUN_KEY_PREFIX}, describes the RUN instead: how long it has been
 * played, by whom, and how many times each player has died across every world it has been through. A
 * regeneration must carry these forward, and does so by naming the prefix in its allowlist — see
 * {@code ExperienceWorldReset} and {@code StateCarry}. Keeping the two families in separate namespaces
 * is what lets one rule ("carry {@code stats.run.*}") be both complete and safe, instead of a growing
 * list of individual keys somebody has to remember to extend.</p>
 */
public final class ExperienceStats {
  private static final String PREFIX = "stats.";
  private static final String DEATHS_TOTAL = "deaths.total";
  private static final String DEATHS_PLAYER = "deaths.player.";
  private static final String BLOCKS_BROKEN = "blocks.broken";

  private static final String RUN = "run.";
  private static final String RUN_SECONDS_TOTAL = RUN + "seconds.total";
  private static final String RUN_SECONDS_PLAYER = RUN + "seconds.player.";
  private static final String RUN_DEATHS_TOTAL = RUN + "deaths.total";
  private static final String RUN_DEATHS_PLAYER = RUN + "deaths.player.";

  /**
   * The FULL shared-state key prefix of the run-lifetime counters — {@code stats.run.} — as it appears
   * in {@code state.yml}, not as it is passed to {@link #get}. This is what a world regeneration's carry
   * allowlist names, so it is spelled out once here rather than in every mode that resets a world.
   */
  public static final String RUN_KEY_PREFIX = PREFIX + RUN;

  private final ExperienceHost host;

  public ExperienceStats(ExperienceHost host) {
    this.host = host;
  }

  public long get(String key) {
    return host.sharedState() == null ? 0L : host.sharedState().getLong(PREFIX + key, 0L);
  }

  public void add(String key, long delta) {
    if (host.sharedState() != null) {
      host.sharedState().setLong(PREFIX + key, get(key) + delta);
    }
  }

  public void set(String key, long value) {
    if (host.sharedState() != null) {
      host.sharedState().setLong(PREFIX + key, value);
    }
  }

  // ----- well-known shared counters ------------------------------------------------------------

  /** Records one open-ended "death" (soft-respawn) for the player and the whole experience. */
  public void recordDeath(PlayerAdapter player) {
    add(DEATHS_TOTAL, 1L);
    if (player != null) {
      add(DEATHS_PLAYER + player.uniqueId(), 1L);
    }
  }

  public long totalDeaths() {
    return get(DEATHS_TOTAL);
  }

  public long deaths(PlayerAdapter player) {
    return player == null ? 0L : get(DEATHS_PLAYER + player.uniqueId());
  }

  public void recordBlocksBroken(long amount) {
    if (amount > 0) {
      add(BLOCKS_BROKEN, amount);
    }
  }

  public long blocksBroken() {
    return get(BLOCKS_BROKEN);
  }

  // ----- run-lifetime counters (carried across a world regeneration) ---------------------------

  /**
   * Adds occupied time to the run and to each player who was present for it.
   *
   * <p>Taken as a batch rather than a second at a time because the caller buffers: writing here marks
   * the shared state dirty, and the state is a file. See {@link OccupancyLedger} for the accrual rule
   * (the total is wall clock while occupied, so it is NOT the sum of the per-player figures).</p>
   */
  public void addRunSeconds(long seconds, Map<UUID, Long> playerSeconds) {
    if (seconds > 0) {
      add(RUN_SECONDS_TOTAL, seconds);
    }
    if (playerSeconds == null) {
      return;
    }
    for (Map.Entry<UUID, Long> entry : playerSeconds.entrySet()) {
      if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
        add(RUN_SECONDS_PLAYER + entry.getKey(), entry.getValue());
      }
    }
  }

  /** Total seconds this run has been occupied by at least one player. */
  public long runSeconds() {
    return get(RUN_SECONDS_TOTAL);
  }

  /** Seconds this player has spent inside the experience, across every world the run has had. */
  public long runSeconds(UUID playerId) {
    return playerId == null ? 0L : get(RUN_SECONDS_PLAYER + playerId);
  }

  /** Every player who has ever spent time in this run, and how long. */
  public Map<UUID, Long> runSecondsByPlayer() {
    return byPlayer(RUN_SECONDS_PLAYER);
  }

  /**
   * Records a death against the run. Counted at the DEATH, not at the respawn: in a mode where a death
   * replaces the world, the respawn happens after the regeneration has already snapshotted what it will
   * carry, so a death counted there is a death the next world never hears about.
   */
  public void recordRunDeath(PlayerAdapter player) {
    add(RUN_DEATHS_TOTAL, 1L);
    if (player != null) {
      add(RUN_DEATHS_PLAYER + player.uniqueId(), 1L);
    }
  }

  /** Deaths across the whole run, every world included. */
  public long runDeaths() {
    return get(RUN_DEATHS_TOTAL);
  }

  /** One player's deaths across the whole run. */
  public long runDeaths(UUID playerId) {
    return playerId == null ? 0L : get(RUN_DEATHS_PLAYER + playerId);
  }

  /** Every player who has ever died in this run, and how often. */
  public Map<UUID, Long> runDeathsByPlayer() {
    return byPlayer(RUN_DEATHS_PLAYER);
  }

  /**
   * Reads back a whole family of per-player counters by scanning the shared state's keys.
   *
   * <p>There is no separate index of "players who have been here": the keys ARE the index, which is
   * what keeps a player's history intact whether or not they are online, whether or not they are still
   * in the match, and across every restart. A key whose tail is not a UUID (a hand-edited
   * {@code state.yml}) is skipped rather than allowed to fail the read for everyone else.</p>
   */
  private Map<UUID, Long> byPlayer(String suffixPrefix) {
    Map<UUID, Long> byPlayer = new LinkedHashMap<>();
    if (host.sharedState() == null) {
      return byPlayer;
    }
    String fullPrefix = PREFIX + suffixPrefix;
    for (Map.Entry<String, String> entry : host.sharedState().values().entrySet()) {
      if (entry.getKey() == null || !entry.getKey().startsWith(fullPrefix)) {
        continue;
      }
      try {
        byPlayer.put(UUID.fromString(entry.getKey().substring(fullPrefix.length())),
            Long.parseLong(entry.getValue().strip()));
      } catch (IllegalArgumentException | NullPointerException ignored) {
        // Not a counter we wrote; leave it alone rather than fail the whole read.
      }
    }
    return byPlayer;
  }
}
