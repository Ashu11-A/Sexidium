package com.sexidium.core.platform;

import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.UUID;

public interface MobHandle {
  String type();

  /**
   * The mob's stable unique id, so it can be tracked in memory across scans (the Total-Cleave
   * real-time tracker keys mobs by this). Null where the platform cannot report it.
   */
  default UUID id() {
    return null;
  }

  /** Whether the mob still exists and is alive (not dead, removed, or unloaded). Default true. */
  default boolean valid() {
    return true;
  }

  WorldPosition position();

  double health();

  void setHealth(double health);

  void setVelocity(double velocityX, double velocityY, double velocityZ);

  void equip(String slot, ItemStackData itemStackData);

  void addEffect(String effectKey, int amplifier, int durationTicks);

  void setGlowing(boolean glowing);

  /**
   * Applies {@code amount} points of damage to this mob, attributed to a player where the platform
   * allows it (so vanilla death drops / XP behave as a player kill). Used by the Total-Cleave
   * challenge. Default no-op for platforms that cannot deal generic damage.
   */
  default void damage(double amount) {
  }

  /**
   * Spawns an as-exact-as-possible copy of this mob (same type, same current health) at its current
   * position. Used by the Mob-Duplication challenge. Default no-op where entity spawning is
   * unavailable.
   */
  default void duplicate() {
  }

  /**
   * The unique id of whatever this mob is currently attacking, or null when it is attacking nothing (or
   * the platform cannot say).
   *
   * <p>An id rather than a handle because the only question ever asked of it is "is this aimed at THAT
   * player" — answering with a {@link PlayerAdapter} would oblige every platform's mob to know how to
   * resolve players, which is not a mob's job.</p>
   */
  default UUID targetId() {
    return null;
  }

  /**
   * Makes this mob stop attacking whatever it was attacking. Default no-op, so a platform that cannot
   * express this simply never drops aggro rather than failing to build.
   */
  default void clearTarget() {
  }
}
