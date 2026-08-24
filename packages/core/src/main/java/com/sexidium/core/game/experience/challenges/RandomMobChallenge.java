package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random Mob. On a timer, a random mob (drawn from a broad configurable list) spawns next to each player —
 * you never know whether the next arrival is a friendly cow or a creeper. Pairs naturally with Random
 * Skyblock (the "infinite mobs" half of the datapack) but runs as a standalone twist too.
 */
public final class RandomMobChallenge extends Challenge {
  private static final List<String> DEFAULT_MOBS = List.of(
      "zombie", "skeleton", "spider", "creeper", "husk", "stray", "cave_spider", "zombie_villager",
      "enderman", "slime", "witch", "silverfish", "phantom", "drowned", "pillager",
      "pig", "cow", "chicken", "sheep", "rabbit", "wolf", "fox", "bee", "goat", "frog");

  private List<String> mobs;
  private int intervalSeconds;
  private int perSpawn;
  private double equipChance;
  private int remainingSeconds;

  public RandomMobChallenge() {
    super("randommob", "Random Mob");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    mobs = resolveMobs();
    intervalSeconds = Math.max(3, cfg().getInt(configPath("interval-seconds"), 20));
    perSpawn = Math.max(1, cfg().getInt(configPath("mobs-per-spawn"), 1));
    // Probability each spawned hostile gets randomized armour + a weapon (0 disables; passives never armed).
    equipChance = Math.max(0.0, Math.min(1.0, cfg().getDouble(configPath("equip-chance"), 0.5)));
    // Count down once per second so the sidebar can show the time until the next wave.
    remainingSeconds = intervalSeconds;
    runTimer(this::tick, 20L, 20L);
  }

  private void tick() {
    if (remainingSeconds > 0) {
      remainingSeconds--;
      return;
    }
    spawnWave();
    remainingSeconds = intervalSeconds;
  }

  private void spawnWave() {
    WorldAdapter world = world();
    if (world == null || mobs.isEmpty()) {
      return;
    }
    for (PlayerAdapter player : online()) {
      WorldPosition at = player.position();
      if (at == null) {
        continue;
      }
      String mob = mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));
      world.spawnMob(at, mob, perSpawn, equipChance);
      stats().add("randommob.spawned", perSpawn);
    }
  }

  private void describeHud(HudContext context) {
    context.line("<green>Next mob in:</green> <white>" + formatTime(Math.max(0, remainingSeconds)) + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Interval", intervalSeconds + "s");
      context.debugStat("Mob types", mobs == null ? 0 : mobs.size());
      context.debugStat("Per spawn", perSpawn);
      context.debugStat("Equip chance", equipChance);
      context.debugStat("Total spawned", stats().get("randommob.spawned"));
    }
  }

  private static String formatTime(int totalSeconds) {
    return totalSeconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", totalSeconds % 60);
  }

  private List<String> resolveMobs() {
    List<String> configured = cfg().getStringList(configPath("mob-types"));
    if (configured == null || configured.isEmpty()) {
      return DEFAULT_MOBS;
    }
    List<String> resolved = new ArrayList<>();
    for (String entry : configured) {
      if (entry != null && !entry.isBlank()) {
        resolved.add(entry.trim().toLowerCase(Locale.ROOT));
      }
    }
    return resolved.isEmpty() ? DEFAULT_MOBS : resolved;
  }
}
