package com.sexidium.paper.adapter.player;

import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperPlayerAdapterTest {

  private Player player;
  private Server server;
  private PaperPlayerAdapter adapter;

  @BeforeEach
  void setUp() {
    player = mock(Player.class);
    server = mock(Server.class);
    when(player.getServer()).thenReturn(server);
    adapter = new PaperPlayerAdapter(player);
  }

  @Test
  void handle_returnsPlayer() {
    assertSame(player, adapter.handle());
  }

  @Test
  void uniqueId_delegatesToPlayer() {
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    assertEquals(id, adapter.uniqueId());
  }

  @Test
  void locale_delegatesToPlayer() {
    when(player.locale()).thenReturn(Locale.GERMAN);
    assertEquals(Locale.GERMAN, adapter.locale());
  }

  @Test
  void online_delegatesToPlayer() {
    when(player.isOnline()).thenReturn(true);
    assertTrue(adapter.online());
    when(player.isOnline()).thenReturn(false);
    assertFalse(adapter.online());
  }

  @Test
  void dead_delegatesToPlayer() {
    when(player.isDead()).thenReturn(true);
    assertTrue(adapter.dead());
  }

  @Test
  void world_returnsWorldAdapter() {
    World world = mock(World.class);
    when(player.getWorld()).thenReturn(world);
    assertNotNull(adapter.world());
  }

  @Test
  void position_returnsPositionFromLocation() {
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    when(player.getLocation()).thenReturn(new Location(world, 1, 2, 3, 4, 5));
    var pos = adapter.position();
    assertEquals("world", pos.worldName());
    assertEquals(1, pos.coordinateX());
    assertEquals(2, pos.coordinateY());
    assertEquals(3, pos.coordinateZ());
    assertEquals(4, pos.yaw());
    assertEquals(5, pos.pitch());
  }

  @Test
  void teleport_teleportsToValidLocation() {
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    when(server.getWorld("world")).thenReturn(world);
    WorldPosition target = new WorldPosition("world", 1, 2, 3, 0, 0);
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.teleport(target);
    }
    verify(player).teleportAsync(any(Location.class));
  }

  @Test
  void teleport_withUnknownWorld_doesNothing() {
    WorldPosition target = new WorldPosition("nowhere", 1, 2, 3, 0, 0);
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.teleport(target);
    }
    verify(player, never()).teleportAsync(any(Location.class));
  }

  @Test
  void teleport_withNullTarget_doesNothing() {
    adapter.teleport(null);
    verify(player, never()).teleportAsync(any(Location.class));
  }

  @Test
  void gameMode_returnsCoreEnum() {
    when(player.getGameMode()).thenReturn(GameMode.CREATIVE);
    assertEquals(GameModeType.CREATIVE, adapter.gameMode());
  }

  @Test
  void setGameMode_invokesPlayer() {
    adapter.setGameMode(GameModeType.SURVIVAL);
    verify(player).setGameMode(GameMode.SURVIVAL);
  }

  @Test
  void health_returnsHealth() {
    when(player.getHealth()).thenReturn(15.0);
    assertEquals(15.0, adapter.health());
  }

  @Test
  void setFoodLevel_clampsToZero() {
    adapter.setFoodLevel(-5);
    verify(player).setFoodLevel(0);
  }

  @Test
  void setFoodLevel_clampsToTwenty() {
    adapter.setFoodLevel(50);
    verify(player).setFoodLevel(20);
  }

  @Test
  void inventory_returnsInventoryAdapter() {
    PlayerInventory inv = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inv);
    assertNotNull(adapter.inventory());
  }

  @Test
  void playSound_invokesPlayerPlaySound() {
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    Location loc = new Location(world, 0, 0, 0);
    when(player.getLocation()).thenReturn(loc);
    adapter.playSound(new SoundKey("ambient.cave"), 1.0f, 1.0f);
    verify(player).playSound(any(Location.class), any(String.class), anyFloat(), anyFloat());
  }

  @Test
  void showTitle_deserializesAndShows() {
    TitleSpec spec = new TitleSpec("Hello", "World", 100, 1000, 100);
    adapter.showTitle(spec);
    verify(player).showTitle(any(net.kyori.adventure.title.Title.class));
  }

  @Test
  void showTitle_withNulls_sendsEmpty() {
    TitleSpec spec = new TitleSpec(null, null, 100, 1000, 100);
    adapter.showTitle(spec);
    verify(player).showTitle(any(net.kyori.adventure.title.Title.class));
  }

  @Test
  void sendActionBar_invokesPlayer() {
    adapter.sendActionBar("hello");
    verify(player).sendActionBar(any(net.kyori.adventure.text.Component.class));
  }

  @Test
  void sendActionBar_withNull_sendsEmpty() {
    adapter.sendActionBar(null);
    verify(player).sendActionBar(any(net.kyori.adventure.text.Component.class));
  }

  @Test
  void setCompassTarget_setsValidLocation() {
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    when(server.getWorld("world")).thenReturn(world);
    WorldPosition target = new WorldPosition("world", 1, 2, 3, 0, 0);
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.setCompassTarget(target);
    }
    verify(player).setCompassTarget(any(Location.class));
  }

  @Test
  void setCompassTarget_withUnknownWorld_doesNothing() {
    WorldPosition target = new WorldPosition("nowhere", 1, 2, 3, 0, 0);
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      adapter.setCompassTarget(target);
    }
    verify(player, never()).setCompassTarget(any(Location.class));
  }

  @Test
  void clearInventory_clearsArmorAndExtra() {
    PlayerInventory inv = mock(PlayerInventory.class);
    when(player.getInventory()).thenReturn(inv);
    adapter.clearInventory();
    verify(inv).clear();
  }

  @Test
  void clearPotionEffects_invokesPlayer() {
    adapter.clearPotionEffects();
    verify(player).clearActivePotionEffects();
  }

  @Test
  void foodLevel_returnsLevel() {
    when(player.getFoodLevel()).thenReturn(10);
    assertEquals(10, adapter.foodLevel());
  }

  @Test
  void clearTitle_clearsTitleAndActionBar() {
    adapter.clearTitle();
    verify(player).clearTitle();
    verify(player).sendActionBar(any(net.kyori.adventure.text.Component.class));
  }

  @Test
  void resetCompass_pointsAtWorldSpawn() {
    World world = mock(World.class);
    Location spawn = mock(Location.class);
    when(player.getWorld()).thenReturn(world);
    when(world.getSpawnLocation()).thenReturn(spawn);
    adapter.resetCompass();
    verify(player).setCompassTarget(spawn);
  }

  @Test
  void clearBossBars_hidesEveryActiveBar() {
    net.kyori.adventure.bossbar.BossBar firstBar = net.kyori.adventure.bossbar.BossBar.bossBar(
        net.kyori.adventure.text.Component.empty(), 1.0f,
        net.kyori.adventure.bossbar.BossBar.Color.RED,
        net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
    net.kyori.adventure.bossbar.BossBar secondBar = net.kyori.adventure.bossbar.BossBar.bossBar(
        net.kyori.adventure.text.Component.empty(), 0.5f,
        net.kyori.adventure.bossbar.BossBar.Color.BLUE,
        net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
    org.mockito.Mockito.doReturn(java.util.List.of(firstBar, secondBar)).when(player).activeBossBars();
    adapter.clearBossBars();
    verify(player).hideBossBar(firstBar);
    verify(player).hideBossBar(secondBar);
  }

  private static float anyFloat() {
    return org.mockito.ArgumentMatchers.anyFloat();
  }
}
