package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.PlayerAdvancementGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.List;

/**
 * Shrinking Achievements: the world border steadily closes in over the course of the experience,
 * emulating a border that shrinks every time an achievement would be earned. Open-ended: the border
 * simply clamps at its minimum size and never ends the match or eliminates anyone.
 */
public final class ShrinkingAchievementsChallenge extends Challenge {
  private static final String KEY_SIZE = "size";

  private double currentSize;
  private double minimumSize;
  private double step;
  private double shrinkMultiplier;

  public ShrinkingAchievementsChallenge() {
    super("shrinkingachievements", "Shrinking Achievements");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    double configuredInitialSize = cfg().getDouble(configPath("initial-size"), 0.0);
    double initialSize = configuredInitialSize > 0.0
        ? configuredInitialSize
        : cfg().getDouble("worlds.temp.world-size", 750.0);
    // Persist the shrunk size so a restart resumes the closed-in border instead of resetting it,
    // staying in sync with the other persistent challenges.
    currentSize = stateDouble(KEY_SIZE, initialSize);
    if (!stateHas(KEY_SIZE)) {
      setStateDouble(KEY_SIZE, currentSize);
    }
    minimumSize = Math.max(1.0, cfg().getDouble(configPath("minimum-size"), 32.0));
    step = Math.max(0.0, cfg().getDouble(configPath("shrink-blocks"), 25.0));
    shrinkMultiplier = Math.max(0.0, cfg().getDouble(configPath("shrink-multiplier"), 1.0));
    applyBorder(null);
  }

  private void describeHud(HudContext context) {
    context.line("<yellow>Border:</yellow> <white>" + (int) currentSize + "</white><gray> blocks</gray>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Minimum", (int) minimumSize + " blocks");
      context.debugStat("Shrink step", String.format(java.util.Locale.ROOT, "%.0f x%.2f", step, shrinkMultiplier));
      context.debugStat("At minimum", currentSize <= minimumSize);
    }
  }

  @Override
  public void onPlayerAdvancement(PlayerAdvancementGameEvent event) {
    if (!isParticipant(event.playerAdapter())) {
      return;
    }
    if (cfg().getBoolean(configPath("ignore-recipes"), true) && event.advancementId() != null
        && event.advancementId().contains("recipes/")) {
      return;
    }
    shrink(event.playerAdapter(), event.advancementId());
  }

  @Override
  public void onStop() {
    WorldAdapter world = activeWorld(null);
    if (world != null && cfg().getBoolean(configPath("restore-border-on-stop"), true)) {
      world.resetBorder();
    }
  }

  private void shrink(PlayerAdapter player, String advancementId) {
    if (currentSize <= minimumSize) {
      return;
    }
    currentSize = Math.max(minimumSize, Math.min(currentSize - step, currentSize * shrinkMultiplier));
    setStateDouble(KEY_SIZE, currentSize);
    applyBorder(player);
    if (cfg().getBoolean(configPath("announce-shrinks"), true)) {
      MessageArg playerArg = MessageArg.text("player", player.name());
      MessageArg advancementArg = MessageArg.text("advancement", advancementId == null ? "unknown" : advancementId);
      MessageArg sizeArg = MessageArg.text("size", String.valueOf((int) currentSize));
      announce(MessageKey.EXPERIENCE_CHALLENGE_ADVANCEMENT_SHRINK, playerArg, advancementArg, sizeArg);
      popupAll(PopupType.WARNING, MessageKey.EXPERIENCE_CHALLENGE_ADVANCEMENT_SHRINK, playerArg, advancementArg, sizeArg);
    }
  }

  private void applyBorder(PlayerAdapter preferredPlayer) {
    WorldAdapter world = activeWorld(preferredPlayer);
    if (world == null) {
      return;
    }
    WorldPosition center = world.spawnPosition();
    double centerX = center == null ? 0.0 : center.coordinateX();
    double centerZ = center == null ? 0.0 : center.coordinateZ();
    int transitionSeconds = (int) Math.max(0L, cfg().getLong(configPath("transition-seconds"), 5L));
    world.setBorder(new WorldBorderSpec(centerX, centerZ, currentSize, transitionSeconds, 0.2));
  }

  private WorldAdapter activeWorld(PlayerAdapter preferredPlayer) {
    // In an experience the border belongs to the experience world; only when that is absent
    // (e.g. in tests) do we fall back to the advancing player's current world.
    WorldAdapter experienceWorld = world();
    if (experienceWorld != null) {
      return experienceWorld;
    }
    if (preferredPlayer != null && preferredPlayer.world() != null) {
      return preferredPlayer.world();
    }
    for (PlayerAdapter player : online()) {
      if (player.world() != null) {
        return player.world();
      }
    }
    return null;
  }
}
