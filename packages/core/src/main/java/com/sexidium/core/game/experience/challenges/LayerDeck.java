package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.Random;

/**
 * Pure "what is the next layer" roll for the Random-Layers challenge, kept free of any world/platform
 * dependency so the block/TNT/mob split can be unit-tested directly.
 *
 * <p>Each rolled {@link Layer} always carries a {@code block} (the slab material — or the floor of a
 * mob-spawn layer); a {@link Kind#TNT_TRAP} additionally lays pressure plates on top, and a
 * {@link Kind#MOB_SPAWN} additionally spawns {@code mobCount} of {@code mob} on the slab.</p>
 */
final class LayerDeck {
  enum Kind { BLOCK, TNT_TRAP, MOB_SPAWN }

  record Layer(Kind kind, ItemKey block, String mob, int mobCount) {
  }

  private final List<ItemKey> palette;
  private final List<String> mobTypes;
  private final double tntChance;
  private final double mobChance;
  private final int mobCountMin;
  private final int mobCountMax;

  LayerDeck(List<ItemKey> palette, List<String> mobTypes, double tntChance, double mobChance,
      int mobCountMin, int mobCountMax) {
    if (palette == null || palette.isEmpty()) {
      throw new IllegalArgumentException("Layer palette cannot be empty.");
    }
    this.palette = List.copyOf(palette);
    this.mobTypes = mobTypes == null ? List.of() : List.copyOf(mobTypes);
    // A TNT and a mob layer must together leave room for at least some ordinary block layers.
    this.tntChance = clampChance(tntChance);
    this.mobChance = clampChance(mobChance);
    this.mobCountMin = Math.max(1, mobCountMin);
    this.mobCountMax = Math.max(this.mobCountMin, mobCountMax);
  }

  /**
   * Maps a uniform {@code roll} in {@code [0,1)} to a layer kind: the first {@code tntChance} of the
   * range is a TNT trap, the next {@code mobChance} a mob-spawn (only when mob types exist), the rest an
   * ordinary block layer. Pure, so the boundaries are directly testable.
   */
  Kind kindFor(double roll) {
    if (roll < tntChance) {
      return Kind.TNT_TRAP;
    }
    if (!mobTypes.isEmpty() && roll < tntChance + mobChance) {
      return Kind.MOB_SPAWN;
    }
    return Kind.BLOCK;
  }

  Layer roll(Random rng) {
    Kind kind = kindFor(rng.nextDouble());
    return switch (kind) {
      case TNT_TRAP -> new Layer(Kind.TNT_TRAP, ItemKey.minecraft("tnt"), null, 0);
      case MOB_SPAWN -> {
        String mob = mobTypes.get(rng.nextInt(mobTypes.size()));
        int span = mobCountMax - mobCountMin + 1;
        int count = mobCountMin + (span <= 1 ? 0 : rng.nextInt(span));
        yield new Layer(Kind.MOB_SPAWN, randomBlock(rng), mob, count);
      }
      case BLOCK -> new Layer(Kind.BLOCK, randomBlock(rng), null, 0);
    };
  }

  private ItemKey randomBlock(Random rng) {
    return palette.get(rng.nextInt(palette.size()));
  }

  private static double clampChance(double value) {
    return Math.max(0.0, Math.min(1.0, value));
  }
}
