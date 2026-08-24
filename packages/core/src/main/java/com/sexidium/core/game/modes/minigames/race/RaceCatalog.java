package com.sexidium.core.game.modes.minigames.race;

import java.util.List;
import java.util.Random;

/**
 * The Race difficulty model: the default item/structure pools for each tier plus the rule that builds a
 * round's three-target mix. The mix is deliberately varied (e.g. two easy + one medium, or two medium +
 * one hard) and never contains two hard targets, so a round is always achievable but rarely trivial.
 *
 * <p>Easy targets are common ores (gathered by mining/smelting); medium and hard tiers add tougher items
 * and, when configured, <em>structure</em> objectives (reach the zone of a structure that spawns into the
 * temporary map). Pure logic with no platform dependency, so the mix rule is unit-tested directly.</p>
 */
public final class RaceCatalog {
  public static final String EASY = "easy";
  public static final String MEDIUM = "medium";
  public static final String HARD = "difficult";

  /** Every common ore drop, used as the default easy pool ("all the game's ores"). */
  public static final List<String> DEFAULT_EASY_ITEMS = List.of(
      "coal", "raw_iron", "iron_ingot", "raw_copper", "copper_ingot", "raw_gold", "gold_ingot",
      "redstone", "lapis_lazuli", "quartz", "diamond", "emerald", "amethyst_shard");

  public static final List<String> DEFAULT_MEDIUM_ITEMS = List.of(
      "diamond", "ender_pearl", "blaze_rod", "gold_block", "obsidian", "slime_ball", "honey_bottle");

  public static final List<String> DEFAULT_HARD_ITEMS = List.of(
      "netherite_scrap", "nether_star", "elytra", "dragon_breath", "totem_of_undying", "heart_of_the_sea");

  /** Default structure objectives (reach the zone): medium tier. */
  public static final List<String> DEFAULT_MEDIUM_STRUCTURES = List.of(
      "village", "ruined_portal", "pillager_outpost", "desert_pyramid", "jungle_pyramid");

  /** Default structure objectives (reach the zone): hard tier. */
  public static final List<String> DEFAULT_HARD_STRUCTURES = List.of(
      "fortress", "bastion_remnant", "stronghold", "end_city", "ancient_city", "mansion");

  /**
   * The valid three-tier mixes for a round. None contains two hard targets; each blends tiers so a round
   * is varied. One is chosen at random per match.
   */
  private static final List<List<String>> COMBOS = List.of(
      List.of(EASY, EASY, MEDIUM),
      List.of(EASY, MEDIUM, MEDIUM),
      List.of(MEDIUM, MEDIUM, MEDIUM),
      List.of(EASY, EASY, HARD),
      List.of(EASY, MEDIUM, HARD),
      List.of(MEDIUM, MEDIUM, HARD));

  private RaceCatalog() {
  }

  /** Rolls one round's tier mix (three tiers, never two hard). */
  public static List<String> rollDifficultyMix(Random random) {
    return COMBOS.get(random.nextInt(COMBOS.size()));
  }

  /** Returns true if the combo never asks for two or more hard targets. */
  public static boolean isValidMix(List<String> tiers) {
    long hard = tiers.stream().filter(HARD::equals).count();
    return hard <= 1;
  }

  public static List<String> defaultItems(String tier) {
    return switch (tier) {
      case MEDIUM -> DEFAULT_MEDIUM_ITEMS;
      case HARD -> DEFAULT_HARD_ITEMS;
      default -> DEFAULT_EASY_ITEMS;
    };
  }

  /** Default structures for a tier; empty for easy (easy is always an item objective). */
  public static List<String> defaultStructures(String tier) {
    return switch (tier) {
      case MEDIUM -> DEFAULT_MEDIUM_STRUCTURES;
      case HARD -> DEFAULT_HARD_STRUCTURES;
      default -> List.of();
    };
  }

  public static int defaultPoints(String tier) {
    return switch (tier) {
      case MEDIUM -> 3;
      case HARD -> 5;
      default -> 1;
    };
  }
}
