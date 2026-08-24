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
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperConvertersTest {

  @Test
  void toCore_withNullLocation_returnsNull() {
    assertNull(PaperConverters.toCore((Location) null));
  }

  @Test
  void toCore_withNullWorld_returnsNull() {
    Location loc = mock(Location.class);
    when(loc.getWorld()).thenReturn(null);
    assertNull(PaperConverters.toCore(loc));
  }

  @Test
  void toCore_returnsWorldPosition() {
    World world = mock(World.class);
    when(world.getName()).thenReturn("world");
    Location loc = new Location(world, 1, 2, 3, 4, 5);
    WorldPosition pos = PaperConverters.toCore(loc);
    assertEquals("world", pos.worldName());
    assertEquals(1, pos.coordinateX());
    assertEquals(2, pos.coordinateY());
    assertEquals(3, pos.coordinateZ());
    assertEquals(4, pos.yaw());
    assertEquals(5, pos.pitch());
  }

  @Test
  void toNative_withNullPosition_returnsNull() {
    assertNull(PaperConverters.toNative((WorldPosition) null));
  }

  @Test
  void toNative_withUnknownWorld_returnsNull() {
    Server server = mock(Server.class);
    when(server.getWorld("nowhere")).thenReturn(null);
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      assertNull(PaperConverters.toNative(new WorldPosition("nowhere", 0, 0, 0, 0, 0)));
    }
  }

  @Test
  void toNative_returnsLocation() {
    Server server = mock(Server.class);
    World world = mock(World.class);
    when(server.getWorld("world")).thenReturn(world);
    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS)) {
      mockedBukkit.when(Bukkit::getServer).thenReturn(server);
      Location loc = PaperConverters.toNative(new WorldPosition("world", 1, 2, 3, 4, 5));
      assertNotNull(loc);
      assertEquals(1, loc.getX());
      assertEquals(2, loc.getY());
      assertEquals(3, loc.getZ());
      assertEquals(4, loc.getYaw());
      assertEquals(5, loc.getPitch());
    }
  }

  @Test
  void toCore_withNullGameMode_returnsSurvival() {
    assertEquals(GameModeType.SURVIVAL, PaperConverters.toCore((GameMode) null));
  }

  @Test
  void toCore_convertsAllGameModes() {
    assertEquals(GameModeType.CREATIVE, PaperConverters.toCore(GameMode.CREATIVE));
    assertEquals(GameModeType.ADVENTURE, PaperConverters.toCore(GameMode.ADVENTURE));
    assertEquals(GameModeType.SPECTATOR, PaperConverters.toCore(GameMode.SPECTATOR));
    assertEquals(GameModeType.SURVIVAL, PaperConverters.toCore(GameMode.SURVIVAL));
  }

  @Test
  void toNative_withNullGameModeType_returnsSurvival() {
    assertEquals(GameMode.SURVIVAL, PaperConverters.toNative((GameModeType) null));
  }

  @Test
  void toNative_convertsAllGameModeTypes() {
    assertEquals(GameMode.CREATIVE, PaperConverters.toNative(GameModeType.CREATIVE));
    assertEquals(GameMode.ADVENTURE, PaperConverters.toNative(GameModeType.ADVENTURE));
    assertEquals(GameMode.SPECTATOR, PaperConverters.toNative(GameModeType.SPECTATOR));
    assertEquals(GameMode.SURVIVAL, PaperConverters.toNative(GameModeType.SURVIVAL));
  }

  @Test
  void toCore_withNullMaterial_returnsAir() {
    ItemKey key = PaperConverters.toCore((Material) null);
    assertEquals("air", key.value());
  }

  @Test
  void toCore_convertsMaterialToLowercase() {
    ItemKey key = PaperConverters.toCore(Material.STONE);
    assertEquals("stone", key.value());
  }

  @Test
  void toNative_withNullItemKey_returnsAir() {
    assertEquals(Material.AIR, PaperConverters.toNative((ItemKey) null));
  }

  @Test
  void toNative_convertsItemKey() {
    assertEquals(Material.STONE, PaperConverters.toNative(ItemKey.minecraft("stone")));
  }

  @Test
  void toNative_withUnknownItemKey_returnsAir() {
    assertEquals(Material.AIR, PaperConverters.toNative(ItemKey.minecraft("not_a_real_material_xyz")));
  }

  @Test
  void toNative_BossBarColor_withNull_returnsWhite() {
    assertEquals(BossBar.Color.WHITE, PaperConverters.toNative((BossBarColor) null));
  }

  @Test
  void toNative_BossBarColor_convertsAll() {
    assertEquals(BossBar.Color.PINK, PaperConverters.toNative(BossBarColor.PINK));
    assertEquals(BossBar.Color.BLUE, PaperConverters.toNative(BossBarColor.BLUE));
    assertEquals(BossBar.Color.RED, PaperConverters.toNative(BossBarColor.RED));
    assertEquals(BossBar.Color.GREEN, PaperConverters.toNative(BossBarColor.GREEN));
    assertEquals(BossBar.Color.YELLOW, PaperConverters.toNative(BossBarColor.YELLOW));
    assertEquals(BossBar.Color.PURPLE, PaperConverters.toNative(BossBarColor.PURPLE));
    assertEquals(BossBar.Color.WHITE, PaperConverters.toNative(BossBarColor.WHITE));
  }

  @Test
  void toNative_BossBarOverlay_withNull_returnsProgress() {
    assertEquals(BossBar.Overlay.PROGRESS, PaperConverters.toNative((BossBarOverlay) null));
  }

  @Test
  void toNative_BossBarOverlay_convertsAll() {
    assertEquals(BossBar.Overlay.NOTCHED_6, PaperConverters.toNative(BossBarOverlay.NOTCHED_6));
    assertEquals(BossBar.Overlay.NOTCHED_10, PaperConverters.toNative(BossBarOverlay.NOTCHED_10));
    assertEquals(BossBar.Overlay.NOTCHED_12, PaperConverters.toNative(BossBarOverlay.NOTCHED_12));
    assertEquals(BossBar.Overlay.NOTCHED_20, PaperConverters.toNative(BossBarOverlay.NOTCHED_20));
    assertEquals(BossBar.Overlay.PROGRESS, PaperConverters.toNative(BossBarOverlay.PROGRESS));
  }

  @Test
  void isPlayerOnline_withNull_returnsFalse() {
    assertFalse(PaperConverters.isPlayerOnline(null));
  }

  @Test
  void isPlayerOnline_withOfflinePlayer_returnsFalse() {
    Player player = mock(Player.class);
    when(player.isOnline()).thenReturn(false);
    assertFalse(PaperConverters.isPlayerOnline(player));
  }

  @Test
  void isPlayerOnline_withOnlinePlayer_returnsTrue() {
    Player player = mock(Player.class);
    when(player.isOnline()).thenReturn(true);
    assertTrue(PaperConverters.isPlayerOnline(player));
  }
}
