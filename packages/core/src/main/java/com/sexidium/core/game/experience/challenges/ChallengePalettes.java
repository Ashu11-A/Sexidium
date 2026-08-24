package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.ItemKey;

import java.util.Arrays;
import java.util.List;

/**
 * Shared block palettes for the "random block" challenges (Block Deleter, Random Chunks, Walking
 * Blocks). The default is deliberately broad — an empty config palette means "essentially any block
 * in the game can show up", not a short hand-picked list — so the challenges stay surprising and fun
 * instead of being capped to a handful of materials.
 */
final class ChallengePalettes {
  private ChallengePalettes() {
  }

  /** A wide cross-section of the vanilla block set: terrain, wood, ores, decorative, and chaos blocks. */
  static final List<ItemKey> COMPREHENSIVE_BLOCKS = blocks(
      "stone", "granite", "diorite", "andesite", "deepslate", "cobblestone", "mossy_cobblestone",
      "dirt", "coarse_dirt", "grass_block", "podzol", "mud", "clay", "sand", "red_sand", "gravel",
      "sandstone", "red_sandstone", "oak_log", "birch_log", "spruce_log", "jungle_log", "acacia_log",
      "dark_oak_log", "mangrove_log", "cherry_log", "oak_planks", "oak_leaves", "glass", "white_wool",
      "glowstone", "sea_lantern", "bookshelf", "hay_block", "pumpkin", "melon", "cactus", "bamboo_block",
      "ice", "packed_ice", "blue_ice", "snow_block", "bricks", "terracotta", "slime_block", "honey_block",
      "sponge", "netherrack", "soul_sand", "soul_soil", "magma_block", "nether_bricks", "basalt",
      "blackstone", "obsidian", "crying_obsidian", "end_stone", "purpur_block", "prismarine", "calcite",
      "tuff", "dripstone_block", "amethyst_block", "moss_block", "coal_ore", "iron_ore", "copper_ore",
      "gold_ore", "redstone_ore", "lapis_ore", "diamond_ore", "emerald_ore", "nether_quartz_ore",
      "ancient_debris", "coal_block", "iron_block", "copper_block", "gold_block", "redstone_block",
      "lapis_block", "diamond_block", "emerald_block", "netherite_block", "tnt", "water", "lava", "cobweb");

  private static List<ItemKey> blocks(String... ids) {
    return Arrays.stream(ids).map(ItemKey::minecraft).toList();
  }
}
