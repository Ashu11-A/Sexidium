package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

/**
 * Handed to each {@code Challenge.register(ChallengeRegistry)} once at start so the challenge can
 * declare its contributions to the shared pipelines and publish capabilities for siblings to call,
 * instead of grabbing raw events and acting in isolation.
 */
public interface ChallengeRegistry {
  void dropContributor(DropContributor contributor);

  void damageContributor(DamageContributor contributor);

  void blockVeto(BlockChangeVeto veto);

  void healthSource(HealthSource source);

  /** Contribute lines to the unified per-player experience HUD. */
  void hud(HudContributor contributor);

  /**
   * Declare an on-screen surface of this challenge's own — a corner readout, a bar, a toast — and get
   * back the handle to push values into.
   *
   * <p>Preferred over {@link #hud(HudContributor)} whenever the readout wants a shape the sidebar's
   * list of lines cannot express, because the driver stack renders the same declaration on the
   * platform's overlay AND on the sidebar, choosing per player. Registering here (rather than opening
   * one through the host directly) is what unbinds the surface when the challenge is unbound, so a
   * live edit or a Chaos cycle reset cannot leave one drawing.</p>
   */
  HudSurfaceHandle hudSurface(HudSurfaceSpec spec);

  /** Publish a typed capability a sibling can fetch via {@link ChallengeContext#service(Class)}. */
  <T> void publish(Class<T> type, T implementation);
}
