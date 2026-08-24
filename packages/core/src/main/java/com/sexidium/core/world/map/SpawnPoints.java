package com.sexidium.core.world.map;

import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * An ordered, mutable list of spawn points for one world/map — the unified "player spawn locations" model
 * shared by the lobby ({@code sexidium-lobby.yml}) and the Combat arena maps ({@code sexidium-combat.yml}).
 * Edited a point at a time by the {@code /lobby} and {@code /sx admin map combat} admin commands and persisted by
 * {@link SpawnPointStore}. Point {@code 1} is "the" spawn (e.g. the lobby's pinned world spawn); the rest
 * let joiners be spread across an arena or several lobby pads. {@link #isReady()} gates whether a map can
 * place players from its own configured spawns instead of falling back to a procedurally generated layout.
 */
public final class SpawnPoints {
  private String world;
  private final List<WorldPosition> spawns = new ArrayList<>();

  public SpawnPoints(String world) {
    this.world = world == null ? "" : world;
  }

  public String world() {
    return world;
  }

  public void setWorld(String world) {
    this.world = world == null ? "" : world;
  }

  /** Appends a spawn point (stamped with this map's world name). */
  public void add(WorldPosition position) {
    if (position != null) {
      spawns.add(position.withWorldName(world));
    }
  }

  /** Replaces all spawn points with a single one — used by {@code setspawn} to pin "the" spawn. */
  public void setPrimary(WorldPosition position) {
    spawns.clear();
    add(position);
  }

  public void clear() {
    spawns.clear();
  }

  public int size() {
    return spawns.size();
  }

  /** True once at least one spawn point is configured, so the map can place players itself. */
  public boolean isReady() {
    return !spawns.isEmpty();
  }

  /** An immutable snapshot of the spawn points, each carrying this map's world name. */
  public List<WorldPosition> all() {
    return List.copyOf(spawns);
  }

  /** The primary spawn (point 1), or null when none are configured. */
  public WorldPosition primary() {
    return spawns.isEmpty() ? null : spawns.get(0);
  }

  /** A random spawn point, or null when none are configured. */
  public WorldPosition random() {
    if (spawns.isEmpty()) {
      return null;
    }
    return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
  }

  /** The spawn point assigned to the {@code index}-th player, wrapping around the configured points. */
  public WorldPosition forIndex(int index) {
    if (spawns.isEmpty()) {
      return null;
    }
    return spawns.get(Math.floorMod(index, spawns.size()));
  }
}
