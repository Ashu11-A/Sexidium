package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.MobDamageGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.SoundKey;

import java.util.List;

/**
 * Mob Duplication challenge.
 *
 * <p>Rule: whenever a mob takes player-caused damage, that mob is duplicated. Every hit a
 * participant lands therefore multiplies the surrounding mobs, quickly snowballing combat.
 *
 * <p>Composition: duplication is gated by the shared {@link com.sexidium.core.game.experience.compose.MobRegistry}.
 * It skips while Total Cleave is dealing its splash damage (so one swing cannot duplicate an entire
 * horde recursively) and obeys a per-tick spawn budget, so the headline "cleave + duplication"
 * exponential explosion is capped instead of killing the server.</p>
 */
public final class MobDuplicationChallenge extends Challenge {
  public MobDuplicationChallenge() {
    super("mobduplication", "Mob Duplication");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Mobs duplicated:</gray> <white>" + stats().get("mobduplication.count") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      int budget = Math.max(0, cfg().getInt(configPath("max-per-tick"), 16));
      context.debugStat("Spawn budget/tick", budget == 0 ? "∞" : budget);
      context.debugStat("Cleave active", mobs().cleaveActive());
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // 0 = uncapped. Bounds the snowball (notably the Cleave-splash chain) without changing the rule.
    mobs().spawnBudgetPerTick(Math.max(0, cfg().getInt(configPath("max-per-tick"), 16)));
  }

  @Override
  public void onMobDamage(MobDamageGameEvent event) {
    if (isParticipant(event.attacker())) {
      // A Cleave swing hits every nearby mob at once; without this guard each of those programmatic
      // hits would duplicate the whole horde, then the duplicates would be hit again — exponential.
      if (mobs().cleaveActive() || !mobs().tryRegisterSpawn()) {
        return;
      }
      event.mob().duplicate();
      stats().add("mobduplication.count", 1);
      if (cfg().getBoolean(configPath("play-sound"), true)
          && event.attacker() != null
          && event.attacker().position() != null) {
        event.attacker().playSound(new SoundKey("minecraft:entity.slime.squish"), 0.5f, 1.2f);
      }
    }
  }
}
