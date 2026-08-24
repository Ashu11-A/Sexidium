package com.sexidium.core.game;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.Locale;
import java.util.UUID;

/**
 * Player {@link PlayerAdapter} stubs shared by the {@code AbstractGame} test suite.
 * Extracted verbatim from the former nested helpers of {@code AbstractGameTest}.
 */
final class GameTestPlayers {
  private GameTestPlayers() {}

  static PlayerAdapter stubPlayer(UUID id) {
    return new PlayerAdapter() {
      @Override public UUID uniqueId() { return id; }
      @Override public String name() { return "Player"; }
      @Override public Locale locale() { return Locale.ENGLISH; }
      @Override public boolean hasPermission(String p) { return false; }
      @Override public void sendMiniMessage(String m) {}
      @Override public void sendPlainMessage(String m) {}
      @Override public boolean online() { return true; }
      @Override public boolean dead() { return false; }
      @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
      @Override public WorldPosition position() { return null; }
      @Override public void teleport(WorldPosition p) {}
      @Override public com.sexidium.core.platform.model.GameModeType gameMode() { return com.sexidium.core.platform.model.GameModeType.SURVIVAL; }
      @Override public void setGameMode(com.sexidium.core.platform.model.GameModeType g) {}
      @Override public double health() { return 20; }
      @Override public double maxHealth() { return 20; }
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

  static TrackedPlayer stubPlayerWithHealth(double initial) {
    return new TrackedPlayer(initial);
  }
}

final class TrackedPlayer implements PlayerAdapter {
  private double health;
  private int food;
  TrackedPlayer(double initial) { this.health = initial; this.food = 20; }
  @Override public UUID uniqueId() { return UUID.randomUUID(); }
  @Override public String name() { return "Tracked"; }
  @Override public Locale locale() { return Locale.ENGLISH; }
  @Override public boolean hasPermission(String p) { return false; }
  @Override public void sendMiniMessage(String m) {}
  @Override public void sendPlainMessage(String m) {}
  @Override public boolean online() { return true; }
  @Override public boolean dead() { return false; }
  @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
  @Override public WorldPosition position() { return null; }
  @Override public void teleport(WorldPosition p) {}
  @Override public com.sexidium.core.platform.model.GameModeType gameMode() { return com.sexidium.core.platform.model.GameModeType.SURVIVAL; }
  @Override public void setGameMode(com.sexidium.core.platform.model.GameModeType g) {}
  @Override public double health() { return health; }
  @Override public double maxHealth() { return 20; }
  @Override public void setHealth(double h) { health = h; }
  @Override public int foodLevel() { return food; }
  @Override public void setFoodLevel(int f) { food = f; }
  @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
  @Override public void playSound(com.sexidium.core.platform.model.SoundKey s, float v, float p) {}
  @Override public void showTitle(com.sexidium.core.platform.model.TitleSpec t) {}
  @Override public void sendActionBar(String m) {}
  @Override public void setCompassTarget(WorldPosition p) {}
  @Override public void clearInventory() {}
  @Override public void clearPotionEffects() {}
}
