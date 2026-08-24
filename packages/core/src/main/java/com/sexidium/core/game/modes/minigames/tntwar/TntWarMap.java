package com.sexidium.core.game.modes.minigames.tntwar;

import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;

/**
 * The persisted layout of one TNT-War map: which template world holds it and where each team's base
 * and spawn are. Edited a corner/spawn at a time by the {@code /sx admin map tntwar} admin commands (hence the
 * mutable, nullable fields) and serialised to {@code sexidium-tntwar.yml} inside the map's world folder
 * by {@link TntWarMapStore}. A base is only "ready" once both of its corners are set; {@link #isReady()}
 * gates whether the map can host a destruction-based match.
 */
public final class TntWarMap {
  private final String id;
  private String world;
  private BlockPosition redCorner1;
  private BlockPosition redCorner2;
  private BlockPosition blueCorner1;
  private BlockPosition blueCorner2;
  private WorldPosition redSpawn;
  private WorldPosition blueSpawn;

  public TntWarMap(String id, String world) {
    this.id = id;
    this.world = world == null ? "" : world;
  }

  public String id() {
    return id;
  }

  public String world() {
    return world;
  }

  public void setWorld(String world) {
    this.world = world == null ? "" : world;
  }

  public BlockPosition redCorner1() {
    return redCorner1;
  }

  public BlockPosition redCorner2() {
    return redCorner2;
  }

  public BlockPosition blueCorner1() {
    return blueCorner1;
  }

  public BlockPosition blueCorner2() {
    return blueCorner2;
  }

  public WorldPosition redSpawn() {
    return redSpawn;
  }

  public WorldPosition blueSpawn() {
    return blueSpawn;
  }

  public void setCorner(String team, int corner, BlockPosition position) {
    boolean red = isRed(team);
    if (corner <= 1) {
      if (red) {
        redCorner1 = position;
      } else {
        blueCorner1 = position;
      }
    } else {
      if (red) {
        redCorner2 = position;
      } else {
        blueCorner2 = position;
      }
    }
  }

  public void setSpawn(String team, WorldPosition position) {
    if (isRed(team)) {
      redSpawn = position;
    } else {
      blueSpawn = position;
    }
  }

  /** The red team's base box, or null until both corners are set. */
  public BaseRegion redBase() {
    return redCorner1 == null || redCorner2 == null ? null : BaseRegion.between(redCorner1, redCorner2);
  }

  /** The blue team's base box, or null until both corners are set. */
  public BaseRegion blueBase() {
    return blueCorner1 == null || blueCorner2 == null ? null : BaseRegion.between(blueCorner1, blueCorner2);
  }

  /** True once both teams have a full base box and a spawn, so the map can host a match. */
  public boolean isReady() {
    return redBase() != null && blueBase() != null && redSpawn != null && blueSpawn != null;
  }

  private static boolean isRed(String team) {
    return team != null && team.trim().equalsIgnoreCase("red");
  }
}
