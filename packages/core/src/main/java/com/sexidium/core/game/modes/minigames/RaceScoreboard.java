package com.sexidium.core.game.modes.minigames;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Per-group scoring state for {@link RaceGame}. A "group" is one entry per team in team play, else one per
 * player (the facade owns the key format — {@code "team:N"} or {@code "player:<uuid>"}). Tracks each
 * group's running points and the set of completed objective ids, and computes the untied score leader for
 * timeout resolution. Pure data logic with no platform dependency.
 */
final class RaceScoreboard {
  private final Map<String, Integer> scores = new HashMap<>();
  private final Map<String, Set<String>> completed = new HashMap<>();

  void clear() {
    scores.clear();
    completed.clear();
  }

  /** Ensures a group has a zeroed score row and an empty found-set. */
  void ensureGroup(String group) {
    scores.putIfAbsent(group, 0);
    completed.computeIfAbsent(group, ignored -> new HashSet<>());
  }

  int score(String group) {
    return scores.getOrDefault(group, 0);
  }

  Set<String> found(String group) {
    return completed.getOrDefault(group, Set.of());
  }

  /**
   * Marks an objective complete for a group, adding its points. Returns true if this is the first time the
   * group completed that objective (i.e. it counted), false if it was already credited.
   */
  boolean markCompleted(String group, String objectiveId, int points) {
    Set<String> set = completed.computeIfAbsent(group, ignored -> new HashSet<>());
    if (!set.add(objectiveId)) {
      return false;
    }
    scores.merge(group, points, Integer::sum);
    return true;
  }

  /** Returns the untied leader among the candidates (by group key), or null if tied/empty/non-positive. */
  <T> T untiedLeader(List<T> candidates, Function<T, String> groupKey) {
    T leader = null;
    int best = Integer.MIN_VALUE;
    boolean tied = false;
    for (T candidate : candidates) {
      int score = scores.getOrDefault(groupKey.apply(candidate), 0);
      if (score > best) {
        leader = candidate;
        best = score;
        tied = false;
      } else if (score == best) {
        tied = true;
      }
    }
    return leader == null || tied || best <= 0 ? null : leader;
  }
}
