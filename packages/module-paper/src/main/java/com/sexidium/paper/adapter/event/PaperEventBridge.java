package com.sexidium.paper.adapter.event;

import com.sexidium.paper.adapter.player.PaperGeyser;
import com.sexidium.paper.adapter.player.PaperHardcoreView;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.paper.adapter.util.PaperConverters;
import com.sexidium.paper.adapter.world.PaperMobHandle;
import com.sexidium.core.SexidiumCore;
import com.sexidium.core.auth.AuthConnection;
import com.sexidium.core.auth.AuthResults.GateOutcome;
import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.BlockPlaceGameEvent;
import com.sexidium.core.game.GameEvents.EntityDeathGameEvent;
import com.sexidium.core.game.GameEvents.InventoryChangeGameEvent;
import com.sexidium.core.game.GameEvents.MobDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDeathGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDropItemGameEvent;
import com.sexidium.core.game.GameEvents.PlayerChangedWorldGameEvent;
import com.sexidium.core.game.GameEvents.PlayerAdvancementGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.GameEvents.PlayerJoinGameEvent;
import com.sexidium.core.game.GameEvents.PlayerJumpGameEvent;
import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.GameEvents.PlayerQuitGameEvent;
import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.GameEvents.PlayerToggleSneakGameEvent;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.DamageCauseType;
import com.sexidium.core.platform.model.ItemKey;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerVelocityEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PaperEventBridge implements Listener {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  /** How long after a server-applied upward launch a "jump" is treated as that launch rather than input. */
  private static final long KNOCKBACK_VETO_MILLIS = 250L;
  private final SexidiumCore core;
  // Last time the server pushed each player UPWARDS. ConcurrentHashMap because on Folia the velocity and
  // the jump run on the player's own region thread, and different players are different threads. Cleared
  // in onQuit so a long-lived server cannot accumulate an entry per player who ever logged in.
  private final Map<UUID, Long> lastUpwardLaunchMillis = new ConcurrentHashMap<>();

  public PaperEventBridge(SexidiumCore core) {
    this.core = core;
  }

  /**
   * The backend's own gate, fail-closed and independent of the proxy's.
   *
   * <p>Backends run {@code online-mode=false} under modern forwarding, so anything that reaches this
   * event has already been vouched for by the proxy — or has bypassed it. Re-running the gate here
   * with the real network address is what makes the second case a refusal rather than a free pass.
   * A {@code HOLD} is an ALLOW: the player lands, and {@code PaperAuthHold} freezes them on join.</p>
   */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void onPreLogin(AsyncPlayerPreLoginEvent event) {
    AuthConnection connection = AuthConnection.of(
        event.getUniqueId(),
        event.getName(),
        event.getAddress() == null ? null : event.getAddress().getHostAddress());
    if (PaperGeyser.isBedrock(event.getUniqueId())) {
      connection = new AuthConnection(connection.username(), connection.connectionUuid(),
          connection.ip(), null, 0, true, false, null);
    }
    GateOutcome outcome = core.authLogin().verify(connection);
    if (!outcome.allowed()) {
      // Paper: usa a sobrecarga Adventure para que a mensagem aceite MiniMessage.
      event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, MINI_MESSAGE.deserialize(outcome.rejectionMessage()));
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    PaperPlayerAdapter player = new PaperPlayerAdapter(event.getPlayer());
    // Record what the client was just told about hardcore by its own login packet, so the first world
    // change only re-tells it when the answer has actually changed.
    PaperHardcoreView.seed(event.getPlayer());
    core.events().handle(new PlayerJoinGameEvent(player));
    core.rankTags().apply(player);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    core.events().handle(new PlayerQuitGameEvent(new PaperPlayerAdapter(event.getPlayer())));
    PaperHardcoreView.forget(event.getPlayer().getUniqueId());
    UUID quitting = event.getPlayer().getUniqueId();
    if (quitting != null) {
      // ConcurrentHashMap rejects a null key outright, and a platform/test player without an identity is
      // not worth failing a disconnect over — it can have no entry to remove either way.
      lastUpwardLaunchMillis.remove(quitting);
    }
  }

  // The JUMP INPUT, surfaced as its own event because no amount of movement sampling can recover it.
  //
  // Paper raises PlayerJumpEvent from the movement-packet handler on the predicate "was on the ground,
  // is now airborne, Y went up". That predicate cannot tell a keypress from a creeper punt — a player
  // launched by an explosion, a mob's knockback or a piston satisfies it exactly as a jumper does. The
  // modes downstream of this event are built on the opposite promise ("you are only punished for what
  // you chose"), so the bridge vetoes any jump that lands inside KNOCKBACK_VETO_MILLIS of the server
  // having pushed that player upwards — recorded by onVelocity below, which is the only place a
  // server-applied launch is visible as such.
  //
  // MONITOR + ignoreCancelled, and NEVER cancelled here: cancelling a PlayerJumpEvent makes Paper
  // rubber-band the client back to getFrom(), which would read as lag, not as a rule.
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onJump(com.destroystokyo.paper.event.player.PlayerJumpEvent event) {
    UUID jumper = event.getPlayer().getUniqueId();
    Long launchedAt = jumper == null ? null : lastUpwardLaunchMillis.get(jumper);
    if (launchedAt != null && System.currentTimeMillis() - launchedAt < KNOCKBACK_VETO_MILLIS) {
      return; // launched, not jumped
    }
    core.events().handle(new PlayerJumpGameEvent(
        new PaperPlayerAdapter(event.getPlayer()), PaperConverters.toCore(event.getFrom())));
  }

  // The knockback veto's only input: every velocity the SERVER applies to a player passes through here,
  // and a player's own jump does not (the client jumps itself and reports the move). An upward push is
  // therefore a reliable "this player was launched" marker, and the timestamp is all onJump needs.
  @EventHandler(priority = EventPriority.MONITOR)
  public void onVelocity(PlayerVelocityEvent event) {
    UUID launched = event.getPlayer().getUniqueId();
    if (launched != null && event.getVelocity() != null && event.getVelocity().getY() > 0.0) {
      lastUpwardLaunchMillis.put(launched, System.currentTimeMillis());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    PlayerMoveGameEvent gameEvent = new PlayerMoveGameEvent(
        new PaperPlayerAdapter(event.getPlayer()),
        PaperConverters.toCore(event.getFrom()),
        PaperConverters.toCore(event.getTo())
    );
    core.events().handle(gameEvent);
    event.setCancelled(gameEvent.cancelled());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onDamage(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player victim)) {
      return;
    }
    Player attacker = resolveAttacker(event);
    // Paper: finalDamage() e o valor canonico ja processado por modifiers.
    PlayerDamageGameEvent gameEvent = new PlayerDamageGameEvent(
        new PaperPlayerAdapter(victim),
        attacker == null ? null : new PaperPlayerAdapter(attacker),
        damageCause(event.getCause()),
        event.getFinalDamage()
    );
    core.events().handle(gameEvent);
    event.setCancelled(gameEvent.cancelled());
  }

  /**
   * Stops hostiles picking on somebody nobody is driving.
   *
   * <p>Answered straight from the watch rather than through a game event, following the
   * {@code PaperLobbyGuard} precedent. This fires whenever ANY mob anywhere re-evaluates a target, and
   * routing a fresh event through every active match to answer "no" — which is the answer essentially
   * always — would cost two adapter allocations and a fan-out per mob per re-target, forever. The
   * {@code anyDowned()} gate in front makes the common case one read of an empty map.</p>
   *
   * <p>{@code setTarget(null)}, NOT {@code setCancelled(true)}. Cancelling an EntityTargetEvent means
   * "refuse this CHANGE of target" — for a mob already chasing this player that PRESERVES the aggro,
   * which is the exact opposite of what is wanted here.</p>
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityTarget(org.bukkit.event.entity.EntityTargetLivingEntityEvent event) {
    if (!(event.getTarget() instanceof Player player) || !core.playerControl().anyDowned()) {
      return;
    }
    if (core.playerControl().downed(player.getUniqueId())) {
      event.setTarget(null);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onMobDamage(EntityDamageByEntityEvent event) {
    if (event.getEntity() instanceof Player) {
      return; // player victims are handled by onDamage
    }
    if (!(event.getEntity() instanceof org.bukkit.entity.Mob mob)) {
      return;
    }
    Player attacker = resolveAttacker(event);
    if (attacker == null) {
      return;
    }
    core.events().handle(new MobDamageGameEvent(
        new PaperMobHandle(mob),
        new PaperPlayerAdapter(attacker),
        event.getFinalDamage()));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    BlockBreakGameEvent gameEvent = new BlockBreakGameEvent(
        new PaperPlayerAdapter(event.getPlayer()),
        position(event.getBlock()),
        PaperConverters.toCore(event.getBlock().getType())
    );
    core.events().handle(gameEvent);
    event.setCancelled(gameEvent.cancelled());
    event.setDropItems(gameEvent.dropItems());
  }

  // TNT (and other) explosions in an experience world are routed block-by-block through the SAME break
  // path as a manual break, so the loot pipeline (Double Drops multiplier, Randomizer remap) and the
  // sweep challenges (Break-One-Break-All registers the type, Chunk Break queues the chunk) all react to
  // a blast exactly as they do to a hand-broken block. Gated to experience worlds so minigames that rely
  // on raw TNT (TNT War) and other modes are untouched.
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    routeExperienceExplosion(event.getLocation(), event.blockList());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    routeExperienceExplosion(event.getBlock().getLocation(), event.blockList());
  }

  private void routeExperienceExplosion(Location source, java.util.List<Block> blocks) {
    if (source == null || source.getWorld() == null || blocks.isEmpty()
        || com.sexidium.core.world.WorldKey.fromRuntime(source.getWorld().getName())
            .map(core.experiences()::byWorld).orElse(null) == null) {
      return;
    }
    Player cause = nearestPlayer(source);
    if (cause == null) {
      return; // no participant to attribute the blast to — leave it vanilla
    }
    PaperPlayerAdapter adapter = new PaperPlayerAdapter(cause);
    java.util.Iterator<Block> iterator = blocks.iterator();
    while (iterator.hasNext()) {
      Block block = iterator.next();
      Material type = block.getType();
      if (type.isAir()) {
        continue;
      }
      BlockBreakGameEvent gameEvent = new BlockBreakGameEvent(
          adapter, position(block), PaperConverters.toCore(type));
      core.events().handle(gameEvent);
      if (gameEvent.cancelled()) {
        iterator.remove(); // a challenge vetoed the break — keep the block standing
      } else if (!gameEvent.dropItems()) {
        // A challenge claimed and already emitted this block's loot; drop it from the blast so vanilla
        // does not double-drop, and clear it ourselves since the explosion will no longer touch it.
        iterator.remove();
        block.setType(Material.AIR, false);
      }
      // Otherwise leave the block for the vanilla explosion to destroy and drop normally.
    }
  }

  private Player nearestPlayer(Location source) {
    Player nearest = null;
    double best = Double.MAX_VALUE;
    for (Player player : source.getWorld().getPlayers()) {
      double distance = player.getLocation().distanceSquared(source);
      if (distance < best) {
        best = distance;
        nearest = player;
      }
    }
    return nearest;
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    BlockPlaceGameEvent gameEvent = new BlockPlaceGameEvent(
        new PaperPlayerAdapter(event.getPlayer()),
        position(event.getBlockPlaced()),
        PaperConverters.toCore(event.getBlockPlaced().getType())
    );
    core.events().handle(gameEvent);
    event.setCancelled(gameEvent.cancelled());
  }

  // Reliable air-swing detection. PlayerInteractEvent's LEFT_CLICK_AIR does not fire for every swing
  // at empty air, so a "gesture in the air" can be missed. PlayerAnimationEvent (ARM_SWING) fires on
  // every arm swing — air, block, or attack — so we surface it as a LEFT_CLICK interaction (no block),
  // letting Total Cleave trigger on air. The challenge dedupes the resulting double-fire on a real
  // block left-click via its own short swing cooldown.
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onArmSwing(PlayerAnimationEvent event) {
    if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
      return;
    }
    core.events().handle(new PlayerInteractGameEvent(
        new PaperPlayerAdapter(event.getPlayer()),
        PlayerInteractGameEvent.ActionType.LEFT_CLICK,
        null,
        null));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    Material material = event.getItem() == null ? Material.AIR : event.getItem().getType();
    PlayerInteractGameEvent gameEvent = new PlayerInteractGameEvent(
        new PaperPlayerAdapter(event.getPlayer()),
        action(event.getAction().name()),
        PaperConverters.toCore(material),
        event.getClickedBlock() == null ? null : position(event.getClickedBlock())
    );
    core.events().handle(gameEvent);
    event.setCancelled(gameEvent.cancelled());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    InventoryChangeGameEvent gameEvent = new InventoryChangeGameEvent(new PaperPlayerAdapter(player));
    core.events().handle(gameEvent);
    event.setCancelled(gameEvent.cancelled());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    InventoryChangeGameEvent gameEvent = new InventoryChangeGameEvent(new PaperPlayerAdapter(player));
    core.events().handle(gameEvent);
    event.setCancelled(gameEvent.cancelled());
  }

  // Two things at once, and they must stay in this order.
  //
  // First the drop is OFFERED, so a mode can refuse it (a world being frozen for a reset must not let
  // anyone throw their inventory onto terrain that is about to be deleted). HIGHEST rather than MONITOR,
  // because a cancel is only worth anything if it is honoured.
  //
  // Then, if it was allowed: a field drop (Q / drop-all) removes the item from the inventory
  // synchronously but fires NO click/drag event, so without a change signal here it is only caught by
  // the shared-inventory challenge's slow periodic reconcile — the exact window in which another
  // sharer's stale copy of the slot re-adds the item and duplicates it. A REFUSED drop raises no change
  // event at all, because nothing changed.
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlayerDropItem(PlayerDropItemEvent event) {
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(event.getPlayer());
    PlayerDropItemGameEvent dropEvent = new PlayerDropItemGameEvent(playerAdapter,
        PaperConverters.toCore(event.getItemDrop().getItemStack().getType()));
    core.events().handle(dropEvent);
    if (dropEvent.cancelled()) {
      event.setCancelled(true);
      return;
    }
    core.events().handle(new InventoryChangeGameEvent(playerAdapter));
  }

  // Bukkit has no generic "inventory changed" event, so click/drag alone miss items gained by
  // picking up, mining, crafting or smelting. These add to the inventory AFTER the event fires,
  // so we re-scan one tick later. This makes RaceGame.scanInventory credit acquired items on
  // Paper, matching the NeoForge per-tick inventory diff.
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onItemPickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player) {
      scanInventoryNextTick(player);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onCraft(CraftItemEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      scanInventoryNextTick(player);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFurnaceExtract(FurnaceExtractEvent event) {
    scanInventoryNextTick(event.getPlayer());
  }

  private void scanInventoryNextTick(Player player) {
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(player);
    core.gameContext().server().scheduler().runLater(
        () -> core.events().handle(new InventoryChangeGameEvent(playerAdapter)), 1L);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onEntityDeath(EntityDeathEvent event) {
    core.events().handle(new EntityDeathGameEvent(event.getEntityType().name(), PaperConverters.toCore(event.getEntity().getLocation())));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onEntityUnleash(EntityUnleashEvent event) {
    // A Chained-Together rope marker is unleashed by vanilla when its holder (a player) dies or
    // disconnects. Stop the lead from dropping as a pickup-able item — the challenge re-leashes the
    // marker on respawn, so the rope should never leave a stray lead on the ground.
    if (event.getEntity().getScoreboardTags().contains("sx_rope_marker")) {
      event.setDropLeash(false);
    }
  }

  // MONITOR: the death has happened and cannot be undone here — we only report it. Fired at the DEATH
  // rather than at the respawn, because a hardcore client's death screen offers no Respawn button, so a
  // mode that watched for the respawn would never learn the player had died at all.
  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
    core.events().handle(new PlayerDeathGameEvent(
        new PaperPlayerAdapter(event.getEntity()),
        PaperConverters.toCore(event.getEntity().getLocation())));
  }

  // HIGH, not MONITOR: a game may REDIRECT the respawn (an experience keeps a player in the dimension
  // they died in), and the redirect only sticks if it is applied before the respawn location is used.
  @EventHandler(priority = EventPriority.HIGH)
  public void onRespawn(PlayerRespawnEvent event) {
    PlayerRespawnGameEvent gameEvent = new PlayerRespawnGameEvent(
        new PaperPlayerAdapter(event.getPlayer()), PaperConverters.toCore(event.getRespawnLocation()));
    core.events().handle(gameEvent);
    Location redirect = PaperConverters.toNative(gameEvent.respawnPosition());
    if (redirect != null && redirect.getWorld() != null) {
      event.setRespawnLocation(redirect);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChangedWorld(PlayerChangedWorldEvent event) {
    PaperPlayerAdapter playerAdapter = new PaperPlayerAdapter(event.getPlayer());
    core.events().handle(new PlayerChangedWorldGameEvent(
        playerAdapter,
        event.getFrom().getName(),
        event.getPlayer().getWorld().getName()
    ));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
    core.events().handle(new PlayerAdvancementGameEvent(
        new PaperPlayerAdapter(event.getPlayer()),
        event.getAdvancement().getKey().toString()
    ));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onToggleSneak(PlayerToggleSneakEvent event) {
    core.events().handle(new PlayerToggleSneakGameEvent(new PaperPlayerAdapter(event.getPlayer()), event.isSneaking()));
  }

  private Player resolveAttacker(EntityDamageEvent event) {
    if (!(event instanceof EntityDamageByEntityEvent damageByEntityEvent)) {
      return null;
    }
    if (damageByEntityEvent.getDamager() instanceof Player player) {
      return player;
    }
    if (damageByEntityEvent.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
      return player;
    }
    return null;
  }

  private DamageCauseType damageCause(EntityDamageEvent.DamageCause damageCause) {
    String causeName = damageCause == null ? "" : damageCause.name();
    if (causeName.contains("EXPLOSION")) {
      return DamageCauseType.EXPLOSION;
    }
    if (causeName.contains("PROJECTILE")) {
      return DamageCauseType.PROJECTILE;
    }
    if (causeName.contains("FIRE")) {
      return DamageCauseType.FIRE;
    }
    return switch (causeName) {
      case "ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK" -> DamageCauseType.ENTITY_ATTACK;
      case "LAVA" -> DamageCauseType.LAVA;
      case "FALL" -> DamageCauseType.FALL;
      case "VOID" -> DamageCauseType.VOID;
      case "MAGIC", "POISON", "WITHER" -> DamageCauseType.MAGIC;
      case "STARVATION" -> DamageCauseType.STARVATION;
      case "CUSTOM" -> DamageCauseType.CUSTOM;
      default -> DamageCauseType.UNKNOWN;
    };
  }

  private PlayerInteractGameEvent.ActionType action(String actionName) {
    if (actionName == null) {
      return PlayerInteractGameEvent.ActionType.UNKNOWN;
    }
    if (actionName.startsWith("LEFT_CLICK")) {
      return PlayerInteractGameEvent.ActionType.LEFT_CLICK;
    }
    if (actionName.startsWith("RIGHT_CLICK")) {
      return PlayerInteractGameEvent.ActionType.RIGHT_CLICK;
    }
    if (actionName.equals("PHYSICAL")) {
      return PlayerInteractGameEvent.ActionType.PHYSICAL;
    }
    return PlayerInteractGameEvent.ActionType.UNKNOWN;
  }

  private BlockPosition position(Block block) {
    return new BlockPosition(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
  }
}
