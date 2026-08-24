package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.DuplicableKind;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * All of the Jump-Multiplies arithmetic, with no host attached: the eligibility set, the reach clamp,
 * the jump debounce, and the budget cascade that decides how many clones one jump is allowed to make.
 * Kept separate from {@link JumpMultipliesChallenge} for the same reason {@code LookMultiplierLadder} is
 * — an exponential mode's caps are exactly the part worth unit-testing, and they are untestable while
 * they are entangled with a world, a config and a clock.
 *
 * <p>The clock is <b>injected</b>. A cooldown written against {@code System.currentTimeMillis()} inline
 * can only be tested by sleeping, so in practice it is not tested at all.</p>
 */
final class JumpMultiplierRule {
  private final LongSupplier clock;
  // ConcurrentHashMap, not HashMap: on Folia each player's jump arrives on that player's own region
  // thread, so this map is genuinely touched from several threads at once.
  private final Map<UUID, Long> lastJumpMillis = new ConcurrentHashMap<>();

  JumpMultiplierRule() {
    this(System::currentTimeMillis);
  }

  JumpMultiplierRule(LongSupplier clock) {
    this.clock = clock == null ? System::currentTimeMillis : clock;
  }

  // ----- debounce -------------------------------------------------------------------------------

  /**
   * Whether this jump counts, consuming the debounce when it does. One physical jump can surface as more
   * than one platform signal (a jump out of water, a jump taken on the same tick a launch resolves), and
   * in an exponential mode a double-fire is not a rounding error — it is a squaring.
   *
   * <p>A cooldown of 0 accepts every jump, which is what an operator who turns the debounce off means.</p>
   */
  boolean acceptJump(UUID playerId, long cooldownMillis) {
    if (playerId == null) {
      return false;
    }
    long now = clock.getAsLong();
    if (cooldownMillis > 0) {
      Long last = lastJumpMillis.get(playerId);
      if (last != null && now - last < cooldownMillis) {
        return false;
      }
    }
    lastJumpMillis.put(playerId, now);
    return true;
  }

  /** Drops a player's debounce entry — called when they leave, so the map cannot grow without bound. */
  void forget(UUID playerId) {
    if (playerId != null) {
      lastJumpMillis.remove(playerId);
    }
  }

  /** How many players currently have a debounce entry (a debug readout). */
  int tracked() {
    return lastJumpMillis.size();
  }

  // ----- eligibility ----------------------------------------------------------------------------

  /**
   * The set of entity kinds the config switches add up to. Bosses are their own switch and default off
   * upstream: a second Ender Dragon is the single most destructive thing this mode can do, so it is
   * opted into rather than out of.
   */
  static Set<DuplicableKind> eligibleKinds(boolean mobs, boolean items, boolean projectiles,
      boolean tnt, boolean bosses) {
    Set<DuplicableKind> kinds = EnumSet.noneOf(DuplicableKind.class);
    if (mobs) {
      kinds.add(DuplicableKind.MOB);
    }
    if (items) {
      kinds.add(DuplicableKind.ITEM);
    }
    if (projectiles) {
      kinds.add(DuplicableKind.PROJECTILE);
    }
    if (tnt) {
      kinds.add(DuplicableKind.TNT);
    }
    if (bosses) {
      kinds.add(DuplicableKind.BOSS);
    }
    return kinds;
  }

  // ----- reach ----------------------------------------------------------------------------------

  /**
   * The sweep radius actually used: the configured reach, clamped down to what the player can see.
   *
   * <p>Both halves matter. Duplicating entities in chunks a player is not rendering is invisible work
   * that still costs the server, so the visible radius is a ceiling. But the visible radius alone is not
   * the answer either — a typical client reports something like 176 blocks, which is nothing like the
   * tight ring around the jumper the mode is supposed to be, so the configured radius is what normally
   * wins.</p>
   */
  static double reach(double configuredRadius, double visibleRadius) {
    double configured = Math.max(0.0, configuredRadius);
    if (visibleRadius <= 0.0) {
      return configured; // a platform that cannot report a view distance does not get to veto the reach
    }
    return Math.min(configured, visibleRadius);
  }

  // ----- the budget cascade ---------------------------------------------------------------------

  /** Whether the area is already at (or over) its live-entity ceiling. A ceiling of 0 means uncapped. */
  static boolean saturated(int liveEntities, int liveCeiling) {
    return liveCeiling > 0 && liveEntities >= liveCeiling;
  }

  /**
   * How many clones this jump may make, after every cap in turn:
   *
   * <ol>
   *   <li>what the rule <em>wants</em> — one copy of every eligible entity, {@code copiesPerEntity} times;</li>
   *   <li>the per-jump budget, so no single jump can spawn unboundedly in one tick;</li>
   *   <li>the headroom left under the live-entity ceiling, so a saturated area stops growing.</li>
   * </ol>
   *
   * <p>At the ceiling this returns 0 and the mode <b>refuses</b> rather than culling. There is no
   * oldest-clone culling anywhere in the repo to reuse, and building it would mean tagging every clone
   * with an identity that survives a chunk unload — so the honest degradation is to stop, and to say so
   * on the HUD, which reads as the mode working rather than as the mode being broken.</p>
   */
  static int clonesFor(int entityCount, int copiesPerEntity, int perJumpBudget,
      int liveEntities, int liveCeiling) {
    if (entityCount <= 0 || copiesPerEntity <= 0) {
      return 0;
    }
    if (saturated(liveEntities, liveCeiling)) {
      return 0;
    }
    // long, because entityCount * copiesPerEntity is precisely the product an exponential mode overflows.
    long wanted = (long) entityCount * copiesPerEntity;
    if (perJumpBudget > 0) {
      wanted = Math.min(wanted, perJumpBudget);
    }
    if (liveCeiling > 0) {
      wanted = Math.min(wanted, (long) liveCeiling - liveEntities);
    }
    return (int) Math.max(0L, Math.min(wanted, Integer.MAX_VALUE));
  }
}
