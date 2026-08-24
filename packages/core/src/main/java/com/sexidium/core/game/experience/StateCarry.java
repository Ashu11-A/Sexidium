package com.sexidium.core.game.experience;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Picks the shared-state entries that survive a world regeneration.
 *
 * <p>Split out of {@link ExperienceWorldReset} so the rule can be read and tested on its own: it is
 * pure map arithmetic, and it decides what a run remembers about itself — which is exactly the kind of
 * thing that should not only be verifiable by regenerating a world on a live server.</p>
 *
 * <h2>Two forms of allowlist entry</h2>
 * An <b>exact key</b> carries that one entry, and is what a challenge uses for its own named counters
 * ({@code deathresets.resets}). A <b>prefix</b> — an entry ending in {@code *} — carries every key
 * beneath it, and exists because some state is keyed by things that are not known when the allowlist is
 * written: per-player counters are keyed by UUID, so there is no finite set of exact keys to name.
 *
 * <p>The distinction is not "convenience" versus "precision". A prefix is still an allowlist: it names
 * a namespace whose whole point is that it describes the RUN rather than the world, so everything in it
 * is carried for the same reason. Nothing here can carry a key that no entry asked for, which is the
 * property the reset depends on — a map challenge guards its first build with an "already built" flag,
 * and carrying that into fresh terrain leaves everyone standing in empty space.</p>
 */
final class StateCarry {
  /** Marks an allowlist entry as a prefix rather than an exact key. */
  static final char WILDCARD = '*';

  private StateCarry() {
  }

  /**
   * The subset of {@code values} the allowlist asks for.
   *
   * @param values all of the experience's current shared state
   * @param keys   exact keys, and prefixes written with a trailing {@value #WILDCARD}
   * @return the carried entries, in allowlist order; never null, and empty when nothing matched
   */
  static Map<String, String> select(Map<String, String> values, Set<String> keys) {
    Map<String, String> kept = new LinkedHashMap<>();
    if (values == null || values.isEmpty() || keys == null || keys.isEmpty()) {
      return kept;
    }
    for (String key : keys) {
      if (key == null || key.isEmpty()) {
        continue;
      }
      if (key.charAt(key.length() - 1) == WILDCARD) {
        String prefix = key.substring(0, key.length() - 1);
        // A bare "*" would carry everything, which is the one thing this class exists to prevent.
        if (prefix.isEmpty()) {
          continue;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
          if (entry.getKey() != null && entry.getKey().startsWith(prefix) && entry.getValue() != null) {
            kept.put(entry.getKey(), entry.getValue());
          }
        }
        continue;
      }
      String value = values.get(key);
      if (value != null) {
        kept.put(key, value);
      }
    }
    return kept;
  }

  /**
   * The entries a challenge wrote while rebuilding into the replacement world.
   *
   * <p>These are NOT subject to the allowlist, and the distinction is the whole reason this method
   * exists. The allowlist answers "what does the run remember about the world it lost" — and its default
   * of dropping everything is right, because a marker describing terrain that no longer exists is worse
   * than no marker at all. But a challenge's {@code onWorldReset} runs on the NEW world and writes about
   * the NEW world: a SkyBlock mode that has just built its island there records that it is built, and a
   * layered mode records the frontier it seated. Filtering that through the old world's allowlist throws
   * it away, and the next time the experience starts, {@code onStart} sees no marker and builds the
   * island a second time — on top of whatever the players have made of the first one.</p>
   *
   * <p>Comparing against a snapshot rather than trusting the challenges to report is deliberate: it
   * needs no cooperation, so a challenge added later is covered without knowing this exists.</p>
   *
   * @param before every value as it stood immediately before the rebuild callbacks
   * @param after  every value immediately after them
   * @return the added and changed entries; never null
   */
  static Map<String, String> writtenDuringRebuild(Map<String, String> before, Map<String, String> after) {
    Map<String, String> written = new LinkedHashMap<>();
    if (after == null || after.isEmpty()) {
      return written;
    }
    for (Map.Entry<String, String> entry : after.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (key == null || value == null) {
        continue;
      }
      // Unchanged means it describes the OLD world and survives only if the allowlist says so. A value
      // that merely happens to be re-written identically is indistinguishable from one nobody touched,
      // and treating it as untouched is the safe half of that ambiguity.
      if (!value.equals(before == null ? null : before.get(key))) {
        written.put(key, value);
      }
    }
    return written;
  }
}
