package com.sexidium.velocity.adapter;

import com.sexidium.core.platform.NetworkPlayer;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Locale;
import java.util.UUID;

/**
 * A proxy-side player.
 *
 * <p>Implements {@link NetworkPlayer} and deliberately not {@code PlayerAdapter}: the proxy has no
 * world, position, inventory or health to offer, and an adapter that returned plausible-looking zeros
 * for those would be worse than one that cannot be asked.</p>
 *
 * <p>Velocity ships Adventure and MiniMessage on its API, so the MiniMessage strings core already
 * emits render here with no translation layer and nothing shaded.</p>
 */
public final class VelocityPlayer implements NetworkPlayer {

  private final Player player;
  private final String nodeId;

  public VelocityPlayer(Player player, String nodeId) {
    this.player = player;
    this.nodeId = nodeId;
  }

  public Player handle() {
    return player;
  }

  @Override
  public UUID uniqueId() {
    return player.getUniqueId();
  }

  @Override
  public boolean online() {
    return player.isActive();
  }

  /**
   * The backend this player is currently connected to, or the proxy's own id when they are in
   * transit — during a transfer there is genuinely no backend that owns them.
   */
  @Override
  public String nodeId() {
    return player.getCurrentServer()
        .map(connection -> connection.getServerInfo().getName())
        .orElse(nodeId);
  }

  @Override
  public String name() {
    return player.getUsername();
  }

  @Override
  public Locale locale() {
    Locale playerLocale = player.getEffectiveLocale();
    return playerLocale == null ? Locale.ENGLISH : playerLocale;
  }

  @Override
  public boolean hasPermission(String permission) {
    return player.hasPermission(permission);
  }

  @Override
  public void sendMiniMessage(String miniMessage) {
    player.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
  }

  @Override
  public void sendPlainMessage(String message) {
    player.sendMessage(Component.text(message));
  }
}
