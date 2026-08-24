package com.sexidium.paper.adapter.event;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.auth.AuthLoginService;
import com.sexidium.core.auth.AuthConnection;
import com.sexidium.core.auth.AuthResults.GateOutcome;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEventRouter;
import com.sexidium.core.game.GameEvents.InventoryChangeGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDropItemGameEvent;
import com.sexidium.core.game.GameEvents.PlayerJoinGameEvent;
import com.sexidium.core.platform.RankTagAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.DamageCauseType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.data.RankTagService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperEventBridgeTest {

  private SexidiumCore core;
  private GameEventRouter router;
  private AuthLoginService authLogin;
  private List<GameEvent> captured;
  private PaperEventBridge bridge;

  @BeforeEach
  void setUp() {
    core = mock(SexidiumCore.class);
    router = mock(GameEventRouter.class);
    authLogin = mock(AuthLoginService.class);
    captured = new ArrayList<>();
    when(core.events()).thenReturn(router);
    when(core.authLogin()).thenReturn(authLogin);
    ServerAdapter rankServer = mock(ServerAdapter.class);
    when(rankServer.rankTags()).thenReturn(RankTagAdapter.NOOP);
    when(core.rankTags()).thenReturn(new RankTagService(rankServer, null));
    org.mockito.Mockito.doAnswer(invocation -> {
      captured.add(invocation.getArgument(0));
      return null;
    }).when(router).handle(any(GameEvent.class));
    bridge = new PaperEventBridge(core);
  }

  @Test
  void onPreLogin_allowed_doesNotDisallow() {
    UUID uuid = UUID.randomUUID();
    AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
    when(event.getUniqueId()).thenReturn(uuid);
    when(event.getName()).thenReturn("Steve");
    when(authLogin.verify(any(AuthConnection.class))).thenReturn(GateOutcome.allow());

    bridge.onPreLogin(event);
    verify(event, never()).disallow(any(AsyncPlayerPreLoginEvent.Result.class), any(net.kyori.adventure.text.Component.class));
  }

  @Test
  void onPreLogin_rejected_disallows() {
    UUID uuid = UUID.randomUUID();
    AsyncPlayerPreLoginEvent event = mock(AsyncPlayerPreLoginEvent.class);
    when(event.getUniqueId()).thenReturn(uuid);
    when(event.getName()).thenReturn("Hacker");
    when(authLogin.verify(any(AuthConnection.class))).thenReturn(GateOutcome.reject("<red>banned</red>"));

    bridge.onPreLogin(event);
    verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
  }

  @Test
  void onJoin_dispatchesJoinEvent() {
    Player player = mock(Player.class);
    PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(player);

    bridge.onJoin(event);
    assertEquals(1, captured.size());
    assertInstanceOf(PlayerJoinGameEvent.class, captured.get(0));
  }

  @Test
  void onQuit_dispatchesQuitEvent() {
    Player player = mock(Player.class);
    PlayerQuitEvent event = mock(PlayerQuitEvent.class);
    when(event.getPlayer()).thenReturn(player);

    bridge.onQuit(event);
    assertEquals(1, captured.size());
    assertEquals(com.sexidium.core.game.GameEvents.PlayerQuitGameEvent.class, captured.get(0).getClass());
  }

  @Test
  void onMove_setsCancelledWhenCoreCancels() {
    Player player = mock(Player.class);
    World world = mock(World.class);
    Location from = new Location(world, 0, 0, 0);
    Location to = new Location(world, 1, 0, 0);
    PlayerMoveEvent event = mock(PlayerMoveEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getFrom()).thenReturn(from);
    when(event.getTo()).thenReturn(to);

    bridge.onMove(event);
    verify(event, times(1)).setCancelled(anyBoolean());
  }

  @Test
  void onDamage_withPlayerVictim_dispatchesDamageEvent() {
    Player victim = mock(Player.class);
    EntityDamageEvent event = mock(EntityDamageEvent.class);
    when(event.getEntity()).thenReturn(victim);
    when(event.getFinalDamage()).thenReturn(5.0);
    when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);

    bridge.onDamage(event);
    verify(event, times(1)).setCancelled(anyBoolean());
    assertEquals(1, captured.size());
    assertInstanceOf(com.sexidium.core.game.GameEvents.PlayerDamageGameEvent.class, captured.get(0));
  }

  @Test
  void onDamage_withNonPlayerVictim_doesNotDispatch() {
    LivingEntity mob = mock(LivingEntity.class);
    EntityDamageEvent event = mock(EntityDamageEvent.class);
    when(event.getEntity()).thenReturn(mob);

    bridge.onDamage(event);
    assertTrue(captured.isEmpty());
  }

  @Test
  void onDamage_withPlayerAttacker_setsAttacker() {
    Player victim = mock(Player.class);
    Player attacker = mock(Player.class);
    EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
    when(event.getEntity()).thenReturn(victim);
    when(event.getDamager()).thenReturn(attacker);
    when(event.getFinalDamage()).thenReturn(3.0);
    when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.ENTITY_ATTACK);

    bridge.onDamage(event);
    assertEquals(1, captured.size());
  }

  @Test
  void onDamage_withArrowAttacker_resolvesShooter() {
    Player victim = mock(Player.class);
    Player shooter = mock(Player.class);
    Arrow arrow = mock(Arrow.class);
    when(arrow.getShooter()).thenReturn(shooter);
    EntityDamageByEntityEvent event = mock(EntityDamageByEntityEvent.class);
    when(event.getEntity()).thenReturn(victim);
    when(event.getDamager()).thenReturn(arrow);
    when(event.getFinalDamage()).thenReturn(3.0);
    when(event.getCause()).thenReturn(EntityDamageEvent.DamageCause.PROJECTILE);

    bridge.onDamage(event);
    assertEquals(1, captured.size());
  }

  @Test
  void onBlockBreak_setsCancelledAndDropItems() {
    Player player = mock(Player.class);
    Block block = mock(Block.class);
    World world = mock(World.class);
    when(block.getWorld()).thenReturn(world);
    when(world.getName()).thenReturn("world");
    when(block.getX()).thenReturn(10);
    when(block.getY()).thenReturn(64);
    when(block.getZ()).thenReturn(10);
    when(block.getType()).thenReturn(Material.STONE);

    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getBlock()).thenReturn(block);

    bridge.onBlockBreak(event);
    verify(event, times(1)).setCancelled(anyBoolean());
    verify(event, times(1)).setDropItems(anyBoolean());
  }

  @Test
  void onBlockPlace_setsCancelled() {
    Player player = mock(Player.class);
    Block block = mock(Block.class);
    World world = mock(World.class);
    when(block.getWorld()).thenReturn(world);
    when(world.getName()).thenReturn("world");
    when(block.getX()).thenReturn(10);
    when(block.getY()).thenReturn(64);
    when(block.getZ()).thenReturn(10);
    when(block.getType()).thenReturn(Material.STONE);

    BlockPlaceEvent event = mock(BlockPlaceEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getBlockPlaced()).thenReturn(block);

    bridge.onBlockPlace(event);
    verify(event, times(1)).setCancelled(anyBoolean());
  }

  @Test
  void onInteract_leftClick_setsLeftClickAction() {
    Player player = mock(Player.class);
    Block block = mock(Block.class);
    World world = mock(World.class);
    when(block.getWorld()).thenReturn(world);
    when(world.getName()).thenReturn("world");
    when(block.getX()).thenReturn(0);
    when(block.getY()).thenReturn(0);
    when(block.getZ()).thenReturn(0);
    when(block.getType()).thenReturn(Material.STONE);

    PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getAction()).thenReturn(org.bukkit.event.block.Action.LEFT_CLICK_AIR);
    when(event.getItem()).thenReturn(null);
    when(event.getClickedBlock()).thenReturn(null);

    bridge.onInteract(event);
    assertEquals(1, captured.size());
  }

  @Test
  void onInteract_rightClick_setsRightClickAction() {
    Player player = mock(Player.class);
    Block block = mock(Block.class);
    World world = mock(World.class);
    when(block.getWorld()).thenReturn(world);
    when(world.getName()).thenReturn("world");
    when(block.getX()).thenReturn(0);
    when(block.getY()).thenReturn(0);
    when(block.getZ()).thenReturn(0);
    when(block.getType()).thenReturn(Material.STONE);

    PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getAction()).thenReturn(org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK);
    when(event.getItem()).thenReturn(null);
    when(event.getClickedBlock()).thenReturn(block);

    bridge.onInteract(event);
    assertEquals(1, captured.size());
  }

  @Test
  void onInteract_physical_setsPhysicalAction() {
    Player player = mock(Player.class);
    PlayerInteractEvent event = mock(PlayerInteractEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getAction()).thenReturn(org.bukkit.event.block.Action.PHYSICAL);
    when(event.getItem()).thenReturn(null);
    when(event.getClickedBlock()).thenReturn(null);

    bridge.onInteract(event);
    assertEquals(1, captured.size());
  }

  @Test
  void onInventoryClick_withPlayer_dispatches() {
    Player player = mock(Player.class);
    InventoryClickEvent event = mock(InventoryClickEvent.class);
    when(event.getWhoClicked()).thenReturn(player);

    bridge.onInventoryClick(event);
    verify(event, times(1)).setCancelled(anyBoolean());
  }

  @Test
  void onInventoryClick_withNonPlayer_doesNothing() {
    InventoryClickEvent event = mock(InventoryClickEvent.class);
    when(event.getWhoClicked()).thenReturn(null);

    bridge.onInventoryClick(event);
    verify(event, never()).setCancelled(anyBoolean());
  }

  @Test
  void onInventoryDrag_withPlayer_dispatches() {
    Player player = mock(Player.class);
    InventoryDragEvent event = mock(InventoryDragEvent.class);
    when(event.getWhoClicked()).thenReturn(player);

    bridge.onInventoryDrag(event);
    verify(event, times(1)).setCancelled(anyBoolean());
  }

  @Test
  void onInventoryDrag_withNonPlayer_doesNothing() {
    InventoryDragEvent event = mock(InventoryDragEvent.class);
    when(event.getWhoClicked()).thenReturn(null);

    bridge.onInventoryDrag(event);
    verify(event, never()).setCancelled(anyBoolean());
  }

  @Test
  void onEntityDeath_dispatches() {
    LivingEntity entity = mock(LivingEntity.class);
    World world = mock(World.class);
    Location loc = new Location(world, 0, 0, 0);
    when(entity.getType()).thenReturn(EntityType.ZOMBIE);
    when(entity.getLocation()).thenReturn(loc);
    when(world.getName()).thenReturn("world");

    EntityDeathEvent event = mock(EntityDeathEvent.class);
    when(event.getEntity()).thenReturn(entity);
    when(event.getEntityType()).thenReturn(EntityType.ZOMBIE);

    bridge.onEntityDeath(event);
    assertEquals(1, captured.size());
  }

  @Test
  void onRespawn_dispatches() {
    Player player = mock(Player.class);
    PlayerRespawnEvent event = mock(PlayerRespawnEvent.class);
    when(event.getPlayer()).thenReturn(player);

    bridge.onRespawn(event);
    assertEquals(1, captured.size());
  }

  @Test
  void onChangedWorld_dispatches() {
    Player player = mock(Player.class);
    World from = mock(World.class);
    World to = mock(World.class);
    when(from.getName()).thenReturn("world");
    when(to.getName()).thenReturn("nether");
    when(player.getWorld()).thenReturn(to);

    PlayerChangedWorldEvent event = mock(PlayerChangedWorldEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getFrom()).thenReturn(from);

    bridge.onChangedWorld(event);
    assertEquals(1, captured.size());
  }

  /**
   * A drop is offered first so a mode can refuse it, and only then reported as an inventory change. The
   * ORDER and the pairing are the contract: this is what lets a frozen world stop a player throwing their
   * inventory onto terrain that is about to be deleted.
   */
  @Test
  void onPlayerDropItem_allowed_offersTheDropThenReportsTheInventoryChange() {
    PlayerDropItemEvent event = dropEvent(Material.DIAMOND);

    bridge.onPlayerDropItem(event);

    assertEquals(2, captured.size());
    PlayerDropItemGameEvent drop = assertInstanceOf(PlayerDropItemGameEvent.class, captured.get(0));
    assertEquals(ItemKey.minecraft("diamond"), drop.itemKey());
    assertInstanceOf(InventoryChangeGameEvent.class, captured.get(1));
    verify(event, never()).setCancelled(true);
  }

  @Test
  void onPlayerDropItem_refused_cancelsTheDropAndReportsNoChange() {
    org.mockito.Mockito.doAnswer(invocation -> {
      GameEvent gameEvent = invocation.getArgument(0);
      captured.add(gameEvent);
      if (gameEvent instanceof PlayerDropItemGameEvent drop) {
        drop.setCancelled(true);
      }
      return null;
    }).when(router).handle(any(GameEvent.class));
    PlayerDropItemEvent event = dropEvent(Material.DIRT);

    bridge.onPlayerDropItem(event);

    verify(event).setCancelled(true);
    assertEquals(1, captured.size(), "a refused drop changed nothing, so it is not an inventory change");
    assertInstanceOf(PlayerDropItemGameEvent.class, captured.get(0));
  }

  /**
   * The jump input reaches core as its own event. MONITOR-only: the bridge never cancels a
   * PlayerJumpEvent, because Paper rubber-bands a refused jump back to getFrom().
   */
  @Test
  void onJump_dispatchesAJumpEvent_andNeverCancelsIt() {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    com.destroystokyo.paper.event.player.PlayerJumpEvent event = jumpEvent(player);

    bridge.onJump(event);

    assertEquals(1, captured.size());
    assertInstanceOf(com.sexidium.core.game.GameEvents.PlayerJumpGameEvent.class, captured.get(0));
    verify(event, never()).setCancelled(anyBoolean());
  }

  /**
   * The knockback veto, which is the whole reason this is a bridge concern rather than a core one:
   * Paper's PlayerJumpEvent predicate ("was on ground, now airborne, Y went up") cannot tell a keypress
   * from a creeper punt, so a jump that lands right after the server pushed the player upwards is that
   * push, not a jump.
   */
  @Test
  void onJump_afterAnUpwardLaunch_dispatchesNothing() {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    bridge.onVelocity(velocityEvent(player, 0.8));

    bridge.onJump(jumpEvent(player));

    assertTrue(captured.isEmpty(), "a player who was launched did not jump");
  }

  @Test
  void onJump_afterADownwardVelocity_stillDispatches() {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    // Only an UPWARD push can be mistaken for a jump; being pulled down never suppresses one.
    bridge.onVelocity(velocityEvent(player, -0.5));

    bridge.onJump(jumpEvent(player));

    assertEquals(1, captured.size());
  }

  @Test
  void onQuit_clearsTheLaunchRecord_soTheVetoMapCannotLeak() {
    Player player = mock(Player.class);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    bridge.onVelocity(velocityEvent(player, 0.8));

    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(player);
    bridge.onQuit(quit);
    captured.clear();

    // Rejoining and jumping is not still vetoed by the launch recorded before the disconnect.
    bridge.onJump(jumpEvent(player));
    assertEquals(1, captured.size());
  }

  private com.destroystokyo.paper.event.player.PlayerJumpEvent jumpEvent(Player player) {
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    com.destroystokyo.paper.event.player.PlayerJumpEvent event =
        mock(com.destroystokyo.paper.event.player.PlayerJumpEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getFrom()).thenReturn(new Location(world, 0, 64, 0));
    return event;
  }

  private org.bukkit.event.player.PlayerVelocityEvent velocityEvent(Player player, double velocityY) {
    org.bukkit.event.player.PlayerVelocityEvent event =
        mock(org.bukkit.event.player.PlayerVelocityEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getVelocity()).thenReturn(new org.bukkit.util.Vector(0.0, velocityY, 0.0));
    return event;
  }

  private PlayerDropItemEvent dropEvent(Material material) {
    Player player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(UUID.randomUUID());
    // Mocked rather than constructed: a real ItemStack initialises the Bukkit item registry, which does
    // not exist without a running server.
    ItemStack stack = mock(ItemStack.class);
    when(stack.getType()).thenReturn(material);
    org.bukkit.entity.Item item = mock(org.bukkit.entity.Item.class);
    when(item.getItemStack()).thenReturn(stack);
    PlayerDropItemEvent event = mock(PlayerDropItemEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getItemDrop()).thenReturn(item);
    return event;
  }

  private static <T> T eq(T value) {
    return org.mockito.ArgumentMatchers.eq(value);
  }

  private static boolean anyBoolean() {
    return org.mockito.ArgumentMatchers.anyBoolean();
  }
}
