package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.challenges.LookMultiplierLadder.Milestone;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.SoundKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Look Multiplies ("Everything I look at multiplies").
 *
 * <p>Rule: whatever entity sits in a player's crosshair is cloned on a repeating look-tick — mobs, dropped
 * items, and arrows alike. The strength of the effect (copies per look) is a single shared, persisted
 * <em>multiplier</em> that climbs as the party clears a ladder of item milestones (three cobblestone, one
 * iron, one diamond, obsidian, a blaze rod, netherite …), mirroring the video's advancement gates.</p>
 *
 * <p>The look-tick delegates the ray-trace + duplication to the platform via
 * {@link PlayerAdapter#duplicateLookedAtEntity} (Paper implements it; unported platforms no-op). Mob
 * spawns are bounded by {@code max-copies-per-tick} so the late-game snowball cannot fry the server.</p>
 *
 * <p>UI: the climbing multiplier is shown on the vanilla XP bar above the hotbar (the "x" number the
 * player watches climb) with its green fill tracking progress toward the next milestone; the next required
 * item is rendered on the shared right-side info panel as a client-localized item name.</p>
 */
public final class LookMultipliesChallenge extends Challenge {
  private static final String KEY_LEVEL = "level";

  private LookMultiplierLadder ladder;
  private long lookIntervalTicks;
  private double lookDistance;
  private int startingMultiplier;
  private int maxCopiesPerTick;
  private boolean multiplyMobs;
  private boolean multiplyItems;
  private boolean multiplyProjectiles;
  private boolean playSound;
  private boolean announceLevelUp;

  public LookMultipliesChallenge() {
    super("lookmultiplies", "Look Multiplies");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    lookIntervalTicks = Math.max(1L, cfg().getLong(configPath("look-interval-ticks"), 20L));
    lookDistance = Math.max(1.0, cfg().getDouble(configPath("look-distance"), 6.0));
    startingMultiplier = Math.max(1, cfg().getInt(configPath("starting-multiplier"), 1));
    // Bounds the "x60" snowball: at most this many copies per player per look-tick, whatever the multiplier.
    maxCopiesPerTick = Math.max(1, cfg().getInt(configPath("max-copies-per-tick"), 20));
    multiplyMobs = cfg().getBoolean(configPath("multiply-mobs"), true);
    multiplyItems = cfg().getBoolean(configPath("multiply-items"), true);
    multiplyProjectiles = cfg().getBoolean(configPath("multiply-projectiles"), true);
    playSound = cfg().getBoolean(configPath("play-sound"), true);
    announceLevelUp = cfg().getBoolean(configPath("announce-level-up"), true);
    ladder = new LookMultiplierLadder(loadMilestones(), startingMultiplier);

    if (!stateHas(KEY_LEVEL)) {
      setStateInt(KEY_LEVEL, 0);
    }
    for (PlayerAdapter player : online()) {
      updateBar(player, stateInt(KEY_LEVEL, 0));
    }
    runTimer(this::lookTick, lookIntervalTicks, lookIntervalTicks);
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    updateBar(playerAdapter, stateInt(KEY_LEVEL, 0));
  }

  @Override
  public void resetPlayer(PlayerAdapter playerAdapter) {
    // Removed mid-run (live edit / chaos cycle): drop the repurposed XP bar back to empty. Leaving a match
    // routes through the host's resetStatuses(), which also clears it.
    if (playerAdapter != null) {
      playerAdapter.setExperienceBar(0, 0f);
    }
  }

  private void lookTick() {
    int level = advanceLevel();
    int multiplier = ladder.multiplierAt(level);
    int copies = Math.min(multiplier, maxCopiesPerTick);
    for (PlayerAdapter player : online()) {
      int spawned = player.duplicateLookedAtEntity(
          lookDistance, copies, multiplyItems, multiplyMobs, multiplyProjectiles);
      if (spawned > 0) {
        stats().add("lookmultiplies.count", spawned);
        if (playSound) {
          player.playSound(new SoundKey("minecraft:entity.item.pickup"), 0.4f, 1.6f);
        }
      }
      updateBar(player, level);
    }
  }

  /** Clears every milestone the party's collective inventory now satisfies, announcing a step up. */
  private int advanceLevel() {
    int current = stateInt(KEY_LEVEL, 0);
    int advanced = ladder.advance(current, this::collectiveHeld);
    if (advanced > current) {
      setStateInt(KEY_LEVEL, advanced);
      if (announceLevelUp) {
        int multiplier = ladder.multiplierAt(advanced);
        for (PlayerAdapter player : online()) {
          player.sendActionBar("<gold>Multiplier <white>x" + multiplier + "</white>!</gold>");
          player.playSound(new SoundKey("minecraft:entity.player.levelup"), 0.7f, 1.0f);
        }
      }
    }
    return advanced;
  }

  /** Total count of {@code item} held across every online participant's inventory. */
  private int collectiveHeld(ItemKey item) {
    if (item == null) {
      return 0;
    }
    int total = 0;
    for (PlayerAdapter player : online()) {
      total += player.inventory().count(item);
    }
    return total;
  }

  /** Drives the XP bar: level number = current multiplier, green fill = progress toward the next rung. */
  private void updateBar(PlayerAdapter player, int level) {
    if (player == null) {
      return;
    }
    player.setExperienceBar(ladder.multiplierAt(level), ladder.progress(level, this::collectiveHeld));
  }

  private void describeHud(HudContext context) {
    int level = stateInt(KEY_LEVEL, 0);
    context.line("<gold>Multiplier:</gold> <white>x" + ladder.multiplierAt(level) + "</white>");
    Milestone next = ladder.next(level);
    if (next == null) {
      context.line("<gray>Next:</gray> <green>maxed out</green>");
    } else {
      context.line("<gray>Next:</gray> <white>" + next.count() + "× " + itemName(next.item())
          + "</white> <gray>(" + Math.min(collectiveHeld(next.item()), next.count()) + "/" + next.count() + ")</gray>");
    }
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Level / rungs", level + " / " + ladder.size());
      context.debugStat("Copies / tick", Math.min(ladder.multiplierAt(level), maxCopiesPerTick)
          + " (cap " + maxCopiesPerTick + ")");
      context.debugStat("Interval", lookIntervalTicks + "t");
      context.debugStat("Distance", String.format(java.util.Locale.ROOT, "%.1f", lookDistance));
      context.debugStat("Duplicated total", stats().get("lookmultiplies.count"));
    }
  }

  /** A client-localized {@code <lang:item.*>} name, falling back to a prettified id when unavailable. */
  private String itemName(ItemKey item) {
    String key = gameContext().server().itemTranslationKey(item);
    if (key != null && !key.isBlank()) {
      return "<lang:" + key + ">";
    }
    return prettyName(item.value());
  }

  private static String prettyName(String value) {
    StringBuilder builder = new StringBuilder();
    for (String part : value.split("_")) {
      if (part.isEmpty()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(' ');
      }
      builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return builder.toString();
  }

  /**
   * Reads the configured milestone ladder; falls back to the video's progression when config is absent
   * (e.g. the no-op properties config in tests) so the challenge always has a working ladder.
   */
  private List<Milestone> loadMilestones() {
    List<Milestone> milestones = new ArrayList<>();
    for (Map<String, Object> entry : cfg().getMapList(configPath("milestones"))) {
      ItemKey item = ItemKey.parse(string(entry.get("item")));
      if (item == null) {
        continue;
      }
      milestones.add(new Milestone(item, intOf(entry.get("count"), 1), intOf(entry.get("multiplier"), 2)));
    }
    return milestones.isEmpty() ? defaultMilestones() : milestones;
  }

  private static List<Milestone> defaultMilestones() {
    return List.of(
        new Milestone(ItemKey.minecraft("cobblestone"), 3, 2),
        new Milestone(ItemKey.minecraft("iron_ingot"), 1, 3),
        new Milestone(ItemKey.minecraft("diamond"), 1, 5),
        new Milestone(ItemKey.minecraft("obsidian"), 1, 10),
        new Milestone(ItemKey.minecraft("blaze_rod"), 1, 25),
        new Milestone(ItemKey.minecraft("ender_pearl"), 1, 30),
        new Milestone(ItemKey.minecraft("netherite_ingot"), 1, 40),
        new Milestone(ItemKey.minecraft("ender_eye"), 1, 60));
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static int intOf(Object value, int fallback) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
