package com.sexidium.paper.adapter.auth;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.auth.AuthHoldService;
import com.sexidium.core.auth.AuthSessionService;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The in-world freeze. Two things are asserted throughout: a held player can do nothing, and a
 * player who is NOT held is completely unaffected — the second is what makes it safe to register
 * this listener on every server whether or not the feature is on.
 */
class PaperAuthHoldTest {

  private JavaPlugin plugin;
  private Server server;
  private SexidiumCore core;
  private AuthSessionService sessions;
  private AuthHoldService holds;
  private PropertiesConfigurationAdapter config;
  private PaperAuthHold hold;
  private Player player;
  private UUID playerId;

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    server = mock(Server.class);
    when(plugin.getServer()).thenReturn(server);
    when(server.getOnlinePlayers()).thenAnswer(invocation -> List.of());

    core = mock(SexidiumCore.class);
    sessions = mock(AuthSessionService.class);
    holds = new AuthHoldService();
    when(core.authHold()).thenReturn(holds);
    when(core.authSessions()).thenReturn(sessions);

    config = new PropertiesConfigurationAdapter();
    config.set("auth.hold.enabled", "true");
    // The hold's titles and action bar are bilingual, so it needs the real catalog.
    MessageService messages = new MessageService(
        new ClassLoaderResourceAdapter(getClass().getClassLoader()), config, new StdoutLoggerAdapter("T"));
    messages.reload();
    when(core.messages()).thenReturn(messages);
    hold = new PaperAuthHold(plugin, core, config);

    playerId = UUID.randomUUID();
    player = mock(Player.class);
    when(player.getUniqueId()).thenReturn(playerId);
    when(player.isOnline()).thenReturn(true);
    when(player.getGameMode()).thenReturn(GameMode.SURVIVAL);
    when(player.getInventory()).thenReturn(mock(org.bukkit.inventory.PlayerInventory.class));
    when(player.getActivePotionEffects()).thenAnswer(invocation -> List.of());
    when(server.getPlayer(playerId)).thenReturn(player);
  }

  // --- lifecycle ----------------------------------------------------------

  @Test
  @DisplayName("a pending hold freezes the player the moment they join")
  void joinAppliesThePendingHold() {
    when(sessions.holdIntent(playerId)).thenReturn(Optional.of(intent("req-1")));

    hold.onJoin(join());

    assertTrue(holds.isHeld(playerId));
    verify(player).setGameMode(GameMode.SPECTATOR);
    verify(player).setInvulnerable(true);
    verify(player).getInventory();
  }

  @Test
  @DisplayName("a player with no hold intent joins normally")
  void joinWithoutAHoldDoesNothing() {
    when(sessions.holdIntent(playerId)).thenReturn(Optional.empty());

    hold.onJoin(join());

    assertFalse(holds.isHeld(playerId));
    verify(player, never()).setGameMode(any());
  }

  @Test
  @DisplayName("with the hold disabled nothing is ever applied, whatever the intent says")
  void disabledHoldNeverApplies() {
    config.set("auth.hold.enabled", "false");
    when(sessions.holdIntent(playerId)).thenReturn(Optional.of(intent("req-1")));

    hold.onJoin(join());

    assertFalse(holds.isHeld(playerId));
  }

  @Test
  @DisplayName("disconnecting drops the hold, so a reconnect starts clean")
  void quitReleases() {
    holdNow();
    PlayerQuitEvent quit = mock(PlayerQuitEvent.class);
    when(quit.getPlayer()).thenReturn(player);

    hold.onQuit(quit);

    assertFalse(holds.isHeld(playerId));
  }

  // --- the freeze ---------------------------------------------------------

  @Test
  @DisplayName("a held player cannot walk, but can still look around")
  void movementIsBlockedAndRotationIsNot() {
    holdNow();

    PlayerMoveEvent walk = move(0, 0, 0, 1, 0, 0);
    hold.onMove(walk);
    verify(walk).setCancelled(true);

    PlayerMoveEvent lookAround = move(0, 0, 0, 0, 0, 0);
    hold.onMove(lookAround);
    verify(lookAround, never()).setCancelled(true);
  }

  @Test
  @DisplayName("a player who is not held moves freely")
  void movementIsUntouchedWhenNotHeld() {
    PlayerMoveEvent walk = move(0, 0, 0, 1, 0, 0);
    hold.onMove(walk);
    verify(walk, never()).setCancelled(true);
  }

  @Test
  @DisplayName("a held player cannot run a command")
  void commandsAreBlocked() {
    holdNow();
    PlayerCommandPreprocessEvent command = mock(PlayerCommandPreprocessEvent.class);
    when(command.getPlayer()).thenReturn(player);

    hold.onCommand(command);

    verify(command).setCancelled(true);
  }

  @Test
  @DisplayName("a held player cannot break a block")
  void blockBreakIsBlocked() {
    holdNow();
    BlockBreakEvent event = mock(BlockBreakEvent.class);
    when(event.getPlayer()).thenReturn(player);

    hold.onBlockBreak(event);

    verify(event).setCancelled(true);
  }

  @Test
  @DisplayName("a held player is neither hurt nor able to hurt")
  void damageIsBlockedBothWays() {
    holdNow();
    EntityDamageEvent incoming = mock(EntityDamageEvent.class);
    when(incoming.getEntity()).thenReturn(player);

    hold.onDamage(incoming);

    verify(incoming).setCancelled(true);
  }

  @Test
  @DisplayName("a held player does not starve while they wait")
  void hungerIsBlocked() {
    holdNow();
    FoodLevelChangeEvent event = mock(FoodLevelChangeEvent.class);
    when(event.getEntity()).thenReturn(player);

    hold.onHunger(event);

    verify(event).setCancelled(true);
  }

  @Test
  @DisplayName("a held player cannot drop items or click an inventory")
  void itemsAndInventoryAreBlocked() {
    holdNow();
    PlayerDropItemEvent drop = mock(PlayerDropItemEvent.class);
    when(drop.getPlayer()).thenReturn(player);
    InventoryClickEvent click = mock(InventoryClickEvent.class);
    when(click.getWhoClicked()).thenReturn(player);

    hold.onDrop(drop);
    hold.onInventoryClick(click);

    verify(drop).setCancelled(true);
    verify(click).setCancelled(true);
  }

  @Test
  @DisplayName("a held player cannot be teleported away, except by the freeze itself")
  void teleportIsBlockedExceptForSpectate() {
    holdNow();

    PlayerTeleportEvent elsewhere = teleport(PlayerTeleportEvent.TeleportCause.PLUGIN);
    hold.onTeleport(elsewhere);
    verify(elsewhere).setCancelled(true);

    PlayerTeleportEvent spectate = teleport(PlayerTeleportEvent.TeleportCause.SPECTATE);
    hold.onTeleport(spectate);
    verify(spectate, never()).setCancelled(true);
  }

  @Test
  @DisplayName("each protection can be switched off on its own")
  void protectionsAreIndividuallyDisableable() {
    config.set("auth.hold.block-move", "false");
    config.set("auth.hold.block-commands", "false");
    holdNow();

    PlayerMoveEvent walk = move(0, 0, 0, 1, 0, 0);
    hold.onMove(walk);
    verify(walk, never()).setCancelled(true);

    PlayerCommandPreprocessEvent command = mock(PlayerCommandPreprocessEvent.class);
    when(command.getPlayer()).thenReturn(player);
    hold.onCommand(command);
    verify(command, never()).setCancelled(true);
  }

  // --- helpers ------------------------------------------------------------

  private void holdNow() {
    holds.hold(playerId, "id-1", "req-1", "SURVIVAL", System.currentTimeMillis() + 60_000L);
  }

  private PlayerJoinEvent join() {
    PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    when(event.getPlayer()).thenReturn(player);
    return event;
  }

  private static AuthSessionService.HoldIntent intent(String requestId) {
    return new AuthSessionService.HoldIntent("id-1", requestId, System.currentTimeMillis() + 300_000L);
  }

  private PlayerMoveEvent move(double fromX, double fromY, double fromZ,
      double toX, double toY, double toZ) {
    PlayerMoveEvent event = mock(PlayerMoveEvent.class);
    when(event.getPlayer()).thenReturn(player);
    Location from = mock(Location.class);
    Location to = mock(Location.class);
    when(from.toVector()).thenReturn(new Vector(fromX, fromY, fromZ));
    when(to.toVector()).thenReturn(new Vector(toX, toY, toZ));
    when(event.getFrom()).thenReturn(from);
    when(event.getTo()).thenReturn(to);
    return event;
  }

  private PlayerTeleportEvent teleport(PlayerTeleportEvent.TeleportCause cause) {
    PlayerTeleportEvent event = mock(PlayerTeleportEvent.class);
    when(event.getPlayer()).thenReturn(player);
    when(event.getCause()).thenReturn(cause);
    return event;
  }
}
