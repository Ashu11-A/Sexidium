package com.sexidium.core.game.modes.minigames.race;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;

/**
 * One Race goal. Either an {@code ITEM} the player must collect (the classic mode) or a {@code STRUCTURE}
 * the player must physically reach — a structure that spawns into the temporary map; completion fires
 * when any (team)member enters its zone. A round mixes both, rolled by {@link RaceCatalog}.
 */
public final class RaceObjective {
  public enum Kind {
    ITEM,
    STRUCTURE
  }

  private final Kind kind;
  private final String tier;
  private final ItemKey itemKey;
  private final int amount;
  private final int points;
  private final String name;
  private final boolean explicitName;
  private final String structureId;
  private WorldPosition center;
  private int radius;

  private RaceObjective(Kind kind, String tier, ItemKey itemKey, int amount, int points, String name,
                        boolean explicitName, String structureId) {
    this.kind = kind;
    this.tier = tier;
    this.itemKey = itemKey;
    this.amount = amount;
    this.points = points;
    this.name = name;
    this.explicitName = explicitName;
    this.structureId = structureId;
  }

  public static RaceObjective item(String tier, ItemKey itemKey, int amount, int points, String name, boolean explicitName) {
    return new RaceObjective(Kind.ITEM, tier, itemKey, Math.max(1, amount), Math.max(1, points), name, explicitName, null);
  }

  public static RaceObjective structure(String tier, String structureId, int points, String name) {
    return new RaceObjective(Kind.STRUCTURE, tier, null, 1, Math.max(1, points), name, true, structureId);
  }

  public Kind kind() {
    return kind;
  }

  public boolean isItem() {
    return kind == Kind.ITEM;
  }

  public boolean isStructure() {
    return kind == Kind.STRUCTURE;
  }

  public String tier() {
    return tier;
  }

  public ItemKey itemKey() {
    return itemKey;
  }

  public int amount() {
    return amount;
  }

  public int points() {
    return points;
  }

  public String name() {
    return name;
  }

  public boolean explicitName() {
    return explicitName;
  }

  public String structureId() {
    return structureId;
  }

  public WorldPosition center() {
    return center;
  }

  public int radius() {
    return radius;
  }

  /** Sets the structure's spawned location and reach radius (runtime, once the arena world exists). */
  public void place(WorldPosition center, int radius) {
    this.center = center;
    this.radius = Math.max(1, radius);
  }

  /** Stable id used as the completion key and for de-duplication within a round. */
  public String id() {
    return isItem() ? "i:" + itemKey.qualifiedName() : "s:" + structureId;
  }
}
