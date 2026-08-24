package com.sexidium.core.world;

import com.sexidium.core.game.GameManager;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;

/**
 * Shared decision logic for lobby protection. Both adapters hook their native events and consult
 * this policy to decide whether a player is currently subject to lobby rules (in the lobby world and
 * not inside an active match) and which protections are enabled in config.
 */
public final class LobbyGuardPolicy {
  private static final String DEFAULT_LOBBY = "lobby";
  private static final String DEFAULT_WORLDS_ROOT = "worlds";

  private final ConfigurationAdapter configuration;
  private final GameManager gameManager;

  public LobbyGuardPolicy(ConfigurationAdapter configuration, GameManager gameManager) {
    this.configuration = configuration;
    this.gameManager = gameManager;
  }

  public String lobbyWorldName() {
    String configured = configuration.getString("worlds.lobby.name", DEFAULT_LOBBY);
    return configured == null || configured.isBlank() ? DEFAULT_LOBBY : configured;
  }

  public boolean asDefaultSpawn() {
    return configuration.getBoolean("worlds.lobby.as-default-spawn", true);
  }

  public boolean isLobbyWorld(String worldName) {
    if (worldName == null || worldName.isBlank()) {
      return false;
    }
    String normalizedWorld = normalizeWorldName(worldName);
    String lobby = normalizeWorldName(lobbyWorldName());
    if (normalizedWorld.equalsIgnoreCase(lobby)) {
      return true;
    }
    return normalizedWorld.equalsIgnoreCase(normalizeWorldName(worldsRootName() + "/" + lobby))
        || normalizedWorld.equalsIgnoreCase("sexidium:" + lobby);
  }

  /**
   * A player is subject to lobby protections when they are in the lobby world and NOT currently in an
   * active match (matches run in their own temp worlds and manage their own rules).
   */
  public boolean isProtected(PlayerAdapter player) {
    if (player == null || !player.online()) {
      return false;
    }
    WorldAdapter world = player.world();
    if (world == null || !isLobbyWorld(world.name())) {
      return false;
    }
    return gameManager == null || gameManager.matchOf(player) == null;
  }

  public boolean blockBlockBreak() {
    return flag("block-break", true);
  }

  public boolean blockBlockPlace() {
    return flag("block-place", true);
  }

  public boolean blockDamage() {
    return flag("damage", true);
  }

  public boolean blockHunger() {
    return flag("hunger", true);
  }

  public boolean blockItemDrop() {
    return flag("item-drop", true);
  }

  public boolean blockInteractions() {
    return flag("interactions", false);
  }

  /** Cancel natural animal/monster spawning in the lobby world (does not affect NPCs/plugin spawns). */
  public boolean blockMobSpawn() {
    return flag("no-mob-spawn", true);
  }

  public boolean voidRedirect() {
    return flag("void-redirect", true);
  }

  /** Players below this Y in the lobby are sent back to the lobby spawn. */
  public double voidLevel() {
    return configuration.getDouble("worlds.lobby.protection.void-level", 0.0);
  }

  private boolean flag(String key, boolean defaultValue) {
    return configuration.getBoolean("worlds.lobby.protection." + key, defaultValue);
  }

  private String worldsRootName() {
    String configured = configuration.getString("worlds.root", DEFAULT_WORLDS_ROOT);
    return configured == null || configured.isBlank() ? DEFAULT_WORLDS_ROOT : configured;
  }

  private static String normalizeWorldName(String worldName) {
    return worldName == null ? "" : worldName.trim().replace('\\', '/');
  }
}
