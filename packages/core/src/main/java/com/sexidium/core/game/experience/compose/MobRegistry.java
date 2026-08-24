package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.experience.ExperienceHost;
import com.sexidium.core.platform.MobHandle;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-owned coordination for the mob challenges (Cleave, Mob Duplication).
 * Provides:
 * <ul>
 *   <li>a per-tick {@code nearbyMobs} scan cache so the scanners pay the spatial query once;</li>
 *   <li>a per-tick spawn budget that caps Mob Duplication's snowball;</li>
 *   <li>a "cleave active" guard so Cleave's programmatic splash damage cannot recursively duplicate
 *       the whole horde.</li>
 * </ul>
 * {@link #tick()} is driven once per game tick by the host to reset the window.
 */
public final class MobRegistry {
  private final ExperienceHost host;
  private final Map<String, List<MobHandle>> scanCache = new HashMap<>();
  private int spawnBudgetPerTick = 16;
  private int spawnsThisTick;
  private int cleaveDepth;

  public MobRegistry(ExperienceHost host) {
    this.host = host;
  }

  /** Resets the per-tick window (scan cache + spawn budget). */
  public void tick() {
    scanCache.clear();
    spawnsThisTick = 0;
  }

  // ----- spawn budget --------------------------------------------------------------------------

  /** 0 disables the cap; otherwise at most this many duplications are allowed per tick. */
  public void spawnBudgetPerTick(int budget) {
    this.spawnBudgetPerTick = Math.max(0, budget);
  }

  /**
   * The budget currently in force (0 = uncapped). The budget is SHARED — Mob Duplication, Cleave and
   * Jump Multiplies all set it — so a challenge that composes with the others reads this first and sets
   * the minimum of it and its own, instead of clobbering a tighter cap a sibling asked for.
   */
  public int spawnBudgetPerTick() {
    return spawnBudgetPerTick;
  }

  /** Reserves one spawn from this tick's budget; false when the budget is exhausted. */
  public boolean tryRegisterSpawn() {
    if (spawnBudgetPerTick > 0 && spawnsThisTick >= spawnBudgetPerTick) {
      return false;
    }
    spawnsThisTick++;
    return true;
  }

  // ----- cleave guard --------------------------------------------------------------------------

  public void beginCleave() {
    cleaveDepth++;
  }

  public void endCleave() {
    if (cleaveDepth > 0) {
      cleaveDepth--;
    }
  }

  /** True while Cleave is dealing its programmatic splash damage. */
  public boolean cleaveActive() {
    return cleaveDepth > 0;
  }

  // ----- shared scan cache ---------------------------------------------------------------------

  /** A {@code nearbyMobs} query, cached for the current tick so repeat callers reuse the result. */
  public List<MobHandle> scan(WorldPosition center, double radius, boolean includePassive) {
    WorldAdapter world = host == null ? null : host.world();
    if (world == null || center == null) {
      return List.of();
    }
    String key = (long) center.coordinateX() + ":" + (long) center.coordinateY() + ":"
        + (long) center.coordinateZ() + ":" + (long) radius + ":" + includePassive;
    List<MobHandle> cached = scanCache.get(key);
    if (cached != null) {
      return cached;
    }
    List<MobHandle> result = world.nearbyMobs(center, radius, includePassive);
    scanCache.put(key, result);
    return result;
  }
}
