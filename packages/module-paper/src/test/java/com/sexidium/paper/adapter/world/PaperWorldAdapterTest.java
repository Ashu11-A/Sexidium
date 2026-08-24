package com.sexidium.paper.adapter.world;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaperWorldAdapterTest {

  private World world;
  private Server server;
  private PaperWorldAdapter adapter;
  private MockedStatic<Bukkit> mockedBukkit;

  @BeforeEach
  void setUp() {
    world = mock(World.class);
    server = mock(Server.class);
    when(world.getName()).thenReturn("lobby");
    when(server.getWorld(any(String.class))).thenAnswer(invocation -> {
      String name = invocation.getArgument(0);
      if ("lobby".equals(name)) {
        return world;
      }
      return null;
    });
    mockedBukkit = Mockito.mockStatic(Bukkit.class, Mockito.CALLS_REAL_METHODS);
    mockedBukkit.when(Bukkit::getServer).thenReturn(server);
    adapter = new PaperWorldAdapter(world);
  }

  @AfterEach
  void tearDown() {
    mockedBukkit.close();
  }

  @Test
  void name_returnsWorldName() {
    assertEquals("lobby", adapter.name());
  }

  @Test
  void spawnPosition_returnsConvertedPosition() {
    Location loc = mock(Location.class);
    World spawnWorld = mock(World.class);
    when(spawnWorld.getName()).thenReturn("lobby");
    when(loc.getWorld()).thenReturn(spawnWorld);
    when(loc.getX()).thenReturn(1.0);
    when(loc.getY()).thenReturn(2.0);
    when(loc.getZ()).thenReturn(3.0);
    when(loc.getYaw()).thenReturn(4.0f);
    when(loc.getPitch()).thenReturn(5.0f);
    when(world.getSpawnLocation()).thenReturn(loc);
    WorldPosition position = adapter.spawnPosition();
    assertNotNull(position);
    assertEquals("lobby", position.worldName());
    assertEquals(1.0, position.coordinateX());
  }

  @Test
  void players_mapsAllPlayers() {
    Player player1 = mock(Player.class);
    Player player2 = mock(Player.class);
    when(world.getPlayers()).thenReturn((List) Arrays.asList(player1, player2));
    List<PlayerAdapter> result = (List) adapter.players();
    assertEquals(2, result.size());
  }

  @Test
  void dropItem_convertsAndDrops() {
    WorldPosition pos = new WorldPosition("lobby", 0, 0, 0, 0, 0);
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), 5, Map.of());
    Material stone = mock(Material.class);
    try (MockedStatic<Material> ignored = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS);
         MockedConstruction itemIgnored = Mockito.mockConstruction(
             org.bukkit.inventory.ItemStack.class, (mock, context) -> {})) {
      Mockito.when(Material.matchMaterial("STONE")).thenReturn(stone);
      adapter.dropItem(pos, data);
    }
    verify(world).dropItemNaturally(any(Location.class), any(org.bukkit.inventory.ItemStack.class));
  }

  @Test
  void dropItem_skipsWhenMaterialIsAir() {
    WorldPosition pos = new WorldPosition("lobby", 0, 0, 0, 0, 0);
    ItemStackData data = new ItemStackData(ItemKey.minecraft("air"), 5, Map.of());
    try (MockedStatic<Material> ignored = Mockito.mockStatic(Material.class, Mockito.CALLS_REAL_METHODS)) {
      adapter.dropItem(pos, data);
    }
  }

  @Test
  void dropItem_skipsWhenLocationIsNull() {
    WorldPosition pos = new WorldPosition("nonexistent", 0, 0, 0, 0, 0);
    ItemStackData data = new ItemStackData(ItemKey.minecraft("stone"), 5, Map.of());
    adapter.dropItem(pos, data);
  }

  @Test
  void playSound_invokesWorldPlaySound() {
    WorldPosition pos = new WorldPosition("lobby", 0, 0, 0, 0, 0);
    adapter.playSound(pos, new SoundKey("entity.player.levelup"), 1.0f, 1.0f);
    verify(world).playSound(any(Location.class), eq("entity.player.levelup"), eq(1.0f), eq(1.0f));
  }

  @Test
  void setBorder_appliesSpec() {
    WorldBorder border = mock(WorldBorder.class);
    when(world.getWorldBorder()).thenReturn(border);
    WorldBorderSpec spec = new WorldBorderSpec(10.0, 20.0, 100.0, 5, 0.5);
    adapter.setBorder(spec);
    verify(border).setCenter(10.0, 20.0);
    verify(border).setSize(100.0);
    verify(border).setWarningDistance(5);
    verify(border).setDamageAmount(0.5);
  }

  @Test
  void resetBorder_centersAtSpawnAndSizesToDefault() {
    WorldBorder border = mock(WorldBorder.class);
    when(world.getWorldBorder()).thenReturn(border);
    Location spawn = mock(Location.class);
    when(spawn.getX()).thenReturn(100.0);
    when(spawn.getZ()).thenReturn(200.0);
    when(world.getSpawnLocation()).thenReturn(spawn);
    adapter.resetBorder();
    verify(border).setCenter(100.0, 200.0);
    verify(border).setSize(59_999_968.0);
  }

  @Test
  void loadChunk_invokesAsyncLoad() {
    adapter.loadChunk(5, 10, true);
    verify(world).getChunkAtAsync(5, 10, true, false);
  }

  @Test
  void handle_returnsWorld() {
    assertSame(world, adapter.handle());
  }
}
