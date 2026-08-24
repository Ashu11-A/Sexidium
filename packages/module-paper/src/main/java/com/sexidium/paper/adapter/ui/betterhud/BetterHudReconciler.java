package com.sexidium.paper.adapter.ui.betterhud;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps BetterHud showing exactly the surfaces the driver claimed, which for most players most of the
 * time is nothing at all.
 *
 * <h2>Why this is a repeating task and not a one-off</h2>
 * BetterHud applies its {@code default-hud} list — which ships as its demo health/hunger bars — every
 * time it builds a player's {@code HudPlayer}. That happens on join, on {@code /betterhud reload}, when
 * its resource pack is re-applied, and whenever the operator's own config changes. Reconciling once
 * would hold until the first of those, and then the lobby would quietly have a HUD in it again.
 *
 * <p>So: reconcile on the events known to rebuild a player, and again on a slow timer for everything
 * else. A reconcile is a map lookup plus a walk of one player's very short object list, and it only
 * does work when BetterHud disagrees with the ledger.</p>
 *
 * <h2>It runs while the driver is switched off too</h2>
 * With the driver disabled the reconcile becomes a one-way TEARDOWN: it can take Sexidium's own
 * surfaces off and can add nothing. That is not belt-and-braces — BetterHud persists worn objects per
 * player, so a server that ever ran with the driver on would otherwise keep re-applying our surfaces
 * on every join forever. See {@link BetterHudClaims#purgeOwned}.
 *
 * <h2>Names no BetterHud type</h2>
 * Everything typed is behind {@link BetterHudClaims}, so this is safe to register on a server without
 * the plugin, where every call is a cheap no-op.
 */
public final class BetterHudReconciler implements Listener {
  private final JavaPlugin plugin;
  private final BetterHudClaims claims;
  private final boolean teardownOnly;
  private final long periodTicks;

  BetterHudReconciler(JavaPlugin plugin, BetterHudClaims claims, long periodTicks, boolean teardownOnly) {
    this.plugin = plugin;
    this.claims = claims;
    this.teardownOnly = teardownOnly;
    this.periodTicks = Math.max(5L, periodTicks);
  }

  /** Starts the sweep. Registered as a listener separately, the same way the other Paper adapters are. */
  public void start() {
    plugin.getServer().getGlobalRegionScheduler()
        .runAtFixedRate(plugin, task -> sweep(), periodTicks, periodTicks);
  }

  /**
   * Reconciles every online player, each on their own region thread — Folia-safe, and a no-op
   * indirection on regular Paper. The player list is readable from the global region; the player is not.
   */
  private void sweep() {
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      player.getScheduler().run(plugin, task -> apply(player), null);
    }
  }

  /**
   * A joining player is wearing whatever BetterHud's defaults gave them. Reconciled a tick late,
   * because BetterHud builds its own {@code HudPlayer} from a join handler too and the one that runs
   * second wins — being second on purpose is cheaper than depending on listener order.
   */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    player.getScheduler().runDelayed(plugin, task -> apply(player), null, 1L);
  }

  /** A world change re-sends the player's boss bars, which is the surface BetterHud rides. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onWorldChange(PlayerChangedWorldEvent event) {
    apply(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onRespawn(PlayerRespawnEvent event) {
    Player player = event.getPlayer();
    player.getScheduler().runDelayed(plugin, task -> apply(player), null, 1L);
  }

  /** Nobody is going to release the claims of a player who dropped mid-match. Drop them here. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    claims.forget(event.getPlayer().getUniqueId());
  }

  private void apply(Player player) {
    if (teardownOnly) {
      claims.purgeOwned(player);
      return;
    }
    claims.reconcile(player);
  }
}
