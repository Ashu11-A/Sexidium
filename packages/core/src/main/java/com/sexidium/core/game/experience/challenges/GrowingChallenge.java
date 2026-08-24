package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.SoundKey;
import java.util.List;

/**
 * Endless Growth ("growing").
 *
 * <p>Rule: every minute every player grows a little. The bigger a player gets, the stronger and
 * tankier they become — there is no upper size cap by default. Growth is shared and persistent:
 * all players advance through the same size stages together, and progress survives disconnects and
 * server restarts via the shared {@code scale} state key.
 *
 * <p>Mechanic: a tracked timer raises the shared scale by {@code scale-increment} each interval and
 * re-applies the new size (plus stage-scaled Strength and optional Resistance) to every online
 * participant. Latecomers immediately snap to the current size when they join.
 */
public final class GrowingChallenge extends Challenge {
  private static final String KEY_SCALE = "scale";

  private long intervalSeconds;
  private double scaleIncrement;
  private double maxScale;
  private int stagesPerStrength;
  private boolean resistance;
  private int maxStrengthAmplifier;
  private int maxResistanceAmplifier;

  public GrowingChallenge() {
    super("growing", "Endless Growth");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<green>Size:</green> <white>" + String.format(java.util.Locale.ROOT, "%.1f", stateDouble("scale", 1.0)) + "x</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Increment", String.format(java.util.Locale.ROOT, "+%.2f / %ds", scaleIncrement, intervalSeconds));
      context.debugStat("Max scale", maxScale > 0.0 ? String.format(java.util.Locale.ROOT, "%.1fx", maxScale) : "∞");
      context.debugStat("Stages / strength", stagesPerStrength);
      context.debugStat("Resistance", resistance);
      context.debugStat("Amp cap str/res", maxStrengthAmplifier + " / " + maxResistanceAmplifier);
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    intervalSeconds = cfg().getLong(configPath("interval-seconds"), 60L);
    scaleIncrement = cfg().getDouble(configPath("scale-increment"), 0.1);
    maxScale = cfg().getDouble(configPath("max-scale"), 0.0);
    stagesPerStrength = cfg().getInt(configPath("stages-per-strength"), 3);
    resistance = cfg().getBoolean(configPath("resistance"), true);
    // 0 = uncapped: Strength keeps climbing with size (one-shotting everything is the point);
    // Resistance is capped at 3 by default so there are still some stakes (set 0 to go invincible).
    maxStrengthAmplifier = cfg().getInt(configPath("max-strength-amplifier"), 0);
    maxResistanceAmplifier = cfg().getInt(configPath("max-resistance-amplifier"), 3);
    if (!stateHas(KEY_SCALE)) {
      setStateDouble(KEY_SCALE, 1.0);
    }
    applyToAll();
    long period = Math.max(20L, intervalSeconds * 20L);
    runTimer(this::growTick, period, period);
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    applyTo(playerAdapter);
  }

  @Override
  public void resetPlayer(PlayerAdapter playerAdapter) {
    // Removed mid-run (live edit / chaos cycle): return the player to normal size at once. The
    // Strength/Resistance buffs carry a finite duration and lapse on their own.
    if (playerAdapter != null) {
      playerAdapter.resetScale();
    }
  }

  // Body scale (and Strength/Resistance effects) are reset by the host on exit — leaving a match
  // routes through resetStatuses() (GameManager.releaseToLobby / stop's releaseAndReset), which resets
  // scale, health-scale, effects and inventory. The challenge only DRIVES growth, it does not clean up.

  private void growTick() {
    double scale = stateDouble(KEY_SCALE, 1.0) + scaleIncrement;
    if (maxScale > 0.0) {
      scale = Math.min(scale, maxScale);
    }
    setStateDouble(KEY_SCALE, scale);
    applyToAll();
    for (PlayerAdapter playerAdapter : online()) {
      playerAdapter.playSound(new SoundKey("minecraft:entity.player.levelup"), 0.6f, 0.7f);
      playerAdapter.sendActionBar("<green>You grew! Size " + String.format("%.1f", scale));
    }
  }

  private void applyToAll() {
    for (PlayerAdapter playerAdapter : online()) {
      applyTo(playerAdapter);
    }
  }

  private void applyTo(PlayerAdapter playerAdapter) {
    double scale = stateDouble(KEY_SCALE, 1.0);
    playerAdapter.setScale(scale);
    int stage = (int) Math.round((scale - 1.0) / Math.max(0.0001, scaleIncrement));
    int duration = (int) Math.max(40L, intervalSeconds * 20L + 60L);
    if (stage >= stagesPerStrength) {
      int strengthAmp = stage / Math.max(1, stagesPerStrength) - 1;
      if (maxStrengthAmplifier > 0) {
        strengthAmp = Math.min(strengthAmp, maxStrengthAmplifier);
      }
      if (strengthAmp >= 0) {
        playerAdapter.addEffect("strength", strengthAmp, duration);
      }
    }
    if (resistance && stage >= stagesPerStrength) {
      int resAmp = stage / (Math.max(1, stagesPerStrength) * 2);
      if (maxResistanceAmplifier > 0) {
        resAmp = Math.min(resAmp, maxResistanceAmplifier);
      }
      if (resAmp >= 0) {
        playerAdapter.addEffect("resistance", resAmp, duration);
      }
    }
  }
}
