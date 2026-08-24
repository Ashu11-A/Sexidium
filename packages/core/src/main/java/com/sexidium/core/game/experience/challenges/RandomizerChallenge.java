package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DropContext;
import com.sexidium.core.game.experience.compose.DropContributor;
import com.sexidium.core.game.experience.compose.DropPhase;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.PopupType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Experience: Randomizer. Every block type is remapped to a different drop, so breaking the same block
 * yields the same surprise item for the session while different blocks yield different items. The pool
 * is drawn from the WHOLE item registry the platform reports (not a small fixed list), and is freshly
 * shuffled each session with a random assignment, so it is genuinely random rather than always the same
 * handful of items. Remapped loot drops into the WORLD at the break location. An optional timer can
 * reshuffle the table, but it is OFF by default. Open-ended: this challenge never ends the match.
 */
public final class RandomizerChallenge extends Challenge {
  private static final List<ItemKey> DEFAULT_POOL = List.of(
      ItemKey.minecraft("diamond"), ItemKey.minecraft("emerald"), ItemKey.minecraft("gold_ingot"),
      ItemKey.minecraft("iron_ingot"), ItemKey.minecraft("copper_ingot"), ItemKey.minecraft("netherite_scrap"),
      ItemKey.minecraft("coal"), ItemKey.minecraft("redstone"), ItemKey.minecraft("lapis_lazuli"),
      ItemKey.minecraft("quartz"), ItemKey.minecraft("amethyst_shard"), ItemKey.minecraft("ender_pearl"),
      ItemKey.minecraft("blaze_rod"), ItemKey.minecraft("slime_ball"), ItemKey.minecraft("gunpowder"),
      ItemKey.minecraft("bone"), ItemKey.minecraft("string"), ItemKey.minecraft("leather"),
      ItemKey.minecraft("feather"), ItemKey.minecraft("egg"), ItemKey.minecraft("apple"),
      ItemKey.minecraft("bread"), ItemKey.minecraft("carrot"), ItemKey.minecraft("potato"),
      ItemKey.minecraft("wheat"), ItemKey.minecraft("sugar_cane"), ItemKey.minecraft("oak_log"),
      ItemKey.minecraft("cobblestone"), ItemKey.minecraft("obsidian"), ItemKey.minecraft("glowstone_dust"),
      ItemKey.minecraft("clay_ball"), ItemKey.minecraft("flint")
  );

  // Per-session block-type -> surprise-item assignment. Not persisted: a fresh session reshuffles, which
  // is the whole point of "genuinely random" (the old persisted table made every run identical).
  private final Map<String, ItemKey> assignedDrops = new HashMap<>();
  private List<ItemKey> shuffledPool = DEFAULT_POOL;

  private ScheduledTask rotationTask;
  private int rotateTotalSeconds;
  private int rotationRemainingSeconds;

  public RandomizerChallenge() {
    super("randomizer", "Randomizer");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.dropContributor(new Remap());
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    if (rotateTotalSeconds > 0) {
      context.line("<light_purple>Randomizer:</light_purple> <white>shuffle in "
          + formatTime(Math.max(0, rotationRemainingSeconds)) + "</white>");
    } else {
      context.line("<light_purple>Randomizer</light_purple> <gray>active</gray>");
    }
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Pool", shuffledPool.size() + " items");
      context.debugStat("Assigned", assignedDrops.size());
      context.debugStat("Rotation", rotateTotalSeconds > 0
          ? (rotationRemainingSeconds + "s / " + rotateTotalSeconds + "s")
          : "off");
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // Always build a fresh, freshly-shuffled pool each session — genuine randomness over the full
    // registry, not a restored deterministic table.
    assignedDrops.clear();
    shuffledPool = buildPool();
    scheduleRotation();
  }

  /**
   * Remaps the source block to its assigned surprise item ({@link DropPhase#GENERATE}, so a later
   * Double Drops multiply scales the remapped item, not the original block). For a manual break the
   * configured fixed amount is dropped; for a sweep the per-block count carries through so a
   * Break-One-Break-All wave yields the remapped item per swept block. Loot drops into the WORLD at the
   * break location by default ({@code drop-to-world: true}) so the surprise item lands on the ground.
   */
  private final class Remap implements DropContributor {
    @Override
    public DropPhase phase() {
      return DropPhase.GENERATE;
    }

    @Override
    public void contribute(DropContext context) {
      if (context.sourceKey() == null) {
        return;
      }
      int total = 0;
      for (ItemStackData stack : context.drops()) {
        total += stack.amount();
      }
      if (total <= 0) {
        total = cfg().getBoolean(configPath("use-natural-drop-amount"), false)
            ? 1
            : Math.max(1, cfg().getInt(configPath("fixed-drop-amount"), 1));
      }
      context.replaceAll(List.of(new ItemStackData(dropFor(context.sourceKey()), total, Map.of())));
    }
  }

  private void rotateDrops() {
    assignedDrops.clear();
    shuffledPool = buildPool();
    if (cfg().getBoolean(configPath("announce-rotations"), true)) {
      announce(MessageKey.EXPERIENCE_CHALLENGE_RANDOMIZER_ROTATED);
      popupAll(PopupType.WARNING, MessageKey.EXPERIENCE_CHALLENGE_RANDOMIZER_ROTATED);
    }
    scheduleRotation();
  }

  /**
   * Per-second countdown that reshuffles the drop table at zero and reschedules itself. The remaining
   * time is surfaced on the unified HUD (no more dedicated boss bar). The tick rides a tracked
   * {@link #runTimer} so the host cancels it on stop.
   */
  private void scheduleRotation() {
    // Disabled by default (0). Set rotate-every-seconds > 0 in config to periodically reshuffle.
    int rotateSeconds = (int) Math.max(0L, cfg().getLong(configPath("rotate-every-seconds"), 0L));
    if (rotateSeconds <= 0) {
      rotateTotalSeconds = 0;
      return;
    }
    rotateTotalSeconds = Math.max(1, rotateSeconds);
    rotationRemainingSeconds = rotateTotalSeconds;
    rotationTask = runTimer(this::tickRotation, 20L, 20L);
  }

  private void tickRotation() {
    if (rotationRemainingSeconds <= 0) {
      if (rotationTask != null) {
        rotationTask.cancel();
        rotationTask = null;
      }
      rotateDrops();
      return;
    }
    rotationRemainingSeconds--;
  }

  private static String formatTime(int totalSeconds) {
    int minutes = totalSeconds / 60;
    int seconds = totalSeconds % 60;
    return minutes > 0 ? String.format("%d:%02d", minutes, seconds) : Integer.toString(seconds);
  }

  /** Genuinely random: each newly-seen block type is assigned a random item from the shuffled pool,
   * cached so the same block keeps the same surprise for the session. */
  private ItemKey dropFor(ItemKey blockKey) {
    return assignedDrops.computeIfAbsent(blockKey.qualifiedName(),
        key -> shuffledPool.get(ThreadLocalRandom.current().nextInt(shuffledPool.size())));
  }

  /**
   * Builds the drop pool: the configured {@code item-pool} if set, otherwise the WHOLE item registry
   * the platform reports ({@link com.sexidium.core.platform.ServerAdapter#allItems()}) minus
   * {@code excluded-items}. Falls back to {@link #DEFAULT_POOL} when the platform reports nothing
   * (headless/test). Always freshly shuffled.
   */
  private List<ItemKey> buildPool() {
    List<String> configured = cfg().getStringList(configPath("item-pool"));
    Set<String> excluded = normalizedSet(cfg().getStringList(configPath("excluded-items")));
    List<ItemKey> pool = new ArrayList<>();
    if (configured != null) {
      for (String entry : configured) {
        if (entry != null && !entry.isBlank() && !excluded.contains(normalize(entry))) {
          pool.add(parseItemKey(entry.trim()));
        }
      }
    }
    if (pool.isEmpty()) {
      for (ItemKey itemKey : gameContext().server().allItems()) {
        if (itemKey != null && !excluded.contains(itemKey.value())) {
          pool.add(itemKey);
        }
      }
    }
    if (pool.isEmpty()) {
      for (ItemKey itemKey : DEFAULT_POOL) {
        if (!excluded.contains(itemKey.value())) {
          pool.add(itemKey);
        }
      }
    }
    if (pool.isEmpty()) {
      pool = new ArrayList<>(DEFAULT_POOL);
    }
    Collections.shuffle(pool, new Random(System.nanoTime()));
    return pool;
  }

  private static Set<String> normalizedSet(List<String> values) {
    Set<String> normalized = new HashSet<>();
    if (values != null) {
      for (String value : values) {
        if (value != null && !value.isBlank()) {
          normalized.add(normalize(value));
        }
      }
    }
    return normalized;
  }

  private static String normalize(String value) {
    String trimmed = value.trim().toLowerCase();
    int separator = trimmed.indexOf(':');
    return separator >= 0 ? trimmed.substring(separator + 1) : trimmed;
  }

  private static ItemKey parseItemKey(String value) {
    int separator = value.indexOf(':');
    return separator > 0
        ? new ItemKey(value.substring(0, separator), value.substring(separator + 1))
        : ItemKey.minecraft(value);
  }
}
