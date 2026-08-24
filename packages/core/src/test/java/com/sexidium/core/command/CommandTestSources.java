package com.sexidium.core.command;

import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Fake {@link CommandSource} / {@link PlayerAdapter} implementations shared by the
 * {@code CoreCommandService} test suite. Extracted verbatim from the former nested
 * classes of {@code CoreCommandServiceAdvancedTest}.
 */
final class CommandTestSources {
  private CommandTestSources() {}
}

class FakeSource implements CommandSource {
  private final String name;
  private final Set<String> permissions;
  FakeSource(String name, Set<String> permissions) {
    this.name = name;
    this.permissions = permissions;
  }
  @Override public String name() { return name; }
  @Override public Locale locale() { return Locale.ENGLISH; }
  @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
  @Override public void sendMiniMessage(String miniMessage) {}
  @Override public void sendPlainMessage(String message) {}
}

final class FakePlayer extends FakeSource implements PlayerAdapter {
  private final UUID id = UUID.randomUUID();
  private final InventoryAdapter inventory = new FakeInventory();
  FakePlayer(String name, Set<String> permissions) { super(name, permissions); }
  @Override public UUID uniqueId() { return id; }
  @Override public boolean online() { return true; }
  @Override public boolean dead() { return false; }
  @Override public WorldAdapter world() { return null; }
  @Override public WorldPosition position() { return null; }
  @Override public void teleport(WorldPosition targetPosition) {}
  @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
  @Override public void setGameMode(GameModeType gameModeType) {}
  @Override public double health() { return 20.0; }
  @Override public double maxHealth() { return 20.0; }
  @Override public void setHealth(double health) {}
  @Override public int foodLevel() { return 20; }
  @Override public void setFoodLevel(int foodLevel) {}
  @Override public InventoryAdapter inventory() { return inventory; }
  @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
  @Override public void showTitle(TitleSpec titleSpec) {}
  @Override public void sendActionBar(String miniMessage) {}
  @Override public void setCompassTarget(WorldPosition targetPosition) {}
  @Override public void clearInventory() { inventory.clear(); }
  @Override public void clearPotionEffects() {}
}

final class OfflineFakePlayer extends FakeSource implements PlayerAdapter {
  private final UUID id = UUID.randomUUID();
  OfflineFakePlayer(String name, Set<String> permissions) { super(name, permissions); }
  @Override public UUID uniqueId() { return id; }
  @Override public boolean online() { return false; }
  @Override public boolean dead() { return false; }
  @Override public WorldAdapter world() { return null; }
  @Override public WorldPosition position() { return null; }
  @Override public void teleport(WorldPosition targetPosition) {}
  @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
  @Override public void setGameMode(GameModeType gameModeType) {}
  @Override public double health() { return 0.0; }
  @Override public double maxHealth() { return 20.0; }
  @Override public void setHealth(double health) {}
  @Override public int foodLevel() { return 0; }
  @Override public void setFoodLevel(int foodLevel) {}
  @Override public InventoryAdapter inventory() { return null; }
  @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
  @Override public void showTitle(TitleSpec titleSpec) {}
  @Override public void sendActionBar(String miniMessage) {}
  @Override public void setCompassTarget(WorldPosition targetPosition) {}
  @Override public void clearInventory() {}
  @Override public void clearPotionEffects() {}
}
