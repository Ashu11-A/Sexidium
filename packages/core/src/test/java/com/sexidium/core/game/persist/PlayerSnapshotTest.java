package com.sexidium.core.game.persist;

import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.EffectSnapshot;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSnapshotTest {

  @Test
  void constructor_setsFields() {
    UUID id = UUID.randomUUID();
    PlayerSnapshot snap = new PlayerSnapshot(id, "Steve", "fighter");
    assertEquals(id, snap.playerId);
    assertEquals("Steve", snap.playerName);
    assertEquals("fighter", snap.role);
    assertEquals(PlayerSnapshot.Status.CONNECTED, snap.status);
    assertEquals(20.0, snap.health);
    assertEquals(20, snap.foodLevel);
  }

  @Test
  void connected_returnsTrue_whenConnected() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    assertTrue(snap.connected());
  }

  @Test
  void connected_returnsFalse_whenDisconnected() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    snap.status = PlayerSnapshot.Status.DISCONNECTED;
    assertFalse(snap.connected());
  }

  @Test
  void savedPosition_whenNoWorldName_returnsNull() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    assertNull(snap.savedPosition());
  }

  @Test
  void savedPosition_withWorldName_returnsPosition() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    snap.worldName = "world";
    snap.coordinateX = 10.0;
    snap.coordinateY = 64.0;
    snap.coordinateZ = -5.0;
    snap.yaw = 90.0f;
    snap.pitch = 0.0f;

    WorldPosition pos = snap.savedPosition();
    assertNotNull(pos);
    assertEquals("world", pos.worldName());
    assertEquals(10.0, pos.coordinateX());
    assertEquals(64.0, pos.coordinateY());
    assertEquals(-5.0, pos.coordinateZ());
  }

  @Test
  void captureLive_withNull_doesNotCrash() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    assertDoesNotThrow(() -> snap.captureLive(null, null));
  }

  @Test
  void applyTo_withNull_returnsNull() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    assertNull(snap.applyTo(null, null));
  }

  @Test
  void data_props_isReadWrite() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    snap.data.set("team", "red");
    assertEquals("red", snap.data.get("team"));
  }

  @Test
  void status_enum_hasTwoValues() {
    assertEquals(2, PlayerSnapshot.Status.values().length);
  }

  @Test
  void defaultGameMode_isSurvival() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    assertEquals(GameModeType.SURVIVAL.name(), snap.gameMode);
  }

  @Test
  void defaultRecoveryFields_areEmpty() {
    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "n", "r");
    assertEquals(0, snap.xp);
    assertTrue(snap.effects.isEmpty());
  }

  @Test
  void captureLive_capturesXpAndEffects() {
    FakePlayer player = new FakePlayer();
    player.xp = 1234;
    player.effects = List.of(new EffectSnapshot("speed", 1, 200), new EffectSnapshot("regeneration", 0, 100));

    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "Steve", null);
    snap.captureLive(player, null);

    assertEquals(1234, snap.xp);
    assertEquals(2, snap.effects.size());
    assertEquals("speed", snap.effects.get(0).effectKey());
    assertEquals(200, snap.effects.get(0).durationTicks());
  }

  @Test
  void applyTo_restoresXpAndClearsThenReAddsEffects() {
    FakePlayer player = new FakePlayer();

    PlayerSnapshot snap = new PlayerSnapshot(UUID.randomUUID(), "Steve", null);
    snap.xp = 500;
    snap.effects = List.of(new EffectSnapshot("strength", 2, 300));

    snap.applyTo(player, null);

    assertEquals(500, player.restoredXp);
    assertTrue(player.clearedEffects, "live effects must be cleared before re-adding the saved set");
    assertEquals(1, player.addedEffects.size());
    assertEquals("strength", player.addedEffects.get(0).effectKey());
    assertEquals(2, player.addedEffects.get(0).amplifier());
    assertEquals(300, player.addedEffects.get(0).durationTicks());
  }

  /** Records the recovery-state writes and serves a fixed effect set / xp for capture. */
  private static final class FakePlayer implements PlayerAdapter {
    int xp;
    List<EffectSnapshot> effects = List.of();

    int restoredXp = -1;
    boolean clearedEffects;
    final List<EffectSnapshot> addedEffects = new ArrayList<>();

    @Override public int experiencePoints() { return xp; }
    @Override public List<EffectSnapshot> activeEffects() { return effects; }
    @Override public void setExperiencePoints(int points) { restoredXp = points; }
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() { clearedEffects = true; }
    @Override public void addEffect(String effectKey, int amplifier, int durationTicks) {
      addedEffects.add(new EffectSnapshot(effectKey, amplifier, durationTicks));
    }

    @Override public UUID uniqueId() { return new UUID(0L, 0L); }
    @Override public String name() { return "Tester"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return new WorldPosition("world", 0, 64, 0, 0f, 0f); }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public double health() { return 20.0; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double health) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
  }
}
