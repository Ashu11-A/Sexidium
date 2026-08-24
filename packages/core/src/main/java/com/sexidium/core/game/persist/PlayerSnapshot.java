package com.sexidium.core.game.persist;

import com.sexidium.core.platform.InventorySerializer;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.EffectSnapshot;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.List;
import java.util.UUID;

public final class PlayerSnapshot {
  public enum Status {
    CONNECTED,
    DISCONNECTED
  }

  public final UUID playerId;
  public String playerName;
  public String role;
  public Status status = Status.CONNECTED;
  public String worldName;
  public double coordinateX;
  public double coordinateY;
  public double coordinateZ;
  public float yaw;
  public float pitch;
  public String gameMode = GameModeType.SURVIVAL.name();
  public double health = 20.0;
  public int foodLevel = 20;
  public int xp;
  public String inventoryPayload;
  public List<EffectSnapshot> effects = List.of();
  public final Props data = new Props();

  public PlayerSnapshot(UUID playerId, String playerName, String role) {
    this.playerId = playerId;
    this.playerName = playerName;
    this.role = role;
  }

  public void captureLive(PlayerAdapter playerAdapter, InventorySerializer inventorySerializer) {
    if (playerAdapter == null) {
      return;
    }
    playerName = playerAdapter.name();
    WorldPosition position = playerAdapter.position();
    if (position != null) {
      worldName = position.worldName();
      coordinateX = position.coordinateX();
      coordinateY = position.coordinateY();
      coordinateZ = position.coordinateZ();
      yaw = position.yaw();
      pitch = position.pitch();
    }
    gameMode = playerAdapter.gameMode().name();
    health = playerAdapter.health();
    foodLevel = playerAdapter.foodLevel();
    xp = playerAdapter.experiencePoints();
    effects = List.copyOf(playerAdapter.activeEffects());
    if (inventorySerializer != null) {
      inventoryPayload = inventorySerializer.serialize(playerAdapter);
    }
  }

  public WorldPosition applyTo(PlayerAdapter playerAdapter, InventorySerializer inventorySerializer) {
    if (playerAdapter == null) {
      return null;
    }
    try {
      playerAdapter.setGameMode(GameModeType.valueOf(gameMode));
    } catch (RuntimeException exception) {
      playerAdapter.setGameMode(GameModeType.SURVIVAL);
    }
    if (inventorySerializer != null && inventoryPayload != null && !inventoryPayload.isBlank()) {
      inventorySerializer.deserializeInto(playerAdapter, inventoryPayload);
    }
    playerAdapter.setHealth(Math.max(1.0, Math.min(playerAdapter.maxHealth(), health)));
    playerAdapter.setFoodLevel(Math.max(0, Math.min(20, foodLevel)));
    // XP and effects are only captured by the experience store; the match-snapshot DB layer does not
    // persist them, so reloaded match snapshots carry the defaults (0 / empty). Guard on captured
    // content so restoring such a snapshot never wipes a reconnecting player's live XP/effects.
    if (xp > 0) {
      playerAdapter.setExperiencePoints(xp);
    }
    if (effects != null && !effects.isEmpty()) {
      // Restore the saved effect set authoritatively: clear whatever is live (entry paths vary) then re-add.
      playerAdapter.clearPotionEffects();
      for (EffectSnapshot effect : effects) {
        if (effect != null && effect.effectKey() != null) {
          playerAdapter.addEffect(effect.effectKey(), effect.amplifier(), effect.durationTicks());
        }
      }
    }
    return savedPosition();
  }

  public WorldPosition savedPosition() {
    if (worldName == null || worldName.isBlank()) {
      return null;
    }
    return new WorldPosition(worldName, coordinateX, coordinateY, coordinateZ, yaw, pitch);
  }

  public boolean connected() {
    return status == Status.CONNECTED;
  }
}
