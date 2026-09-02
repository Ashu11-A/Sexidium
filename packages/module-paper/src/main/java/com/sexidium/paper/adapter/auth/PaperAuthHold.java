package com.sexidium.paper.adapter.auth;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.auth.AuthHoldPolicy;
import com.sexidium.core.auth.AuthHoldService;
import com.sexidium.core.auth.AuthMessages;
import com.sexidium.core.auth.AuthSessionService;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.ConfigurationAdapter;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Freezes a player in-world until their Discord approval lands, and lets them go the instant it does.
 *
 * <p>This is what removes the reconnect: instead of "approve, then press Reconnect", the player is
 * simply here, told what to do, and released in place. It is also the only part of the auth design
 * that lets an UNVERIFIED connection reach a world, which is why the freeze is total — a held player
 * is a hidden, invulnerable spectator who cannot move, chat, run a command, touch a block, hit or be
 * hit, drop, pick up, click an inventory or change world — and why it is kicked at a deadline.</p>
 *
 * <p>The release arrives three ways, deliberately: directly from {@code BridgeRouter} when the
 * decision is made on this node, over {@link com.sexidium.core.network.NetworkBus} when it is not,
 * and — because neither of those is a guarantee — from this class's own one-second re-read of the
 * request row. A dropped message must never strand somebody frozen.</p>
 */
public final class PaperAuthHold implements Listener {

  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  /** How often the countdown is re-rendered and the request row re-read. */
  private static final long TICK_PERIOD = 20L;

  private final JavaPlugin plugin;
  private final SexidiumCore core;
  private final AuthHoldPolicy policy;
  private final AuthHoldService holds;

  private ScheduledTask task;

  public PaperAuthHold(JavaPlugin plugin, SexidiumCore core, ConfigurationAdapter configuration) {
    this.plugin = plugin;
    this.core = core;
    this.policy = new AuthHoldPolicy(configuration);
    this.holds = core.authHold();
  }

  /** Start the countdown renderer and install the release hook core will call on a decision. */
  public void start() {
    core.setHoldRelease(this::releaseByIdentity);
    // GlobalRegionScheduler, not getScheduler(): plugin.yml declares folia-supported, which is only
    // true while every timer rides a region/entity/async scheduler — the legacy one throws
    // UnsupportedOperationException on Folia and would kill the countdown at startup.
    //
    // The timer itself belongs on the global region: it reads database rows and iterates the hold
    // table, which is owned by nobody in particular. What it must NOT do from there is touch a
    // PLAYER — setGameMode, kick, showPlayer and friends belong to the region that owns that entity,
    // and Folia throws when another thread reaches in. So every player-facing step below is handed to
    // that player's own entity scheduler via onPlayerThread(); see release() and tick().
    this.task = plugin.getServer().getGlobalRegionScheduler()
        .runAtFixedRate(plugin, ignored -> tick(), TICK_PERIOD, TICK_PERIOD);
  }

  public void stop() {
    if (task != null) {
      task.cancel();
      task = null;
    }
  }

  // --- lifecycle ----------------------------------------------------------

  /**
   * Apply the hold as early as possible.
   *
   * <p>{@code LOWEST} so the freeze is in place before anything else reacts to the join — the lobby
   * guard's hotbar, the HUD and the rank tag all run later and would otherwise dress up a player who
   * is not allowed to be here yet.</p>
   */
  @EventHandler(priority = EventPriority.LOWEST)
  public void onJoin(PlayerJoinEvent event) {
    AuthSessionService sessions = core.authSessions();
    if (sessions == null || !policy.enabled()) {
      return;
    }
    Player player = event.getPlayer();
    // The in-memory intent THIS gate recorded at pre-login is the load-bearing signal, not the
    // database: it is keyed by the player uuid the platform hands both events, so it agrees on a
    // proxied backend (canonical uuid) and on a proxy-less server (offline uuid) alike — which a
    // lookup of the identity by player uuid cannot, because a standalone Paper derives that uuid
    // case-sensitively while the identity is anchored on the lowercased name. And because it holds
    // no database, a transient SQL failure cannot turn a held player loose: the fail-closed answer.
    Optional<AuthSessionService.HoldIntent> intent = sessions.holdIntent(player.getUniqueId());
    if (intent.isEmpty()) {
      return;
    }
    apply(player, intent.get());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    holds.release(event.getPlayer().getUniqueId());
    AuthSessionService sessions = core.authSessions();
    if (sessions != null) {
      sessions.forgetHold(event.getPlayer().getUniqueId());
    }
  }

  private void apply(Player player, AuthSessionService.HoldIntent intent) {
    GameMode previous = player.getGameMode();
    holds.hold(player.getUniqueId(), intent.identityId(), intent.requestId(), previous.name(),
        Math.min(intent.expiresAt(), System.currentTimeMillis() + policy.timeoutMillis()));

    if (policy.spectator()) {
      player.setGameMode(GameMode.SPECTATOR);
    }
    player.setInvulnerable(true);
    player.getInventory().clear();
    for (PotionEffect effect : player.getActivePotionEffects()) {
      player.removePotionEffect(effect.getType());
    }
    if (policy.hideFromOthers()) {
      // Both directions: an unverified connection must be socially inert, not merely mechanically so.
      for (Player other : plugin.getServer().getOnlinePlayers()) {
        if (!other.equals(player)) {
          other.hidePlayer(plugin, player);
          player.hidePlayer(plugin, other);
        }
      }
    }
    // Rendered bilingually: the player has only just connected, so their client locale has not been
    // negotiated into anything we can trust yet.
    player.showTitle(Title.title(
        MINI_MESSAGE.deserialize(inline(MessageKey.AUTH_HOLD_TITLE)),
        MINI_MESSAGE.deserialize(inline(MessageKey.AUTH_HOLD_SUBTITLE)),
        Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ZERO)));
  }

  /**
   * Let a held player go, or kick them on a Deny.
   *
   * <p>Called with an identity rather than a player uuid because the deciding node knows only the
   * identity — which, thanks to the canonical-uuid rewrite at the proxy, is the player uuid here.</p>
   */
  private void releaseByIdentity(String identityId, boolean approved) {
    holds.byIdentity(identityId).ifPresent(hold -> release(hold.playerId(), approved));
  }

  private void release(UUID playerId, boolean approved) {
    Optional<AuthHoldService.Hold> held = holds.release(playerId);
    AuthSessionService sessions = core.authSessions();
    if (sessions != null) {
      sessions.forgetHold(playerId);
    }
    Player player = plugin.getServer().getPlayer(playerId);
    if (player == null || !player.isOnline()) {
      return;
    }
    if (approved && sessions != null) {
      // Stays on the calling thread rather than moving into the entity-scheduler task below: it is a
      // database write, and a region thread is the last place to put one. Unchanged in every other
      // respect — still only on an approval, still only for a player who is actually here.
      held.ifPresent(hold -> sessions.consumeRequest(hold.requestId()));
    }
    // Everything from here mutates the player, so it runs on the region that owns them — never on
    // whichever thread happened to decide the release (the global-region timer below, the network bus,
    // or a command). The bookkeeping above already ran: a player who logs off in between simply loses
    // the cosmetics, which is the same outcome as the isOnline() check.
    long sessionHours = sessions == null ? 18 : sessions.sessionHours();
    onPlayerThread(player, () -> {
      if (!approved) {
        player.kick(MINI_MESSAGE.deserialize(
            AuthMessages.bilingual(core.messages(), MessageKey.AUTH_SESSION_DENIED,
                MessageArg.text("hours", 24))));
        return;
      }
      held.ifPresent(hold -> {
        if (policy.spectator()) {
          player.setGameMode(gameModeOf(hold.previousGameMode()));
        }
      });
      player.setInvulnerable(false);
      if (policy.hideFromOthers()) {
        for (Player other : plugin.getServer().getOnlinePlayers()) {
          if (!other.equals(player)) {
            other.showPlayer(plugin, player);
            player.showPlayer(plugin, other);
          }
        }
      }
      player.clearTitle();
      player.sendActionBar(Component.empty());
      player.sendMessage(MINI_MESSAGE.deserialize(inline(MessageKey.AUTH_HOLD_APPROVED,
          MessageArg.text("hours", sessionHours))));
    });
  }

  /**
   * Runs {@code action} on the region that owns {@code player}.
   *
   * <p>The entity scheduler follows a player across regions and simply drops the task if they retire
   * first — which is the correct outcome for every use here, because all of them are cosmetics applied
   * to somebody who is still connected. On regular Paper this is the main thread, so the behaviour is
   * unchanged; on Folia it is the difference between a release and a thread-ownership exception.</p>
   */
  private void onPlayerThread(Player player, Runnable action) {
    player.getScheduler().run(plugin, ignored -> action.run(), null);
  }

  /** Countdown, timeout kicks, and the belt-and-braces re-read of the request row. */
  private void tick() {
    long now = System.currentTimeMillis();
    for (AuthHoldService.Hold hold : holds.tickExpired(now)) {
      Player player = plugin.getServer().getPlayer(hold.playerId());
      if (player != null && player.isOnline()) {
        onPlayerThread(player, () -> player.kick(MINI_MESSAGE.deserialize(
            AuthMessages.bilingual(core.messages(), MessageKey.AUTH_HOLD_TIMEOUT))));
      }
    }
    AuthSessionService sessions = core.authSessions();
    for (AuthHoldService.Hold hold : holds.all()) {
      Player player = plugin.getServer().getPlayer(hold.playerId());
      if (player == null || !player.isOnline()) {
        holds.release(hold.playerId());
        continue;
      }
      // A dropped bus message must not strand anybody: the row is the truth, and reading it once a
      // second for the handful of players actually being held costs nothing.
      if (sessions != null) {
        Optional<AuthSessionService.HoldTicket> ticket = sessions.pendingHold(hold.identityId());
        if (ticket.isPresent() && ticket.get().decided()) {
          release(hold.playerId(), ticket.get().approved());
          continue;
        }
      }
      long seconds = Math.max(0L, (hold.deadline() - now) / 1000L);
      onPlayerThread(player, () -> player.sendActionBar(MINI_MESSAGE.deserialize(
          inline(MessageKey.AUTH_HOLD_ACTIONBAR, MessageArg.text("seconds", seconds)))));
    }
  }

  // --- the freeze ---------------------------------------------------------

  /**
   * Movement, minus head rotation.
   *
   * <p>Cancelling rotation too makes the client rubber-band on every mouse twitch, which reads as a
   * broken server rather than as a rule — and a player who can look around does not feel dead.</p>
   */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    if (!held(event.getPlayer()) || !policy.blockMove()) {
      return;
    }
    if (event.getTo() == null || event.getFrom().toVector().equals(event.getTo().toVector())) {
      return;
    }
    event.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    if (held(event.getPlayer()) && policy.blockChat()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onCommand(PlayerCommandPreprocessEvent event) {
    if (held(event.getPlayer()) && policy.blockCommands()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    cancelIfHeld(event.getPlayer(), event);
  }

  /** Damage in both directions: a held player can neither be hurt nor hurt anybody. */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player victim && held(victim)) {
      event.setCancelled(true);
      return;
    }
    if (event instanceof EntityDamageByEntityEvent hit
        && hit.getDamager() instanceof Player attacker && held(attacker)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onHunger(FoodLevelChangeEvent event) {
    if (event.getEntity() instanceof Player player && held(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onDrop(PlayerDropItemEvent event) {
    cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player && held(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInteractEntity(PlayerInteractEntityEvent event) {
    cancelIfHeld(event.getPlayer(), event);
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player && held(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player && held(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onSwapHands(PlayerSwapHandItemsEvent event) {
    cancelIfHeld(event.getPlayer(), event);
  }

  /**
   * Teleports, except the ones the hold itself performs.
   *
   * <p>{@code SPECTATE} is allowed through because switching a held player into spectator mode is a
   * teleport in Paper's eyes, and cancelling our own setup would leave the freeze half applied.</p>
   */
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onTeleport(PlayerTeleportEvent event) {
    if (held(event.getPlayer()) && policy.blockMove()
        && event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onWorldChange(PlayerChangedWorldEvent event) {
    // Not cancellable, so the answer is to re-assert the freeze rather than to refuse the move.
    if (held(event.getPlayer()) && policy.spectator()) {
      event.getPlayer().setGameMode(GameMode.SPECTATOR);
    }
  }

  // --- helpers ------------------------------------------------------------

  private boolean held(Player player) {
    return player != null && holds.isHeld(player.getUniqueId());
  }

  private void cancelIfHeld(Player player, org.bukkit.event.Cancellable event) {
    if (held(player)) {
      event.setCancelled(true);
    }
  }

  private String inline(MessageKey key, MessageArg... args) {
    return AuthMessages.bilingualInline(core.messages(), key, args);
  }

  private static GameMode gameModeOf(String name) {
    try {
      return name == null ? GameMode.SURVIVAL : GameMode.valueOf(name);
    } catch (IllegalArgumentException unknown) {
      return GameMode.SURVIVAL;
    }
  }
}
