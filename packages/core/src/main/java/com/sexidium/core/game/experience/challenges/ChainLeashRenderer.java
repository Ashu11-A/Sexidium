package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Renders the Chained-Together rope as a real Minecraft lead instead of the particle line.
 *
 * <p>For each consecutive player pair {@code (holder, target)} in join order, one invisible leashed
 * marker entity is held by {@code holder} and teleported onto {@code target} every tick, so a native
 * lead spans the two players — a thin rope that does not block the view the way the per-tick particle
 * line does. The marker entities are platform objects (spawn/leash/teleport via {@link WorldAdapter}),
 * but the per-link bookkeeping lives here so it stays testable and the adapter stays stateless.
 *
 * <p>State is one marker id per <em>holder</em> (each player holds at most the rope to the next player).
 * Each render diff-syncs: spawn a missing link, drive an existing one onto its target, and drop the
 * marker of any holder that is no longer roped (left, or became the tail).
 */
final class ChainLeashRenderer {
  // Height (blocks) above the target's feet the marker sits at, so the lead reads near waist height.
  private static final double ROPE_HEIGHT = 0.6;

  private final Supplier<WorldAdapter> world;
  private final Supplier<Boolean> visibleRope;
  // holder player id -> the marker entity carrying that holder's lead to the next player.
  private final Map<UUID, UUID> markers = new HashMap<>();

  ChainLeashRenderer(Supplier<WorldAdapter> world, Supplier<Boolean> visibleRope) {
    this.world = world;
    this.visibleRope = visibleRope;
  }

  void render(List<PlayerAdapter> group) {
    WorldAdapter world = this.world.get();
    if (world == null) {
      return;
    }
    if (!visibleRope.get() || group.size() < 2) {
      clear();
      return;
    }
    Set<UUID> wanted = new HashSet<>();
    for (int i = 0; i < group.size() - 1; i++) {
      PlayerAdapter holder = group.get(i);
      PlayerAdapter target = group.get(i + 1);
      if (holder.position() == null || target.position() == null) {
        continue;
      }
      UUID holderId = holder.uniqueId();
      wanted.add(holderId);
      WorldPosition at = ropePoint(target.position());
      UUID marker = markers.get(holderId);
      if (marker == null) {
        UUID spawned = world.spawnRopeMarker(at, holderId);
        if (spawned != null) {
          markers.put(holderId, spawned);
        }
      } else {
        // Drive AND re-assert the leash: a holder's death/disconnect unleashes the marker, so re-leash it
        // (when broken) every tick instead of only on spawn — the rope self-heals onto the respawned player
        // with no dependence on a respawn event firing.
        world.driveRopeMarker(marker, holderId, at);
      }
    }
    // Drop markers for holders that are no longer roping anyone (the tail, or a player who left).
    List<UUID> stale = new ArrayList<>();
    for (UUID holderId : markers.keySet()) {
      if (!wanted.contains(holderId)) {
        stale.add(holderId);
      }
    }
    for (UUID holderId : stale) {
      world.removeRopeMarker(markers.remove(holderId));
    }
  }

  /** Removes every live marker — call on stop/teardown so no leashed entity is left in the world. */
  void clear() {
    WorldAdapter world = this.world.get();
    if (world != null) {
      for (UUID marker : markers.values()) {
        world.removeRopeMarker(marker);
      }
    }
    markers.clear();
  }

  private static WorldPosition ropePoint(WorldPosition target) {
    return new WorldPosition(target.worldName(), target.coordinateX(), target.coordinateY() + ROPE_HEIGHT,
        target.coordinateZ(), 0.0f, 0.0f);
  }
}
