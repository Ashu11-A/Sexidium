package com.sexidium.core.game.experience.compose;

/**
 * A challenge's contribution to the shared {@link DamagePipeline}. Registered via
 * {@link ChallengeRegistry#damageContributor(DamageContributor)}. Contributors run in ascending
 * {@link #order()} over one {@link DamageContext}; the recommended ordering is
 * reduce (Growing) &lt; absorb-into-pool (Shared Life) &lt; convert-to-XP (XP Health) &lt;
 * death-link (Chained).
 */
public interface DamageContributor {
  /** Ascending; lower runs first. */
  default int order() {
    return 0;
  }

  void onDamage(DamageContext context);
}
