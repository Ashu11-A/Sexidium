package com.sexidium.paper.adapter.world;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Redirects nether/End portal travel inside an experience world to that experience's OWN linked
 * dimensions (created eagerly by {@link PaperWorldControl#experienceDimension}) instead of the server
 * default Nether/End. Only experience worlds are touched — the lobby, temp/minigame and server worlds
 * keep vanilla behaviour. Server-default → experience never happens because the portals are inside the
 * experience overworld.
 *
 * <ul>
 *   <li>experience overworld → nether portal → experience nether (coords ÷8, vanilla builds the link);</li>
 *   <li>experience nether → nether portal → experience overworld (coords ×8);</li>
 *   <li>experience overworld → (vanilla seed) End portal → experience End (entry platform at 100,50,0);</li>
 *   <li>experience End → exit portal → experience overworld spawn.</li>
 * </ul>
 */
public final class PaperPortalListener implements Listener {
  private final PaperWorldControl control;
  private final SexidiumCore core;

  public PaperPortalListener(PaperWorldControl control, SexidiumCore core) {
    this.control = control;
    this.core = core;
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onPlayerPortal(PlayerPortalEvent event) {
    Location from = event.getFrom();
    World fromWorld = from == null ? null : from.getWorld();
    if (fromWorld == null) {
      return;
    }
    PlayerTeleportEvent.TeleportCause cause = event.getCause();
    // Lobby End portal: send the player to their (or a friend's) most recently created experience.
    if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL && isLobby(fromWorld)) {
      event.setCancelled(true);
      returnToLatestExperience(event);
      return;
    }
    if (!control.isExperienceWorld(fromWorld)) {
      return;
    }
    Location destination = destinationFor(fromWorld, from, cause);
    if (destination == null) {
      return;
    }
    event.setTo(destination);
    if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
      // Let vanilla find/create the linked portal in the sibling near the scaled destination.
      event.setSearchRadius(128);
      try {
        event.setCanCreatePortal(true);
      } catch (Throwable ignored) {
        // older API without the setter — destination override is still honoured
      }
    } else {
      event.setSearchRadius(0);
      try {
        event.setCanCreatePortal(false);
      } catch (Throwable ignored) {
        // ignore
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
  public void onEntityPortal(EntityPortalEvent event) {
    Location from = event.getFrom();
    World fromWorld = from == null ? null : from.getWorld();
    if (fromWorld == null || !control.isExperienceWorld(fromWorld)) {
      return;
    }
    Location to = event.getTo();
    if (to == null || to.getWorld() == null) {
      return;
    }
    // The destination world's environment is what vanilla intended; map it onto the experience sibling.
    World target = control.experienceDimension(fromWorld, to.getWorld().getEnvironment());
    if (target == null) {
      // Sibling unavailable: drop the entity rather than leak it into a server-default dimension.
      event.setCancelled(true);
      return;
    }
    if (target.equals(to.getWorld())) {
      return;
    }
    event.setTo(new Location(target, to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch()));
  }

  private Location destinationFor(World fromWorld, Location from, PlayerTeleportEvent.TeleportCause cause) {
    World.Environment env = fromWorld.getEnvironment();
    if (cause == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
      if (env == World.Environment.NORMAL) {
        World nether = control.experienceDimension(fromWorld, World.Environment.NETHER);
        return nether == null ? null : scaled(from, nether, 0.125);
      }
      if (env == World.Environment.NETHER) {
        World overworld = control.experienceDimension(fromWorld, World.Environment.NORMAL);
        return overworld == null ? null : scaled(from, overworld, 8.0);
      }
      return null;
    }
    if (cause == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
      if (env == World.Environment.NORMAL) {
        World end = control.experienceDimension(fromWorld, World.Environment.THE_END);
        return end == null ? null : new Location(end, 100.5, 50.0, 0.5);
      }
      if (env == World.Environment.THE_END) {
        World overworld = control.experienceDimension(fromWorld, World.Environment.NORMAL);
        return overworld == null ? null : overworld.getSpawnLocation();
      }
      return null;
    }
    return null;
  }

  private boolean isLobby(World world) {
    if (world == null) {
      return false;
    }
    String name = world.getKey() != null ? world.getKey().getKey() : world.getName();
    return control.naming().isLobby(name);
  }

  /** Routes a lobby End-portal user to their/their friends' latest experience (next tick, off the portal). */
  private void returnToLatestExperience(PlayerPortalEvent event) {
    PaperPlayerAdapter adapter = new PaperPlayerAdapter(event.getPlayer());
    core.gameContext().server().scheduler().runLater(() -> {
      ExperienceService.EnterOutcome outcome = core.experienceService().enterLatest(adapter);
      switch (outcome) {
        case ENTERED, STARTED -> adapter.sendActionBar("<green>Returning to your latest experience…</green>");
        case NOT_FOUND -> {
          adapter.sendActionBar("<yellow>No recent experience yet — create one with /sx experience.</yellow>");
          sendToLobbySpawn(adapter);
        }
        default -> {
          adapter.sendActionBar("<red>Could not open your latest experience.</red>");
          sendToLobbySpawn(adapter);
        }
      }
    }, 1L);
  }

  private void sendToLobbySpawn(PaperPlayerAdapter adapter) {
    java.util.Optional<WorldPosition> spawn = core.gameContext().server().worlds().lobbySpawn();
    spawn.ifPresent(adapter::teleport);
  }

  /** Scales the horizontal coordinates by {@code factor} into {@code target}, clamping Y into range. */
  private static Location scaled(Location from, World target, double factor) {
    double y = Math.max(target.getMinHeight() + 4, Math.min(from.getY(), target.getMaxHeight() - 8));
    return new Location(target, from.getX() * factor, y, from.getZ() * factor, from.getYaw(), from.getPitch());
  }
}
