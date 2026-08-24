package com.sexidium.core.game;
import com.sexidium.core.game.GameEvents.*;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.DamageCauseType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GameEventsTest {

  private PlayerAdapter mockPlayer(String name) {
    return new PlayerAdapter() {
      @Override public UUID uniqueId() { return UUID.randomUUID(); }
      @Override public String name() { return name; }
      @Override public Locale locale() { return Locale.ENGLISH; }
      @Override public boolean hasPermission(String p) { return true; }
      @Override public void sendMiniMessage(String m) {}
      @Override public void sendPlainMessage(String m) {}
      @Override public boolean online() { return true; }
      @Override public boolean dead() { return false; }
      @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
      @Override public WorldPosition position() { return null; }
      @Override public void teleport(WorldPosition p) {}
      @Override public com.sexidium.core.platform.model.GameModeType gameMode() { return com.sexidium.core.platform.model.GameModeType.SURVIVAL; }
      @Override public void setGameMode(com.sexidium.core.platform.model.GameModeType g) {}
      @Override public double health() { return 20.0; }
      @Override public double maxHealth() { return 20.0; }
      @Override public void setHealth(double h) {}
      @Override public int foodLevel() { return 20; }
      @Override public void setFoodLevel(int f) {}
      @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
      @Override public void playSound(com.sexidium.core.platform.model.SoundKey s, float v, float p) {}
      @Override public void showTitle(com.sexidium.core.platform.model.TitleSpec t) {}
      @Override public void sendActionBar(String m) {}
      @Override public void setCompassTarget(WorldPosition p) {}
      @Override public void clearInventory() {}
      @Override public void clearPotionEffects() {}
    };
  }

  // ---- PlayerJoinGameEvent ----
  @Test
  void playerJoinEvent_holdsPlayer() {
    PlayerAdapter player = mockPlayer("Steve");
    PlayerJoinGameEvent event = new PlayerJoinGameEvent(player);
    assertSame(player, event.playerAdapter());
  }

  // ---- PlayerQuitGameEvent ----
  @Test
  void playerQuitEvent_holdsPlayer() {
    PlayerAdapter player = mockPlayer("Alex");
    PlayerQuitGameEvent event = new PlayerQuitGameEvent(player);
    assertSame(player, event.playerAdapter());
  }

  // ---- EntityDeathGameEvent ----
  @Test
  void entityDeathEvent_holdsTypeAndPosition() {
    WorldPosition pos = new WorldPosition("world", 1, 64, 1, 0f, 0f);
    EntityDeathGameEvent event = new EntityDeathGameEvent("minecraft:zombie", pos);
    assertEquals("minecraft:zombie", event.entityType());
    assertEquals(pos, event.deathPosition());
  }

  // ---- PlayerDamageGameEvent ----
  @Test
  void playerDamageEvent_holdsFields() {
    PlayerAdapter victim = mockPlayer("A");
    PlayerAdapter attacker = mockPlayer("B");
    PlayerDamageGameEvent event = new PlayerDamageGameEvent(victim, attacker, DamageCauseType.ENTITY_ATTACK, 4.0);
    assertSame(victim, event.victim());
    assertSame(attacker, event.attacker());
    assertEquals(DamageCauseType.ENTITY_ATTACK, event.damageCauseType());
    assertEquals(4.0, event.finalDamage());
  }

  @Test
  void playerDamageEvent_negativeDamage_clampedToZero() {
    PlayerDamageGameEvent event = new PlayerDamageGameEvent(mockPlayer("A"), null, DamageCauseType.FALL, -5.0);
    assertEquals(0.0, event.finalDamage());
  }

  @Test
  void playerDamageEvent_nullDamageCause_defaultsToUnknown() {
    PlayerDamageGameEvent event = new PlayerDamageGameEvent(mockPlayer("A"), null, null, 1.0);
    assertEquals(DamageCauseType.UNKNOWN, event.damageCauseType());
  }

  @Test
  void playerDamageEvent_isCancellable() {
    PlayerDamageGameEvent event = new PlayerDamageGameEvent(mockPlayer("A"), null, DamageCauseType.FALL, 1.0);
    assertFalse(event.cancelled());
    event.setCancelled(true);
    assertTrue(event.cancelled());
    event.setCancelled(false);
    assertFalse(event.cancelled());
  }

  // ---- PlayerMoveGameEvent ----
  @Test
  void playerMoveEvent_holdsPositions() {
    WorldPosition from = new WorldPosition("world", 0, 64, 0, 0f, 0f);
    WorldPosition to = new WorldPosition("world", 1, 64, 0, 0f, 0f);
    PlayerMoveGameEvent event = new PlayerMoveGameEvent(mockPlayer("X"), from, to);
    assertEquals(from, event.fromPosition());
    assertEquals(to, event.toPosition());
    assertFalse(event.cancelled());
  }

  // ---- BlockBreakGameEvent ----
  @Test
  void blockBreakEvent_holdsFields() {
    PlayerAdapter player = mockPlayer("Miner");
    BlockPosition pos = new BlockPosition("world", 5, 60, 5);
    ItemKey key = ItemKey.minecraft("STONE");
    BlockBreakGameEvent event = new BlockBreakGameEvent(player, pos, key);
    assertSame(player, event.playerAdapter());
    assertEquals(pos, event.blockPosition());
    assertEquals(key, event.blockKey());
    assertTrue(event.dropItems());
  }

  @Test
  void blockBreakEvent_setDropItems() {
    BlockBreakGameEvent event = new BlockBreakGameEvent(mockPlayer("P"), new BlockPosition("w", 0, 0, 0), ItemKey.minecraft("dirt"));
    event.setDropItems(false);
    assertFalse(event.dropItems());
  }

  @Test
  void blockBreakEvent_cancellation() {
    BlockBreakGameEvent event = new BlockBreakGameEvent(mockPlayer("P"), new BlockPosition("w", 0, 0, 0), ItemKey.minecraft("dirt"));
    assertFalse(event.cancelled());
    event.setCancelled(true);
    assertTrue(event.cancelled());
  }

  // ---- BlockPlaceGameEvent ----
  @Test
  void blockPlaceEvent_holdsFields() {
    PlayerAdapter player = mockPlayer("Builder");
    BlockPosition pos = new BlockPosition("world", 1, 65, 1);
    ItemKey key = ItemKey.minecraft("STONE");
    BlockPlaceGameEvent event = new BlockPlaceGameEvent(player, pos, key);
    assertSame(player, event.playerAdapter());
    assertEquals(pos, event.blockPosition());
    assertEquals(key, event.blockKey());
    assertFalse(event.cancelled());
  }

  // ---- InventoryChangeGameEvent ----
  @Test
  void inventoryChangeEvent_holdsPlayer() {
    PlayerAdapter player = mockPlayer("Inv");
    InventoryChangeGameEvent event = new InventoryChangeGameEvent(player);
    assertSame(player, event.playerAdapter());
    assertFalse(event.cancelled());
  }

  // ---- PlayerInteractGameEvent ----
  @Test
  void playerInteractEvent_holdsFields() {
    PlayerAdapter player = mockPlayer("Clicker");
    BlockPosition pos = new BlockPosition("world", 2, 63, 2);
    ItemKey item = ItemKey.minecraft("STICK");
    PlayerInteractGameEvent event = new PlayerInteractGameEvent(
        player, PlayerInteractGameEvent.ActionType.RIGHT_CLICK, item, pos);
    assertSame(player, event.playerAdapter());
    assertEquals(PlayerInteractGameEvent.ActionType.RIGHT_CLICK, event.actionType());
    assertEquals(item, event.itemKey());
    assertEquals(pos, event.blockPosition());
  }

  @Test
  void playerInteractEvent_nullActionType_defaultsToUnknown() {
    PlayerInteractGameEvent event = new PlayerInteractGameEvent(mockPlayer("P"), null, null, null);
    assertEquals(PlayerInteractGameEvent.ActionType.UNKNOWN, event.actionType());
  }

  @Test
  void playerInteractEvent_allActionTypes_exist() {
    assertEquals(4, PlayerInteractGameEvent.ActionType.values().length);
  }
}
