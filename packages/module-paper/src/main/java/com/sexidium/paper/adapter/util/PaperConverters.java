package com.sexidium.paper.adapter.util;

import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class PaperConverters {
  private PaperConverters() {
  }

  public static WorldPosition toCore(Location location) {
    if (location == null || location.getWorld() == null) {
      return null;
    }
    return new WorldPosition(
        location.getWorld().getName(),
        location.getX(),
        location.getY(),
        location.getZ(),
        location.getYaw(),
        location.getPitch()
    );
  }

  public static Location toNative(WorldPosition position) {
    if (position == null) {
      return null;
    }
    World world = resolveWorld(position.worldName());
    if (world == null) {
      return null;
    }
    return new Location(
        world,
        position.coordinateX(),
        position.coordinateY(),
        position.coordinateZ(),
        position.yaw(),
        position.pitch()
    );
  }

  /**
   * Resolves a Bukkit world from a Sexidium world name in any form it might carry. A managed world is
   * keyed ({@code world/dimensions/<ns>/<key>}); depending on the platform build its {@code getName()}
   * may be a plain name, a {@code namespace:key} id, or a {@code namespace_key} "usable name", so a bare
   * {@code Bukkit.getWorld(name)} can miss. Tries the exact name, then a namespaced key, then a scan
   * across loaded worlds matching name / key id / usable name / last segment.
   */
  public static World resolveWorld(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    World world = Bukkit.getServer().getWorld(name);
    if (world != null) {
      return world;
    }
    if (name.indexOf(':') >= 0) {
      org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
      if (key != null) {
        world = Bukkit.getServer().getWorld(key);
        if (world != null) {
          return world;
        }
      }
    }
    if (name.indexOf('/') > 0 && name.indexOf(':') < 0) {
      String keyedName = name.substring(0, name.indexOf('/')) + ":" + name.substring(name.indexOf('/') + 1);
      org.bukkit.NamespacedKey key = org.bukkit.NamespacedKey.fromString(keyedName.toLowerCase(Locale.ROOT));
      if (key != null) {
        world = Bukkit.getServer().getWorld(key);
        if (world != null) {
          return world;
        }
      }
    }
    String lastSegment = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf(':')) + 1);
    for (World candidate : Bukkit.getServer().getWorlds()) {
      org.bukkit.NamespacedKey key = candidate.getKey();
      String slashKey = key == null ? null : key.getNamespace() + "/" + key.getKey();
      String usableKey = key == null ? null : (key.getNamespace() + "_" + key.getKey()).replace('/', '_');
      if (candidate.getName().equalsIgnoreCase(name)
          || (key != null && (
              (key.getNamespace() + ":" + key.getKey()).equalsIgnoreCase(name)
              || slashKey.equalsIgnoreCase(name)
              || usableKey.equalsIgnoreCase(name)
              || key.getKey().equalsIgnoreCase(lastSegment)))) {
        return candidate;
      }
    }
    return null;
  }

  public static GameModeType toCore(GameMode gameMode) {
    if (gameMode == null) {
      return GameModeType.SURVIVAL;
    }
    return switch (gameMode) {
      case CREATIVE -> GameModeType.CREATIVE;
      case ADVENTURE -> GameModeType.ADVENTURE;
      case SPECTATOR -> GameModeType.SPECTATOR;
      default -> GameModeType.SURVIVAL;
    };
  }

  public static GameMode toNative(GameModeType gameModeType) {
    return switch (gameModeType == null ? GameModeType.SURVIVAL : gameModeType) {
      case CREATIVE -> GameMode.CREATIVE;
      case ADVENTURE -> GameMode.ADVENTURE;
      case SPECTATOR -> GameMode.SPECTATOR;
      default -> GameMode.SURVIVAL;
    };
  }

  public static ItemKey toCore(Material material) {
    if (material == null) {
      return ItemKey.minecraft("air");
    }
    return ItemKey.minecraft(material.name().toLowerCase(Locale.ROOT));
  }

  public static Material toNative(ItemKey itemKey) {
    if (itemKey == null) {
      return Material.AIR;
    }
    Material material = Material.matchMaterial(itemKey.value().toUpperCase(Locale.ROOT));
    return material == null ? Material.AIR : material;
  }

  public static BossBar.Color toNative(BossBarColor color) {
    return switch (color == null ? BossBarColor.WHITE : color) {
      case PINK -> BossBar.Color.PINK;
      case BLUE -> BossBar.Color.BLUE;
      case RED -> BossBar.Color.RED;
      case GREEN -> BossBar.Color.GREEN;
      case YELLOW -> BossBar.Color.YELLOW;
      case PURPLE -> BossBar.Color.PURPLE;
      case WHITE -> BossBar.Color.WHITE;
    };
  }

  public static BossBar.Overlay toNative(BossBarOverlay overlay) {
    return switch (overlay == null ? BossBarOverlay.PROGRESS : overlay) {
      case NOTCHED_6 -> BossBar.Overlay.NOTCHED_6;
      case NOTCHED_10 -> BossBar.Overlay.NOTCHED_10;
      case NOTCHED_12 -> BossBar.Overlay.NOTCHED_12;
      case NOTCHED_20 -> BossBar.Overlay.NOTCHED_20;
      case PROGRESS -> BossBar.Overlay.PROGRESS;
    };
  }

  public static boolean isPlayerOnline(Player player) {
    return player != null && player.isOnline();
  }
}
