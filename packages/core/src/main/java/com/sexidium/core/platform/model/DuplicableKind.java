package com.sexidium.core.platform.model;

/**
 * Which classes of entity a bulk-duplication seam is allowed to copy — the vocabulary
 * {@link com.sexidium.core.platform.PlayerAdapter#duplicateNearbyEntities} and
 * {@link com.sexidium.core.platform.WorldAdapter#countNearbyEntities} share, so a mode's config
 * switches ("multiply mobs? items? TNT?") travel to the platform as a set rather than as an
 * ever-growing row of booleans.
 *
 * <p>Players are not a kind and never can be: nothing about a player is duplicable, and the omission is
 * the enum saying so rather than a flag someone could set.</p>
 */
public enum DuplicableKind {
  /** Any living non-player entity — passive, hostile, tamed, baby. */
  MOB,
  /** A dropped item ENTITY. One stack of 64 is one entity, so a copy yields one more entity, not 64 items. */
  ITEM,
  /** Anything in flight: arrows, thrown pearls, potions, fireballs. */
  PROJECTILE,
  /** PRIMED TNT — the entity. A TNT block sitting on the ground is a block and is never in scope. */
  TNT,
  /**
   * The Ender Dragon and the Wither. Deliberately separate from {@link #MOB} so cloning a boss is an
   * explicit opt-in: it is the single most destructive thing these seams can be asked to do, and a mode
   * that wants "everything nearby" almost never means "and a second dragon" by default.
   */
  BOSS
}
