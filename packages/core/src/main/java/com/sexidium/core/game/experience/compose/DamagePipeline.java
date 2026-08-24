package com.sexidium.core.game.experience.compose;

import java.util.Comparator;

/**
 * Host-owned ordered damage pipeline. Replaces the old free-for-all where Shared Life, XP Health and
 * Chained each independently cancelled {@code PlayerDamageGameEvent} and double-charged a hit. The
 * host builds one {@link DamageContext} per hit, runs it through the registered contributors in
 * order, and cancels the native event once based on the resolved outcome. Registration/ordering live
 * in {@link ContributorRegistry}.
 */
public final class DamagePipeline extends ContributorRegistry<DamageContributor> {
  public DamagePipeline() {
    super(Comparator.comparingInt(DamageContributor::order));
  }

  /** Runs the hit through every contributor; returns the resolved context. */
  public DamageContext process(DamageContext context) {
    for (DamageContributor contributor : contributors()) {
      contributor.onDamage(context);
    }
    return context;
  }
}
