package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.BlockChangeVeto;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Block Deleter.
 *
 * <p>RULE: every interval, one random block type from the palette is permanently
 * deleted from the world. A deleted type is recorded in persistent shared state so it
 * can never be chosen again (the deletion survives disconnect/restart). Each tick every
 * online player has the chosen block type removed from the world around them within a
 * configurable radius, and protected blocks (bedrock, etc.) are never eligible.
 *
 * <p>MECHANIC: a repeating timer fires {@link #deleteTick()}; the period is derived from
 * {@code interval-seconds} read in {@link #onStart}. Candidates are palette entries that
 * are neither protected nor already deleted; when the candidate set is empty the run is
 * "out of blocks" and the tick is a no-op.
 */
public final class BlockDeleterChallenge extends Challenge {
  private static final String KEY_DELETED_PREFIX = "deleted.";

  // Empty config palette -> any of these (essentially the whole block set) can be the one deleted.
  private static final List<ItemKey> DEFAULT_PALETTE = ChallengePalettes.COMPREHENSIVE_BLOCKS;

  private final Random random = new Random();
  private List<ItemKey> palette = DEFAULT_PALETTE;
  private Set<String> protectedValues = Set.of();

  public BlockDeleterChallenge() {
    super("blockdeleter", "Block Deleter");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    // A type deleted forever must never be re-placed (e.g. by Walking Blocks) — otherwise the
    // "permanently deleted" invariant is silently broken when the type reappears under a player.
    registry.blockVeto(new BlockChangeVeto() {
      @Override
      public boolean allowsPlace(WorldPosition position, ItemKey type) {
        return type == null || !stateHas(KEY_DELETED_PREFIX + type.value());
      }
    });
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Types deleted:</gray> <white>" + stats().get("blockdeleter.deleted") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Palette", palette.size() + " blocks");
      context.debugStat("Protected", protectedValues.size());
      context.debugStat("Remaining", Math.max(0, palette.size() - protectedValues.size() - stats().get("blockdeleter.deleted")));
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    random.setSeed(System.nanoTime());
    palette = buildPalette();
    protectedValues = buildProtectedSet();
    long intervalSeconds = cfg().getLong(configPath("interval-seconds"), 60L);
    long period = Math.max(20L, intervalSeconds * 20L);
    runTimer(this::deleteTick, period, period);
  }

  private void deleteTick() {
    List<ItemKey> candidates = new ArrayList<>();
    for (ItemKey itemKey : palette) {
      String value = normalize(itemKey.value());
      if (protectedValues.contains(value)) {
        continue;
      }
      if (stateHas(KEY_DELETED_PREFIX + itemKey.value())) {
        continue;
      }
      candidates.add(itemKey);
    }
    if (candidates.isEmpty()) {
      return;
    }
    ItemKey chosen = candidates.get(random.nextInt(candidates.size()));
    setStateInt(KEY_DELETED_PREFIX + chosen.value(), 1);
    // "Delete this type for ever" must never be pointed at a protected block: losing every End portal
    // frame in the world would make it unbeatable. The guard answers for every mode at once.
    if (blocks().guard().isProtected(chosen)) {
      return;
    }
    stats().add("blockdeleter.deleted", 1);

    double radius = cfg().getDouble(configPath("radius"), 96.0);
    boolean playSound = cfg().getBoolean(configPath("play-sound"), true);

    for (PlayerAdapter player : online()) {
      WorldAdapter world = player.world();
      WorldPosition position = player.position();
      if (world != null && position != null) {
        world.removeBlocksOfType(position, radius, chosen, false);
      }
    }
    announceDeletion(chosen, playSound);
  }

  private void announceDeletion(ItemKey chosen, boolean playSound) {
    TitleSpec titleSpec =
        new TitleSpec("<red>Block Deleted", "<gray>" + chosen.value(), 200L, 1500L, 400L);
    SoundKey soundKey = new SoundKey("minecraft:block.glass.break");
    for (PlayerAdapter player : online()) {
      player.showTitle(titleSpec);
      if (playSound) {
        player.playSound(soundKey, 0.8F, 0.7F);
      }
    }
  }

  private List<ItemKey> buildPalette() {
    List<String> configured = cfg().getStringList(configPath("palette"));
    if (configured == null || configured.isEmpty()) {
      return DEFAULT_PALETTE;
    }
    List<ItemKey> parsed = new ArrayList<>();
    for (String entry : configured) {
      ItemKey itemKey = parseItemKey(entry);
      if (itemKey != null) {
        parsed.add(itemKey);
      }
    }
    return parsed.isEmpty() ? DEFAULT_PALETTE : parsed;
  }

  /**
   * Blocks this mode will not choose as its victim. The shared world-integrity guard is the floor — no
   * mode may ever delete those — and this challenge's own {@code protected-blocks} adds to it rather than
   * replacing it, so a server can shield extra blocks from deletion without being able to unshield the
   * ones that keep a world beatable.
   */
  private Set<String> buildProtectedSet() {
    Set<String> values = new HashSet<>(blocks().guard().preservedValues());
    List<String> configured = cfg().getStringList(configPath("protected-blocks"));
    if (configured != null) {
      for (String entry : configured) {
        ItemKey itemKey = parseItemKey(entry);
        if (itemKey != null) {
          values.add(normalize(itemKey.value()));
        }
      }
    }
    return values;
  }

  private ItemKey parseItemKey(String entry) {
    if (entry == null) {
      return null;
    }
    String trimmed = entry.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.contains(":")) {
      String[] parts = trimmed.split(":", 2);
      return new ItemKey(parts[0].trim().toLowerCase(Locale.ROOT), parts[1].trim().toLowerCase(Locale.ROOT));
    }
    return ItemKey.minecraft(trimmed.toLowerCase(Locale.ROOT));
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
