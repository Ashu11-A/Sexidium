package com.sexidium.core.game.chaos;

import com.sexidium.core.game.experience.compose.BlockChangeVeto;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DamageContext;
import com.sexidium.core.game.experience.compose.DamageContributor;
import com.sexidium.core.game.experience.compose.DropContext;
import com.sexidium.core.game.experience.compose.DropContributor;
import com.sexidium.core.game.experience.compose.DropPhase;
import com.sexidium.core.game.experience.compose.HealthSource;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Wires a single Chaos player's challenge contributions into the host's SHARED pipelines, each wrapped
 * with an owner gate so the effect only ever applies to that player: a per-player Double Drops only
 * multiplies the OWNER's breaks, a per-player Shared Life only governs the OWNER's health, a per-player
 * HUD line only shows on the OWNER's panel. Every registration also records an undo on the host's active
 * binding so the player's challenges can be torn down at the next cycle. Block vetoes are positional (not
 * attributable to a player) so they register globally.
 */
final class ScopedChallengeRegistry implements ChallengeRegistry {
  private final ChaosGame host;
  private final PlayerScope scope;
  private final UUID owner;

  ScopedChallengeRegistry(ChaosGame host, PlayerScope scope) {
    this.host = host;
    this.scope = scope;
    this.owner = scope.owner();
  }

  private boolean owns(PlayerAdapter player) {
    return player != null && owner.equals(player.uniqueId());
  }

  @Override
  public void dropContributor(DropContributor contributor) {
    DropContributor wrapped = new DropContributor() {
      @Override
      public DropPhase phase() {
        return contributor.phase();
      }

      @Override
      public int order() {
        return contributor.order();
      }

      @Override
      public void contribute(DropContext context) {
        if (owns(context.cause())) {
          contributor.contribute(context);
        }
      }
    };
    host.drops().register(wrapped);
    host.recordUndo(() -> host.drops().unregister(wrapped));
  }

  @Override
  public void damageContributor(DamageContributor contributor) {
    DamageContributor wrapped = new DamageContributor() {
      @Override
      public int order() {
        return contributor.order();
      }

      @Override
      public void onDamage(DamageContext context) {
        if (owns(context.victim())) {
          contributor.onDamage(context);
        }
      }
    };
    host.damage().register(wrapped);
    host.recordUndo(() -> host.damage().unregister(wrapped));
  }

  @Override
  public void blockVeto(BlockChangeVeto veto) {
    // Positional, not player-attributable — register globally (shared world).
    host.blocks().registerVeto(veto);
    host.recordUndo(() -> host.blocks().unregisterVeto(veto));
  }

  @Override
  public void healthSource(HealthSource source) {
    HealthSource wrapped = new HealthSource() {
      @Override
      public int priority() {
        return source.priority();
      }

      @Override
      public OptionalDouble value(PlayerAdapter player) {
        return owns(player) ? source.value(player) : OptionalDouble.empty();
      }

      @Override
      public OptionalDouble scale(PlayerAdapter player) {
        return owns(player) ? source.scale(player) : OptionalDouble.empty();
      }
    };
    host.health().register(wrapped);
    host.recordUndo(() -> host.health().unregister(wrapped));
  }

  @Override
  public void hud(HudContributor contributor) {
    HudContributor wrapped = new HudContributor() {
      @Override
      public int order() {
        return contributor.order();
      }

      @Override
      public void describe(HudContext context) {
        if (owns(context.player())) {
          contributor.describe(context);
        }
      }
    };
    host.hud().register(wrapped);
    host.recordUndo(() -> host.hud().unregister(wrapped));
  }

  /**
   * Opens the surface, then gates it to the owner: everything a non-owner would be shown is dropped.
   *
   * <p>Cheaper and safer than filtering at render time, because a surface's viewer set IS its state —
   * a Chaos twist held by two players opens two handles for the same declaration, and letting either
   * one enrol the other's player would give a player a readout for a twist they were never assigned.</p>
   */
  @Override
  public HudSurfaceHandle hudSurface(HudSurfaceSpec spec) {
    HudSurfaceHandle handle = host.track(host.hudDriver().open(spec));
    HudSurfaceHandle owned = new HudSurfaceHandle() {
      @Override
      public boolean active() {
        return handle.active();
      }

      @Override
      public boolean activeFor(PlayerAdapter playerAdapter) {
        return owns(playerAdapter) && handle.activeFor(playerAdapter);
      }

      @Override
      public void text(String key, LocalizedText value) {
        handle.text(key, value);
      }

      @Override
      public void number(String key, double value) {
        handle.number(key, value);
      }

      @Override
      public void flag(String key, boolean value) {
        handle.flag(key, value);
      }

      @Override
      public void progress(String key, double value) {
        handle.progress(key, value);
      }

      @Override
      public void show(PlayerAdapter playerAdapter) {
        if (owns(playerAdapter)) {
          handle.show(playerAdapter);
        }
      }

      @Override
      public void hide(PlayerAdapter playerAdapter) {
        handle.hide(playerAdapter);
      }

      @Override
      public void refresh() {
        handle.refresh();
      }

      @Override
      public void close() {
        handle.close();
      }
    };
    host.recordUndo(owned::close);
    return owned;
  }

  @Override
  public <T> void publish(Class<T> type, T implementation) {
    scope.publish(type, implementation);
  }
}
