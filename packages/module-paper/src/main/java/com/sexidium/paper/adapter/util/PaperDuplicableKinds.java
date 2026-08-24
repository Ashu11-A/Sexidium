package com.sexidium.paper.adapter.util;

import com.sexidium.core.platform.model.DuplicableKind;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Shared plumbing for the bulk-duplication seams ({@code PlayerAdapter#duplicateNearbyEntities},
 * {@code WorldAdapter#countNearbyEntities}) — the Bukkit-entity → {@link DuplicableKind} classification
 * and the loaded-chunk spatial walk, in one place so the cloner and the population counter can never
 * disagree about which entities are in scope or where "nearby" ends.
 */
public final class PaperDuplicableKinds {
  private PaperDuplicableKinds() {
  }

  /**
   * Which kind an entity belongs to, or null when it is not duplicable at all (a player, an armour
   * stand, a hanging painting, a rope marker…). Bosses are classified BOSS rather than MOB, so cloning
   * one stays an explicit opt-in.
   */
  public static DuplicableKind kindOf(Entity entity) {
    if (entity == null || entity instanceof Player || !entity.isValid()) {
      return null;
    }
    if (entity instanceof org.bukkit.entity.EnderDragon || entity instanceof org.bukkit.entity.Wither) {
      return DuplicableKind.BOSS;
    }
    if (entity instanceof org.bukkit.entity.Item) {
      return DuplicableKind.ITEM;
    }
    if (entity instanceof org.bukkit.entity.TNTPrimed) {
      return DuplicableKind.TNT;
    }
    if (entity instanceof org.bukkit.entity.Projectile) {
      return DuplicableKind.PROJECTILE;
    }
    if (entity instanceof org.bukkit.entity.LivingEntity) {
      return DuplicableKind.MOB;
    }
    return null;
  }

  /** Whether {@code entity} is one of the kinds the caller asked for. */
  public static boolean eligible(Entity entity, Set<DuplicableKind> kinds) {
    if (kinds == null || kinds.isEmpty()) {
      return false;
    }
    DuplicableKind kind = kindOf(entity);
    return kind != null && kinds.contains(kind);
  }

  /**
   * Visits every eligible entity within {@code radius} of {@code center}, walking only ALREADY-LOADED
   * chunks.
   *
   * <p>Deliberately not {@code World#getNearbyEntities}: that can synchronously load chunks — a
   * server-wide lag spike that hits mobile clients hardest — or throw outright when the search box
   * reaches an unloaded or foreign chunk, and the callers here run on a player's own region thread with
   * a radius a config file chooses. Skipping unloaded chunks keeps the scan cheap and safe at any
   * radius, and matches the walk {@code PaperWorldAdapter#nearbyItems} already uses.</p>
   *
   * <p>The distance test is a real SPHERE ({@code distanceSquared <= r²}), not the chunk cube the walk
   * iterates, so "within 12 blocks" means the same thing in every direction.</p>
   */
  public static void forEachNearby(Location center, double radius, Set<DuplicableKind> kinds,
      Consumer<Entity> visitor) {
    if (center == null || center.getWorld() == null || kinds == null || kinds.isEmpty()) {
      return;
    }
    World world = center.getWorld();
    double safeRadius = Math.max(0.0, radius);
    double radiusSquared = safeRadius * safeRadius;
    int minChunkX = (int) Math.floor((center.getX() - safeRadius) / 16.0);
    int maxChunkX = (int) Math.floor((center.getX() + safeRadius) / 16.0);
    int minChunkZ = (int) Math.floor((center.getZ() - safeRadius) / 16.0);
    int maxChunkZ = (int) Math.floor((center.getZ() + safeRadius) / 16.0);
    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
          continue;
        }
        Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        for (Entity entity : chunk.getEntities()) {
          if (eligible(entity, kinds) && entity.getLocation().distanceSquared(center) <= radiusSquared) {
            visitor.accept(entity);
          }
        }
      }
    }
  }

  /**
   * The same walk, collected into a SNAPSHOT array.
   *
   * <p>The array is the point, not a convenience. A duplication sweep adds entities to the very chunks
   * it is iterating, so streaming the walk straight into a cloner would clone the clones, then clone
   * those — an unbounded self-amplifying loop, in the one mode whose whole premise is exponential
   * growth. Freezing the source list first makes "every entity that was here when you jumped" mean
   * exactly that.</p>
   */
  public static Entity[] snapshotNearby(Location center, double radius, Set<DuplicableKind> kinds) {
    List<Entity> found = new ArrayList<>();
    forEachNearby(center, radius, kinds, found::add);
    return found.toArray(new Entity[0]);
  }
}
