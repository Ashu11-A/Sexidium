package com.sexidium.paper.adapter.world;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.world.LobbyGuardPolicy;
import com.sexidium.core.world.hotbar.HotbarScope;
import com.sexidium.core.world.hotbar.HotbarSlot;
import com.sexidium.core.world.hotbar.items.LobbyInviteItem;
import com.sexidium.core.world.lobby.LobbyResult;
import com.sexidium.paper.adapter.menu.PaperUiItemFactory;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.paper.adapter.util.PaperConverters;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Enforces lobby protection rules on the Paper adapter. Each native event is gated by the matching
 * toggle in {@link LobbyGuardPolicy} plus {@link LobbyGuardPolicy#isProtected(com.sexidium.core.platform.PlayerAdapter)},
 * which only returns {@code true} when the affected player is in the lobby world and not inside an
 * active match. Join/respawn placement instead honours {@link LobbyGuardPolicy#asDefaultSpawn()} so
 * players always land back at the lobby spawn.
 */
public final class PaperLobbyGuard implements Listener {
  private final JavaPlugin plugin;
  private final SexidiumCore core;
  private final LobbyGuardPolicy policy;
  private final NamespacedKey navKey;
  private final NamespacedKey navIdKey;
  // Per-player resource-pack gate (from PaperResourcePackService): the nav items only wear their custom
  // item-models for players who loaded the pack, so a no-pack / Bedrock player never sees a missing-model
  // hotbar item — they keep the plain compass / ender chest. Defaults to "nobody" until wired in.
  private Predicate<UUID> packLoaded = id -> false;
  // Last rendered hotbar signature per player, so the periodic refresh re-renders only when the resolved
  // lobby items actually changed (e.g. the player created a lobby, joined a team, the team count changed)
  // instead of clearing + rebuilding the inventory every tick.
  private final Map<UUID, String> hotbarSignatures = new ConcurrentHashMap<>();

  public PaperLobbyGuard(JavaPlugin plugin, SexidiumCore core, ConfigurationAdapter configuration) {
    this.plugin = plugin;
    this.core = core;
    this.policy = new LobbyGuardPolicy(configuration, core.games());
    this.navKey = new NamespacedKey(plugin, "nav");
    this.navIdKey = new NamespacedKey(plugin, "nav_id");
  }

  /** Supplies the per-player pack-load predicate (from {@link com.sexidium.paper.adapter.menu.PaperResourcePackService}). */
  public void setPackGate(Predicate<UUID> gate) {
    this.packLoaded = gate == null ? id -> false : gate;
  }

  /**
   * Re-renders the lobby hotbar for {@code playerId} now that their resource-pack status changed, so the
   * custom item-models appear (or disappear) without a relog. Called from the pack service on
   * {@code SUCCESSFULLY_LOADED}. The full re-render always replaces, picking up the newly-available art.
   */
  public void refreshNavItems(UUID playerId) {
    Player player = plugin.getServer().getPlayer(playerId);
    if (player != null && player.isOnline() && policy.isProtected(new PaperPlayerAdapter(player))) {
      applyHotbar(player, core.hotbar().resolve(new PaperPlayerAdapter(player), HotbarScope.LOBBY));
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    if (policy.blockBlockBreak() && policy.isProtected(new PaperPlayerAdapter(event.getPlayer()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onBlockPlace(BlockPlaceEvent event) {
    if (policy.blockBlockPlace() && policy.isProtected(new PaperPlayerAdapter(event.getPlayer()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onEntityDamage(EntityDamageEvent event) {
    // Left-clicking (attacking) a player while holding the lobby invite tool invites them. Handled here,
    // before the damage guard cancels the hit, so left-click works alongside the right-click path below.
    if (event instanceof EntityDamageByEntityEvent hit && hit.getDamager() instanceof Player attacker
        && tryInviteWithItem(attacker, event.getEntity())) {
      event.setCancelled(true);
      return;
    }
    if (!(event.getEntity() instanceof Player player)) {
      return;
    }
    if (policy.blockDamage() && policy.isProtected(new PaperPlayerAdapter(player))) {
      event.setCancelled(true);
    }
  }

  /** Right-clicking a player while holding the lobby invite tool invites them. */
  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onInviteInteractEntity(PlayerInteractEntityEvent event) {
    if (tryInviteWithItem(event.getPlayer(), event.getRightClicked())) {
      event.setCancelled(true);
    }
  }

  /**
   * Invites {@code target} to {@code attacker}'s lobby when the attacker is a protected lobby player
   * holding the lobby-invite hotbar tool and the target is another player. Returns true when the click was
   * an invite (so the caller cancels the underlying interaction/attack).
   */
  private boolean tryInviteWithItem(Player attacker, org.bukkit.entity.Entity targetEntity) {
    if (!(targetEntity instanceof Player target) || core.lobbies() == null) {
      return false;
    }
    if (!policy.isProtected(new PaperPlayerAdapter(attacker))) {
      return false;
    }
    if (!LobbyInviteItem.ID.equals(navIdOf(attacker.getInventory().getItemInMainHand()))) {
      return false;
    }
    PaperPlayerAdapter inviter = new PaperPlayerAdapter(attacker);
    LobbyResult result = core.lobbies().invite(inviter, new PaperPlayerAdapter(target));
    inviter.sendActionBar(result == LobbyResult.INVITE_SENT
        ? "<green>Invited " + target.getName() + " to your lobby.</green>"
        : result == LobbyResult.TARGET_IN_PARTY
            ? "<red>" + target.getName() + " is already in a group.</red>"
            : result == LobbyResult.FULL
                ? "<red>Your lobby is full.</red>"
                : "<red>Could not invite " + target.getName() + ".</red>");
    if (result == LobbyResult.INVITE_SENT) {
      new PaperPlayerAdapter(target).sendMiniMessage("<aqua>" + attacker.getName()
          + "</aqua> <gray>invited you to their lobby — open the menu ▶ Lobby ▶ Invites.</gray>");
    }
    return true;
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onFoodLevelChange(FoodLevelChangeEvent event) {
    if (!(event.getEntity() instanceof Player player)) {
      return;
    }
    if (policy.blockHunger() && policy.isProtected(new PaperPlayerAdapter(player))) {
      event.setFoodLevel(20);
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onItemDrop(PlayerDropItemEvent event) {
    if (policy.blockItemDrop() && policy.isProtected(new PaperPlayerAdapter(event.getPlayer()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onInteract(PlayerInteractEvent event) {
    if (policy.blockInteractions() && policy.isProtected(new PaperPlayerAdapter(event.getPlayer()))) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onMove(PlayerMoveEvent event) {
    if (!policy.voidRedirect() || event.getTo() == null) {
      return;
    }
    if (event.getTo().getY() >= policy.voidLevel()) {
      return;
    }
    Player player = event.getPlayer();
    if (!policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    Location lobbySpawn = lobbySpawn();
    if (lobbySpawn != null) {
      player.teleportAsync(lobbySpawn);
    }
  }

  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  public void onCreatureSpawn(CreatureSpawnEvent event) {
    if (!policy.blockMobSpawn() || event.getLocation().getWorld() == null) {
      return;
    }
    if (!policy.isLobbyWorld(event.getLocation().getWorld().getName())) {
      return;
    }
    // Block natural/spawner mob spawns but keep plugin/admin spawns (NPCs, /summon, spawn eggs).
    switch (event.getSpawnReason()) {
      case CUSTOM, COMMAND, SPAWNER_EGG -> {
        // allowed
      }
      default -> event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    // Do not pull a reconnecting player to the lobby: they have a match to be restored into (the
    // reconnect flow teleports them to their match world ~1 tick after join).
    boolean restoringMatch = core.games().hasPersistedSession(player.getUniqueId());
    if (policy.asDefaultSpawn() && !restoringMatch) {
      // Delay one tick so this overrides the vanilla spawn placement applied during join.
      player.getScheduler().runDelayed(plugin, ignored -> {
        Location lobbySpawn = lobbySpawn();
        if (lobbySpawn != null && player.isOnline()) {
          player.teleportAsync(lobbySpawn);
        }
        giveNavItemsIfLobby(player);
      }, null, 1L);
    } else if (!restoringMatch) {
      giveNavItemsIfLobby(player);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onChangedWorld(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    if (policy.isLobbyWorld(player.getWorld().getName())) {
      giveNavItemsIfLobby(player);
    } else {
      // Leaving the lobby (e.g. entering an experience or minigame world): strip the lobby nav items
      // so the Menu / My Experiences items never leak into the world the player enters.
      clearNavItems(player);
    }
  }

  @EventHandler(priority = EventPriority.HIGH)
  public void onRespawn(PlayerRespawnEvent event) {
    Player player = event.getPlayer();
    boolean inMatch = core.games().matchOf(new PaperPlayerAdapter(player)) != null;
    if (policy.asDefaultSpawn() && !inMatch) {
      Location lobbySpawn = lobbySpawn();
      if (lobbySpawn != null) {
        event.setRespawnLocation(lobbySpawn);
      }
    }
    // Respawn location is applied after the event; give items one tick later so the player is in the
    // lobby world before we evaluate isProtected/giveNavItemsIfLobby.
    player.getScheduler().runDelayed(plugin, ignored -> giveNavItemsIfLobby(player), null, 1L);
  }

  // ----- nav-item handlers -------------------------------------------------------------------

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onNavInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    if (!policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    String navId = navIdOf(event.getItem());
    if (navId == null) {
      return;
    }
    event.setCancelled(true);
    // Route the click back to the core hotbar controller, which owns each item's behaviour.
    core.hotbar().handleClick(new PaperPlayerAdapter(player), HotbarScope.LOBBY, navId);
  }

  /** Lobby decorative chest blocks act as physical menu entry points, not vanilla storage containers. */
  @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
  public void onLobbyMenuContainerInteract(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
      return;
    }
    Player player = event.getPlayer();
    if (!policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    Material type = event.getClickedBlock().getType();
    if (!isLobbyMenuContainer(type)) {
      return;
    }
    event.setCancelled(true);
    PaperPlayerAdapter adapter = new PaperPlayerAdapter(player);
    if (type == Material.ENDER_CHEST) {
      core.menus().openExperiences(adapter);
    } else if (core.lobbies() != null && core.lobbies().hasActiveLobby(player.getUniqueId())) {
      core.menus().openLobby(adapter);
    } else {
      core.menus().openMain(adapter);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onNavDrop(PlayerDropItemEvent event) {
    Player player = event.getPlayer();
    if (!policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    if (navIdOf(event.getItemDrop().getItemStack()) != null) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onNavSwapHands(PlayerSwapHandItemsEvent event) {
    Player player = event.getPlayer();
    if (!policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    if (navIdOf(event.getMainHandItem()) != null || navIdOf(event.getOffHandItem()) != null) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onNavInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (!policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    // Block any click touching a tagged nav item (the clicked slot, the cursor, or a hotbar
    // number-key/swap target), so the persistent items cannot be moved or removed.
    if (navIdOf(event.getCurrentItem()) != null || navIdOf(event.getCursor()) != null) {
      event.setCancelled(true);
      return;
    }
    if (event.getHotbarButton() >= 0) {
      ItemStack hotbarItem = player.getInventory().getItem(event.getHotbarButton());
      if (navIdOf(hotbarItem) != null) {
        event.setCancelled(true);
        return;
      }
    }
    // Disable crafting: any click inside the 2x2 player grid or a 3x3 workbench.
    InventoryType clickedType = event.getClickedInventory() == null
        ? null : event.getClickedInventory().getType();
    if (clickedType == InventoryType.CRAFTING || clickedType == InventoryType.WORKBENCH) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onNavInventoryDrag(InventoryDragEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (!policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    if (navIdOf(event.getOldCursor()) != null) {
      event.setCancelled(true);
      return;
    }
    Inventory top = event.getView().getTopInventory();
    int topSize = top.getSize();
    for (int rawSlot : event.getRawSlots()) {
      ItemStack existing = rawSlot < topSize ? top.getItem(rawSlot) : null;
      if (navIdOf(existing) != null) {
        event.setCancelled(true);
        return;
      }
    }
  }

  // ----- crafting lockdown -------------------------------------------------------------------

  @EventHandler(priority = EventPriority.LOW)
  public void onPrepareCraft(PrepareItemCraftEvent event) {
    if (event.getView().getPlayer() instanceof Player player
        && policy.isProtected(new PaperPlayerAdapter(player))) {
      event.getInventory().setResult(null);
    }
  }

  @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
  public void onCraft(CraftItemEvent event) {
    if (event.getWhoClicked() instanceof Player player
        && policy.isProtected(new PaperPlayerAdapter(player))) {
      event.setCancelled(true);
    }
  }

  // ----- helpers -----------------------------------------------------------------------------

  /**
   * Starts the periodic hotbar refresh: the lobby hotbar swaps to the lobby-management tools (and back)
   * when a player's lobby state changes with no world change — e.g. creating a lobby from the cake menu,
   * being invited into one, or a team-count change. Polling with a per-player signature diff catches
   * every such change (menu-driven, command-driven, or the tick-driven queue flips) in one place and
   * re-renders only when something actually changed. Called once from the plugin after registration.
   */
  public void start() {
    plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tickHotbars(), 20L, 10L);
  }

  private void tickHotbars() {
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      // Hop to the player's region thread (Folia-safe; a no-op indirection on regular Paper) before
      // touching their inventory.
      player.getScheduler().run(plugin, ignored -> refreshHotbarIfChanged(player), null);
    }
  }

  /** Renders the lobby hotbar for {@code player} if they are currently a lobby player. */
  private void giveNavItemsIfLobby(Player player) {
    if (!player.isOnline() || !policy.isProtected(new PaperPlayerAdapter(player))) {
      return;
    }
    applyHotbar(player, core.hotbar().resolve(new PaperPlayerAdapter(player), HotbarScope.LOBBY));
  }

  /** Re-renders the hotbar only when the resolved items differ from what the player currently holds. */
  private void refreshHotbarIfChanged(Player player) {
    if (!player.isOnline() || !policy.isProtected(new PaperPlayerAdapter(player))) {
      hotbarSignatures.remove(player.getUniqueId());
      return;
    }
    List<HotbarSlot> slots = core.hotbar().resolve(new PaperPlayerAdapter(player), HotbarScope.LOBBY);
    if (!signatureOf(slots).equals(hotbarSignatures.get(player.getUniqueId()))) {
      applyHotbar(player, slots);
    }
  }

  /**
   * Places the resolved hotbar items: it clears the inventory first — so both leaked experience gear and
   * any now-hidden managed item (e.g. Friend Requests when there are none) go — then places each item,
   * tagged with its routing id, and records the new signature. A lobby player only ever holds these
   * items, so the full clear keeps the hotbar in exact sync with the controller's decision and no
   * experience item can persist into the lobby.
   */
  private void applyHotbar(Player player, List<HotbarSlot> slots) {
    org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack item = inventory.getItem(slot);
      if (item != null && !item.getType().isAir()) {
        inventory.setItem(slot, null);
      }
    }
    boolean art = packLoaded.test(player.getUniqueId());
    for (HotbarSlot hotbarSlot : slots) {
      inventory.setItem(hotbarSlot.slot(), buildHotbarItem(hotbarSlot, art));
    }
    hotbarSignatures.put(player.getUniqueId(), signatureOf(slots));
  }

  /** A compact fingerprint of the resolved hotbar (slot + id + badge + name), for change detection. */
  private static String signatureOf(List<HotbarSlot> slots) {
    StringBuilder builder = new StringBuilder();
    for (HotbarSlot slot : slots) {
      builder.append(slot.slot()).append(':').append(slot.id()).append(':')
          .append(slot.item().amount()).append(':').append(slot.item().name()).append('|');
    }
    return builder.toString();
  }

  /** Materializes one resolved hotbar item via the shared factory and tags its routing id into the PDC. */
  private ItemStack buildHotbarItem(HotbarSlot hotbarSlot, boolean packLoaded) {
    ItemStack item = PaperUiItemFactory.build(hotbarSlot.item(), packLoaded);
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      PersistentDataContainer pdc = meta.getPersistentDataContainer();
      pdc.set(navKey, PersistentDataType.BYTE, (byte) 1);
      pdc.set(navIdKey, PersistentDataType.STRING, hotbarSlot.id());
      item.setItemMeta(meta);
    }
    return item;
  }

  /** Removes every tagged lobby hotbar item from a player's inventory (used when they leave the lobby). */
  private void clearNavItems(Player player) {
    org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      if (navIdOf(inventory.getItem(slot)) != null) {
        inventory.setItem(slot, null);
      }
    }
  }

  private boolean isLobbyMenuContainer(Material material) {
    return material == Material.CHEST
        || material == Material.TRAPPED_CHEST
        || material == Material.BARREL
        || material == Material.ENDER_CHEST;
  }

  /** Returns the nav id of a tagged nav item, or {@code null} if the stack is not a nav item. */
  private String navIdOf(ItemStack item) {
    if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
      return null;
    }
    PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
    Byte flag = pdc.get(navKey, PersistentDataType.BYTE);
    if (flag == null || flag != (byte) 1) {
      return null;
    }
    return pdc.get(navIdKey, PersistentDataType.STRING);
  }

  private Location lobbySpawn() {
    Location resolved = core.gameContext().server().worlds().lobbySpawn()
        .map(PaperConverters::toNative)
        .orElse(null);
    if (resolved != null) {
      return resolved;
    }
    World lobby = Bukkit.getWorld(policy.lobbyWorldName());
    return lobby == null ? null : lobby.getSpawnLocation();
  }
}
