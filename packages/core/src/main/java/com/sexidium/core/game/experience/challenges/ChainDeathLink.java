package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.compose.DamageContext;
import com.sexidium.core.game.experience.compose.DamageContributor;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.SoundKey;

/**
 * Death link: an otherwise-fatal, unabsorbed hit revives the whole team IN PLACE. The fatal hit is
 * claimed (absorbed), so the victim never actually dies or respawns — everyone is just healed where
 * they stand, keeping the chain intact rather than scattering the team to spawn. It defers when another
 * life challenge already absorbed the hit (Shared Life pool, XP Health) or already claimed the death.
 *
 * <p>Order 40 so it runs after the dedicated life challenges. Reads the {@code death-link}/{@code
 * play-sounds} toggles and the heal/stats helpers from its owning {@link ChainedChallenge}.</p>
 */
final class ChainDeathLink implements DamageContributor {
  private final ChainedChallenge challenge;

  ChainDeathLink(ChainedChallenge challenge) {
    this.challenge = challenge;
  }

  @Override
  public int order() {
    return 40;
  }

  @Override
  public void onDamage(DamageContext context) {
    if (!challenge.deathLinkEnabled() || context.fatalHandled() || context.absorbed()) {
      return;
    }
    PlayerAdapter victim = context.victim();
    if (victim == null || context.amount() < victim.health()) {
      return;
    }
    context.markFatalHandled();
    challenge.recordDeath(victim);
    boolean playSounds = challenge.soundsEnabled();
    for (PlayerAdapter p : challenge.onlineParticipants()) {
      challenge.healInPlace(p);
      if (playSounds && p.position() != null) {
        p.playSound(new SoundKey("minecraft:entity.wither.spawn"), 0.7f, 0.8f);
      }
      p.sendActionBar("<red>Death link — the team is revived together!");
    }
  }
}
