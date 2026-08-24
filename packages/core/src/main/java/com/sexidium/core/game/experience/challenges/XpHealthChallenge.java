package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DamageContext;
import com.sexidium.core.game.experience.compose.DamageContributor;
import com.sexidium.core.game.experience.compose.HealthSource;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.experience.compose.SharedHealthPool;
import com.sexidium.core.game.experience.compose.XpHealthModel;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.DamageCauseType;
import com.sexidium.core.platform.model.PopupType;

import java.util.List;
import java.util.OptionalDouble;

/**
 * A player's experience pool doubles as their health: damage burns XP instead of hearts, and XP
 * slowly matters again as they earn it. Open-ended: running out of XP soft-respawns the player and
 * refills them to the starting XP instead of eliminating them.
 *
 * <p>Composition: XP Health publishes an {@link XpHealthModel} so Shared Life can fold XP-as-health
 * into a single, group-shared XP bar (the pool becomes the shared life, denominated in XP). While a
 * {@link SharedHealthPool} is present XP Health <em>stands down entirely</em> — it does not seed,
 * drain, govern hearts or draw its own HUD line — so the pool is the one authority and the hit is
 * never double-charged. When Shared Life is absent it runs the classic per-player mechanic.</p>
 */
public final class XpHealthChallenge extends Challenge implements XpHealthModel {
  private int startingXp;
  private double xpPerDamagePoint;
  private boolean voidConsumesAllXp;
  private int deathThresholdXp;
  private boolean scaleHeartDisplay;
  private double displayHealthScale;

  public XpHealthChallenge() {
    super("xphealth", "XP Health");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    // Snapshot config now (before any onStart) and publish it, so Shared Life's onStart can read a
    // populated model regardless of which challenge's onStart runs first.
    startingXp = Math.max(0, cfg().getInt(configPath("starting-xp-points"), 30));
    xpPerDamagePoint = Math.max(0.0, cfg().getDouble(configPath("xp-per-damage-point"), 4.0));
    voidConsumesAllXp = cfg().getBoolean(configPath("void-consumes-all-xp"), true);
    deathThresholdXp = Math.max(0, cfg().getInt(configPath("death-threshold-xp"), 0));
    scaleHeartDisplay = cfg().getBoolean(configPath("scale-heart-display"), true);
    displayHealthScale = Math.max(1.0, cfg().getDouble(configPath("display-health-scale"), 2.0));
    registry.publish(XpHealthModel.class, this);
    registry.damageContributor(new XpDrain());
    registry.healthSource(new XpHearts());
    registry.hud(this::describeHud);
  }

  /** True when Shared Life owns the (XP-denominated) pool, so this challenge stands down. */
  private boolean pooled() {
    return service(SharedHealthPool.class).isPresent();
  }

  private void describeHud(HudContext context) {
    if (!pooled()) {
      context.line("<green>XP health:</green> <white>" + context.player().experiencePoints()
          + "</white> <gray>(gain XP to heal)</gray>");
    }
    // else: Shared Life draws the single merged "Shared XP" line — but still surface the knobs in debug.
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Pooled", pooled());
      context.debugStat("Start XP", startingXp);
      context.debugStat("XP / dmg pt", String.format(java.util.Locale.ROOT, "%.1f", xpPerDamagePoint));
      context.debugStat("Death threshold", deathThresholdXp);
      context.debugStat("Void clears all", voidConsumesAllXp);
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    if (pooled()) {
      return; // Shared Life seeds and mirrors the shared XP bar.
    }
    for (PlayerAdapter player : (participants == null ? List.<PlayerAdapter>of() : participants)) {
      seedXp(player);
    }
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    if (!pooled()) {
      seedXp(playerAdapter);
    }
  }

  @Override
  public void resetPlayer(PlayerAdapter playerAdapter) {
    // Removed mid-run: restore the normal heart-display scale (the XP bar reverts to vanilla on its own).
    if (playerAdapter != null) {
      playerAdapter.resetHealthScale();
    }
  }

  // The heart-display scale is reset by the host on exit (resetStatuses() via leave/stop), so the
  // challenge no longer resets it — it only drives the XP-as-health mechanic.

  private void seedXp(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    if (cfg().getBoolean(configPath("clear-existing-xp"), false) || player.experiencePoints() < startingXp) {
      player.setExperiencePoints(startingXp);
    }
  }

  /** Converts residual damage (after any pool absorption) into XP loss; out of XP soft-respawns. */
  private final class XpDrain implements DamageContributor {
    @Override
    public int order() {
      return 30;
    }

    @Override
    public void onDamage(DamageContext context) {
      if (pooled()) {
        return; // the shared pool drains the XP for the whole group; do not double-charge.
      }
      if (context.amount() <= 0.0) {
        return; // no damage (e.g. fully reduced upstream)
      }
      // Burns XP from the hit. We absorb the native hit but never consume() the damage, so a sibling
      // damage system still sees the full hit.
      PlayerAdapter victim = context.victim();
      int current = Math.max(0, victim.experiencePoints());
      int loss = voidConsumesAllXp && context.cause() == DamageCauseType.VOID
          ? current
          : (int) Math.max(0.0, Math.ceil(context.amount() * xpPerDamagePoint));
      int remaining = Math.max(0, current - loss);
      victim.setExperiencePoints(remaining);
      context.absorb();
      popup(victim, PopupType.WARNING, MessageKey.EXPERIENCE_CHALLENGE_XP_DAMAGE, MessageArg.text("amount", loss));
      if (remaining <= deathThresholdXp) {
        popupAll(PopupType.ELIMINATION, MessageKey.EXPERIENCE_CHALLENGE_XP_DEATH,
            MessageArg.text("player", victim.name()),
            MessageArg.localized("reason", text(MessageKey.EXPERIENCE_CHALLENGE_XP_REASON_DAMAGE)));
        // Out of XP is a REAL death: the death animation plays and the death screen appears (keepInventory
        // preserves items + XP), then the respawn refills the XP-as-health pool to its starting value.
        kill(victim);
        victim.setExperiencePoints(startingXp);
      }
    }
  }

  /** Heart-display scale always; the heart value only when no shared pool governs it. */
  private final class XpHearts implements HealthSource {
    @Override
    public int priority() {
      return 5;
    }

    @Override
    public OptionalDouble value(PlayerAdapter player) {
      if (pooled()) {
        return OptionalDouble.empty(); // the pool owns the bar; XP is the resource that drains
      }
      return OptionalDouble.of(player.maxHealth());
    }

    @Override
    public OptionalDouble scale(PlayerAdapter player) {
      if (pooled()) {
        return OptionalDouble.empty(); // Shared Life applies the XP heart scale in pooled mode
      }
      return scaleHeartDisplay ? OptionalDouble.of(displayHealthScale) : OptionalDouble.empty();
    }
  }

  // ----- XpHealthModel capability --------------------------------------------------------------

  @Override
  public int maxXp() {
    return startingXp;
  }

  @Override
  public double xpPerDamagePoint() {
    return xpPerDamagePoint;
  }

  @Override
  public int deathThresholdXp() {
    return deathThresholdXp;
  }

  @Override
  public boolean voidConsumesAllXp() {
    return voidConsumesAllXp;
  }

  @Override
  public double displayHealthScale() {
    return displayHealthScale;
  }

  @Override
  public boolean scaleHeartDisplay() {
    return scaleHeartDisplay;
  }
}
