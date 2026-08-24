package com.sexidium.core.game.chaos;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ExperienceState;
import com.sexidium.core.game.experience.compose.BlockBreakService;
import com.sexidium.core.game.experience.compose.ChallengeContext;
import com.sexidium.core.game.experience.compose.DamagePipeline;
import com.sexidium.core.game.experience.compose.DropPipeline;
import com.sexidium.core.game.experience.compose.ExperienceStats;
import com.sexidium.core.game.experience.compose.HealthModel;
import com.sexidium.core.game.experience.compose.MobRegistry;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.TabListHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link ChallengeContext} a single Chaos player's challenges see. It narrows the host's mode-wide
 * view to one owner so existing {@link Challenge} implementations behave per-player with no changes:
 * {@link #online()} is just the owner, {@link #isParticipant} is true only for the owner, state and
 * published capabilities are private to this scope, while the shared pipelines and {@link #modePopulation()}
 * (the full roster, for Shared Life's average) come from the host. Owner-attribution of pipeline
 * contributions happens in {@link ScopedChallengeRegistry}.
 */
final class PlayerScope implements ChallengeContext {
  private final ChaosGame host;
  private final UUID owner;
  // Per-player published capabilities (e.g. XpHealthModel) — checked before the host's, so a player's
  // SharedLife only sees that same player's XP Health.
  private final Map<Class<?>, Object> services = new HashMap<>();

  PlayerScope(ChaosGame host, UUID owner) {
    this.host = host;
    this.owner = owner;
  }

  UUID owner() {
    return owner;
  }

  @Override
  public GameContext gameContext() {
    return host.gameContext();
  }

  @Override
  public List<PlayerAdapter> online() {
    PlayerAdapter player = host.gameContext().server().player(owner).filter(PlayerAdapter::online).orElse(null);
    return player == null ? List.of() : List.of(player);
  }

  @Override
  public boolean isParticipant(PlayerAdapter playerAdapter) {
    return playerAdapter != null && owner.equals(playerAdapter.uniqueId()) && host.isParticipant(playerAdapter);
  }

  @Override
  public List<PlayerAdapter> modePopulation() {
    return host.online();
  }

  @Override
  public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
    return host.runTimer(runnable, delayTicks, periodTicks);
  }

  @Override
  public ScheduledTask runLater(Runnable runnable, long delayTicks) {
    return host.runLater(runnable, delayTicks);
  }

  @Override
  public BossBarHandle track(BossBarHandle bossBarHandle) {
    return host.track(bossBarHandle);
  }

  @Override
  public HudPanelHandle track(HudPanelHandle hudPanelHandle) {
    return host.track(hudPanelHandle);
  }

  @Override
  public HudSurfaceHandle track(HudSurfaceHandle hudSurfaceHandle) {
    return host.track(hudSurfaceHandle);
  }

  @Override
  public TabListHandle track(TabListHandle tabListHandle) {
    return host.track(tabListHandle);
  }

  /**
   * Played time comes from the HOST, not from this scope.
   *
   * <p>A Chaos scope narrows the roster to one player, but the run's clock is the mode's, and the
   * buffered seconds live on the host. Answering from the scoped stats would report only what had been
   * committed, which is the very jump this seam exists to remove.</p>
   */
  @Override
  public long liveRunSeconds() {
    return host.liveRunSeconds();
  }

  @Override
  public long liveRunSeconds(UUID playerId) {
    return host.liveRunSeconds(playerId);
  }

  @Override
  public WorldAdapter world() {
    return host.world();
  }

  @Override
  public ExperienceState sharedState() {
    return host.playerState(owner);
  }

  @Override
  public void softRespawn(PlayerAdapter playerAdapter) {
    host.softRespawn(playerAdapter);
  }

  @Override
  public void killParticipant(PlayerAdapter playerAdapter) {
    host.killParticipant(playerAdapter);
  }

  // ----- sibling discovery (scoped to this owner's challenges) ----------------------------------

  @Override
  public List<Challenge> challenges() {
    return host.challengesOf(owner);
  }

  @Override
  public Optional<Challenge> challenge(String id) {
    if (id == null) {
      return Optional.empty();
    }
    for (Challenge challenge : host.challengesOf(owner)) {
      if (challenge.id().equalsIgnoreCase(id)) {
        return Optional.of(challenge);
      }
    }
    return Optional.empty();
  }

  @Override
  public <C extends Challenge> Optional<C> challenge(Class<C> type) {
    if (type == null) {
      return Optional.empty();
    }
    for (Challenge challenge : host.challengesOf(owner)) {
      if (type.isInstance(challenge)) {
        return Optional.of(type.cast(challenge));
      }
    }
    return Optional.empty();
  }

  // ----- per-scope capability registry ---------------------------------------------------------

  @Override
  public <T> void publish(Class<T> type, T implementation) {
    if (type != null && implementation != null) {
      services.put(type, implementation);
    }
  }

  @Override
  public <T> Optional<T> service(Class<T> type) {
    if (type == null) {
      return Optional.empty();
    }
    Object implementation = services.get(type);
    if (implementation != null) {
      return Optional.of(type.cast(implementation));
    }
    return host.service(type);
  }

  // ----- shared pipelines (the host's; contributions are owner-gated at registration) -----------

  @Override
  public DropPipeline drops() {
    return host.drops();
  }

  @Override
  public BlockBreakService blocks() {
    return host.blocks();
  }

  @Override
  public DamagePipeline damage() {
    return host.damage();
  }

  @Override
  public HealthModel health() {
    return host.health();
  }

  @Override
  public MobRegistry mobs() {
    return host.mobs();
  }

  @Override
  public ExperienceStats stats() {
    return host.stats();
  }
}
