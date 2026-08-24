package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.experience.ExperienceHost;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.HashSet;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * Host-owned merge of per-player health contributions. Instead of each life challenge calling
 * {@code setHealth}/{@code setHealthScale} on its own timer (causing flicker), every challenge
 * registers a {@link HealthSource} (via {@link ContributorRegistry}) and the host writes the resolved
 * value/scale once per tick via {@link #writeAll()}. Exposes both whole-roster control ({@link
 * #writeAll()}) and per-player control ({@link #writeNow(PlayerAdapter)} / {@link
 * #effective(PlayerAdapter)}).
 */
public final class HealthModel extends ContributorRegistry<HealthSource> {
  private final ExperienceHost host;
  // Players mid-death: their governance is paused so a REAL death (health -> 0, driven by a life
  // challenge's depletion) is not instantly healed back by the next per-tick write. Resumed on respawn.
  private final Set<UUID> suspended = new HashSet<>();

  public HealthModel(ExperienceHost host) {
    this.host = host;
  }

  /** Pause per-tick governance for a player who is being killed for real, so the death can register. */
  public void suspend(UUID playerId) {
    if (playerId != null) {
      suspended.add(playerId);
    }
  }

  /** Resume governance after the player respawns (or leaves). Idempotent. */
  public void resume(UUID playerId) {
    if (playerId != null) {
      suspended.remove(playerId);
    }
  }

  /** The governing target health for a player, or their max health when no source provides one. */
  public double effective(PlayerAdapter player) {
    HealthSource best = null;
    for (HealthSource source : contributors()) {
      if (source.value(player).isPresent() && (best == null || source.priority() > best.priority())) {
        best = source;
      }
    }
    if (best != null) {
      return best.value(player).orElse(player.maxHealth());
    }
    return player.maxHealth();
  }

  /** Applies the resolved health and heart scale to one player. */
  public void writeNow(PlayerAdapter player) {
    if (player == null || !player.online() || suspended.contains(player.uniqueId())) {
      return;
    }
    HealthSource valueSource = null;
    HealthSource scaleSource = null;
    for (HealthSource source : contributors()) {
      if (source.value(player).isPresent() && (valueSource == null || source.priority() > valueSource.priority())) {
        valueSource = source;
      }
      if (source.scale(player).isPresent() && (scaleSource == null || source.priority() > scaleSource.priority())) {
        scaleSource = source;
      }
    }
    if (valueSource != null) {
      OptionalDouble value = valueSource.value(player);
      if (value.isPresent()) {
        player.setHealth(Math.max(1.0, Math.min(player.maxHealth(), value.getAsDouble())));
      }
    }
    if (scaleSource != null) {
      OptionalDouble scale = scaleSource.scale(player);
      if (scale.isPresent()) {
        player.setHealthScale(scale.getAsDouble());
      }
    }
  }

  public void writeAll() {
    if (isEmpty() || host == null) {
      return;
    }
    for (PlayerAdapter player : host.online()) {
      writeNow(player);
    }
  }
}
