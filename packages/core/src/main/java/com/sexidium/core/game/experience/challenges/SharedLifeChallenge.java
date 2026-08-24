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
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.DamageCauseType;
import com.sexidium.core.platform.model.PopupType;

import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

/**
 * Every participant shares a single health pool. Damage to anyone drains it; it slowly recovers over
 * time. Open-ended: when the pool empties the whole group soft-respawns and the pool resets to full.
 *
 * <p>Composition: Shared Life absorbs the hit into the pool via an ordered {@link DamageContributor}
 * (running in parallel with other damage systems, not swallowing the hit), governs the displayed
 * health via a {@link HealthSource}, and publishes {@link SharedHealthPool}.</p>
 *
 * <p><b>XP-pooled mode:</b> when {@link XpHealthChallenge} is also active it publishes an
 * {@link XpHealthModel}; Shared Life then denominates its pool in XP points (max = the starting XP,
 * e.g. {@code 30}) instead of hearts. The single pool value is mirrored onto every player's XP bar,
 * so the group shares ONE XP bar that doubles as everyone's life — and the HUD shows a single
 * {@code Shared XP: x/y} line rather than separate "Shared life" + "XP health" numbers. XP Health
 * itself stands down in this mode, so the hit is drained exactly once.</p>
 */
public final class SharedLifeChallenge extends Challenge implements SharedHealthPool {
  private static final String KEY_HEALTH = "health";
  // Denomination the persisted pool was last saved in ("xp" vs "hp"). Lets a restart detect when the
  // experience's mode changed (XP Health added/removed) and avoid reading the old value in the wrong
  // unit. Absent on pre-merge saves, which therefore reset rather than misread.
  private static final String KEY_MODE = "mode";
  // Hard ceiling for the XP pool so it can grow far past the full-hearts reference (XP = extra life
  // buffer) without overflowing the int XP bar.
  private static final double MAX_XP_POOL = 1_000_000.0;

  private static final String ROW_POOL = "pool";
  private static final String ROW_AMOUNT = "amount";

  private HudSurfaceHandle poolSurface = HudSurfaceHandle.NOOP;
  private double sharedHealth;
  private double maxHealth;
  private boolean voidFatal;
  private double damageMultiplier;
  private double healingMultiplier;

  // XP-pooled mode (Shared Life + XP Health composed): the pool is denominated in XP and mirrored to
  // every player's XP bar. Null when XP Health is not part of this experience.
  private XpHealthModel xpModel;
  // Last value pushed onto every XP bar — the baseline used to tell earned XP (bar rose above it →
  // feed the pool) apart from a damage drain (pool fell, bars not yet updated → no false feed).
  private int lastMirroredXp;

  public SharedLifeChallenge() {
    super("sharedlife", "Shared Life");
  }

  /**
   * The pool, as a bar plus its absolute figure.
   *
   * <p>A bar rather than a line of text because the pool is the one number in this challenge that is
   * read at a glance mid-fight — "are we nearly dead" is a proportion, not a decimal. The declaration
   * is honoured as a real bar by a driver that can draw one and as a run of block glyphs on the
   * sidebar, which is the same information at lower resolution rather than a different readout.</p>
   */
  public static HudSurfaceSpec poolSpec() {
    return HudSurfaceSpec.persistent("sharedlife")
        .anchor(HudAnchor.TOP_LEFT)
        .bar(ROW_POOL, LocalizedText.of(MessageKey.EXPERIENCE_SHAREDLIFE_POOL))
        .text(ROW_AMOUNT, LocalizedText.of(MessageKey.EXPERIENCE_SHAREDLIFE_AMOUNT))
        .build();
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.damageContributor(new Drain());
    registry.healthSource(new PoolHealth());
    registry.hud(this::describeDebug);
    poolSurface = hudSurface(registry, poolSpec());
    registry.publish(SharedHealthPool.class, this);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // XP Health (if present) publishes its config in register(), so it is resolvable here regardless
    // of challenge ordering. When present, the pool is the group's shared XP and doubles as life.
    xpModel = service(XpHealthModel.class).orElse(null);
    voidFatal = cfg().getBoolean(configPath("void-is-fatal"), true);
    damageMultiplier = Math.max(0.0, cfg().getDouble(configPath("damage-multiplier"), 1.0));
    healingMultiplier = Math.max(0.0, cfg().getDouble(configPath("healing-multiplier"), 1.0));
    // maxHealth is the FULL-HEARTS reference: in XP mode it is the starting XP that maps to full hearts;
    // the pool may grow ABOVE it (earned XP = extra life buffer) up to MAX_XP_POOL.
    if (xpModel != null) {
      maxHealth = Math.max(1.0, xpModel.maxXp());
    } else {
      maxHealth = Math.max(1.0, cfg().getDouble(configPath("max-health"), 20.0));
    }
    double startingHealth = xpModel != null
        ? maxHealth
        : clamp(cfg().getDouble(configPath("starting-health"), maxHealth), 1.0, maxHealth);
    double poolCeiling = xpModel != null ? MAX_XP_POOL : maxHealth;
    // Only restore the persisted pool when it was saved in the SAME denomination as the current mode;
    // a mode change (or a pre-merge save with no recorded mode) starts fresh so we never read an
    // XP-denominated value as hearts (or vice-versa).
    String mode = xpModel != null ? "xp" : "hp";
    boolean sameMode = stateHas(KEY_HEALTH) && mode.equals(stateString(KEY_MODE, ""));
    sharedHealth = sameMode
        ? clamp(stateDouble(KEY_HEALTH, startingHealth), depletionFloor(), poolCeiling)
        : startingHealth;
    setStateDouble(KEY_HEALTH, sharedHealth);
    setStateString(KEY_MODE, mode);
    lastMirroredXp = (int) Math.round(sharedHealth);
    runTimer(this::regenTick, 20L, 20L);
    if (xpModel != null) {
      // Two-way sync each tick: earned XP feeds the shared pool (life grows past full hearts), and the
      // pool is mirrored back onto every player's XP bar (the shared XP bar IS the group life bar).
      runTimer(this::syncXpPool, 1L, 1L);
    }
  }

  /** Steady passive recovery toward the full-hearts reference (never shrinks an earned buffer). */
  private void regenTick() {
    if (healingMultiplier > 0.0 && sharedHealth < maxHealth) {
      sharedHealth = clamp(sharedHealth + healingMultiplier, depletionFloor(), maxHealth);
      setStateDouble(KEY_HEALTH, sharedHealth);
    }
  }

  /** XP-pooled mode: fold any XP players earned into the shared pool, then mirror it back to every bar. */
  private void syncXpPool() {
    if (xpModel == null) {
      return;
    }
    for (PlayerAdapter player : online()) {
      int gained = Math.max(0, player.experiencePoints()) - lastMirroredXp;
      if (gained > 0) {
        sharedHealth = Math.min(MAX_XP_POOL, sharedHealth + gained);
      }
    }
    setStateDouble(KEY_HEALTH, sharedHealth);
    mirrorXpBar();
  }

  /** Push the shared pool value onto every participant's XP bar and reset the feed baseline. */
  private void mirrorXpBar() {
    int target = (int) Math.round(Math.min(MAX_XP_POOL, sharedHealth));
    for (PlayerAdapter player : online()) {
      if (player.experiencePoints() != target) {
        player.setExperiencePoints(target);
      }
    }
    lastMirroredXp = target;
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    // Snap a (re)joining player's XP bar to the current shared pool immediately (do NOT let their own
    // prior XP feed the pool — they join the shared bar, they don't top it up).
    if (xpModel != null && playerAdapter != null && playerAdapter.online()) {
      playerAdapter.setExperiencePoints((int) Math.round(Math.min(MAX_XP_POOL, sharedHealth)));
    }
  }

  @Override
  public void resetPlayer(PlayerAdapter playerAdapter) {
    // Removed mid-run while in XP-pooled mode: restore the normal heart-display scale.
    if (xpModel != null && playerAdapter != null) {
      playerAdapter.resetHealthScale();
    }
  }

  /**
   * Publishes the pool to the declared surface. Called wherever the pool moves.
   *
   * <p>The bar's fill is against the FULL-HEARTS reference, not against the XP ceiling: in XP-pooled
   * mode the pool can legitimately grow past full (earned XP is a life buffer), and a bar that
   * rescaled to the ceiling would read as nearly empty at the exact moment the group is safest. It is
   * clamped for drawing; the absolute figure beside it is what carries the overflow.</p>
   */
  private void pushPool() {
    poolSurface.progress(ROW_POOL, maxHealth <= 0.0 ? 0.0 : sharedHealth / maxHealth);
    poolSurface.number(ROW_AMOUNT, xpModel != null ? Math.round(sharedHealth) : sharedHealth);
    poolSurface.refresh();
  }

  private void describeDebug(HudContext context) {
    if (!context.debug()) {
      return;
    }
    context.debugHeader(displayName());
    context.debugStat("Mode", xpModel != null ? "xp-pool" : "hearts");
    context.debugStat("Pool", String.format(Locale.ROOT, "%.1f / %.0f", sharedHealth, maxHealth));
    context.debugStat("Dmg / heal mult", String.format(Locale.ROOT, "%.2f / %.2f", damageMultiplier, healingMultiplier));
    context.debugStat("Void fatal", voidFatal);
  }

  /** True when this Shared Life has a single holder (per-player Chaos scope, or a 1-player experience)
   *  and is NOT XP-pooled — in which case the holder's health tracks the mode-wide average. */
  private boolean averagingMode() {
    return xpModel == null && online().size() <= 1;
  }

  /** The mean current health of every online player in the wider mode, or empty when none. */
  private OptionalDouble averageModeHealth() {
    double sum = 0.0;
    int count = 0;
    for (PlayerAdapter player : modePopulation()) {
      if (player != null && player.online()) {
        sum += player.health();
        count++;
      }
    }
    return count == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / count);
  }

  /** Pool value at or below which the group wipes and the pool refills. */
  private double depletionFloor() {
    return xpModel != null ? xpModel.deathThresholdXp() : 0.0;
  }

  /** Whether this cause empties the whole pool in one hit. */
  private boolean drainsWholePool(DamageCauseType cause) {
    if (cause != DamageCauseType.VOID) {
      return false;
    }
    return xpModel != null ? xpModel.voidConsumesAllXp() : voidFatal;
  }

  /** Absorbs the hit into the pool; on depletion wipes + refills the group and claims the death. */
  private final class Drain implements DamageContributor {
    @Override
    public int order() {
      return 20;
    }

    @Override
    public void onDamage(DamageContext context) {
      if (averagingMode()) {
        // Single-holder (e.g. the per-player Chaos mode): no shared pool — the holder's displayed
        // health simply tracks the mode-wide AVERAGE (see PoolHealth.value), so let the hit apply
        // normally instead of draining a one-person pool.
        return;
      }
      if (drainsWholePool(context.cause())) {
        sharedHealth = 0.0;
      } else {
        double loss = xpModel != null
            ? Math.ceil(context.amount() * xpModel.xpPerDamagePoint())
            : context.amount() * damageMultiplier;
        sharedHealth = Math.max(0.0, sharedHealth - Math.max(0.0, loss));
      }
      // Absorb the native hit, but do NOT consume the damage: a sibling damage system may still need
      // to see the full hit. (XP Health stands down in XP-pooled mode, so it never double-charges.)
      context.absorb();
      if (sharedHealth <= depletionFloor()) {
        popupAll(PopupType.ELIMINATION, MessageKey.EXPERIENCE_CHALLENGE_SHARED_LIFE_DEPLETED,
            MessageArg.text("player", context.victim().name()));
        // Shared life empty is a REAL death for the whole group: everyone plays the death animation and
        // sees the death screen (keepInventory preserves items + XP), instead of a silent teleport. The
        // pool refills to full and the respawn re-seats each player.
        for (PlayerAdapter player : online()) {
          kill(player);
        }
        sharedHealth = maxHealth;
        if (xpModel != null) {
          mirrorXpBar();
        }
        context.markFatalHandled();
      }
      setStateDouble(KEY_HEALTH, sharedHealth);
    }
  }

  /** Governs the displayed health: every player shows the shared pool (mapped to hearts in XP mode). */
  private final class PoolHealth implements HealthSource {
    @Override
    public int priority() {
      return 10;
    }

    @Override
    public OptionalDouble value(PlayerAdapter player) {
      if (!isParticipant(player)) {
        return OptionalDouble.empty();
      }
      if (xpModel != null) {
        // Map the XP-denominated pool fraction onto the player's real heart range.
        double fraction = maxHealth <= 0.0 ? 0.0 : Math.min(1.0, sharedHealth / maxHealth);
        return OptionalDouble.of(Math.max(1.0, player.maxHealth() * fraction));
      }
      if (averagingMode()) {
        // One holder: mirror the AVERAGE current health of everyone in the mode, so "shared life" still
        // means something. In a literal 1-player experience this is just the player's own health.
        OptionalDouble average = averageModeHealth();
        if (average.isPresent()) {
          return OptionalDouble.of(Math.max(1.0, Math.min(player.maxHealth(), average.getAsDouble())));
        }
      }
      return OptionalDouble.of(Math.min(maxHealth, sharedHealth));
    }

    @Override
    public OptionalDouble scale(PlayerAdapter player) {
      // In XP-pooled mode apply the XP heart scale here (XP Health defers its scale to us).
      if (xpModel != null && xpModel.scaleHeartDisplay()) {
        return OptionalDouble.of(xpModel.displayHealthScale());
      }
      return OptionalDouble.empty();
    }
  }

  // ----- SharedHealthPool capability -----------------------------------------------------------

  @Override
  public double current() {
    return sharedHealth;
  }

  @Override
  public double max() {
    return maxHealth;
  }

  @Override
  public boolean governs(PlayerAdapter player) {
    return isParticipant(player);
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }
}
