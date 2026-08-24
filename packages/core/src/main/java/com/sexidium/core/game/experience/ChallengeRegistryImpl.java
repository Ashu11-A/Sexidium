package com.sexidium.core.game.experience;

import com.sexidium.core.game.experience.compose.BlockBreakService;
import com.sexidium.core.game.experience.compose.BlockChangeVeto;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DamageContributor;
import com.sexidium.core.game.experience.compose.DamagePipeline;
import com.sexidium.core.game.experience.compose.DropContributor;
import com.sexidium.core.game.experience.compose.DropPipeline;
import com.sexidium.core.game.experience.compose.HealthModel;
import com.sexidium.core.game.experience.compose.HealthSource;
import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

/**
 * Wires a challenge's declared contributions into the host-owned pipelines. Each challenge calls these
 * during {@code register()}, before any {@code onStart} runs, so the typed capabilities and pipeline
 * contributions are all in place when the first challenge's {@code onStart} fetches a sibling service.
 */
final class ChallengeRegistryImpl implements ChallengeRegistry {
  private final ExperienceGame game;
  private final DropPipeline dropPipeline;
  private final BlockBreakService blockBreakService;
  private final DamagePipeline damagePipeline;
  private final HealthModel healthModel;
  private final GameHud experienceHud;

  ChallengeRegistryImpl(ExperienceGame game,
      DropPipeline dropPipeline,
      BlockBreakService blockBreakService,
      DamagePipeline damagePipeline,
      HealthModel healthModel,
      GameHud experienceHud) {
    this.game = game;
    this.dropPipeline = dropPipeline;
    this.blockBreakService = blockBreakService;
    this.damagePipeline = damagePipeline;
    this.healthModel = healthModel;
    this.experienceHud = experienceHud;
  }

  // Every registration records a matching undo on the game's currently-active challenge binding, so a
  // single challenge can be unwound at runtime (live edit / chaos cycle reset) without touching siblings.
  @Override
  public void dropContributor(DropContributor contributor) {
    dropPipeline.register(contributor);
    game.recordUndo(() -> dropPipeline.unregister(contributor));
  }

  @Override
  public void damageContributor(DamageContributor contributor) {
    damagePipeline.register(contributor);
    game.recordUndo(() -> damagePipeline.unregister(contributor));
  }

  @Override
  public void blockVeto(BlockChangeVeto veto) {
    blockBreakService.registerVeto(veto);
    game.recordUndo(() -> blockBreakService.unregisterVeto(veto));
  }

  @Override
  public void healthSource(HealthSource source) {
    healthModel.register(source);
    game.recordUndo(() -> healthModel.unregister(source));
  }

  @Override
  public void hud(HudContributor contributor) {
    experienceHud.register(contributor);
    game.recordUndo(() -> experienceHud.unregister(contributor));
  }

  @Override
  public HudSurfaceHandle hudSurface(HudSurfaceSpec spec) {
    HudSurfaceHandle handle = game.track(game.hudDriver().open(spec));
    // Closing rather than merely unregistering: a surface holds platform-side state (a claimed layout
    // on the player, a registered sidebar contributor) that outlives the pipeline entry.
    game.recordUndo(handle::close);
    return handle;
  }

  @Override
  public <T> void publish(Class<T> type, T implementation) {
    game.publish(type, implementation);
    game.recordUndo(() -> game.unpublish(type, implementation));
  }
}
