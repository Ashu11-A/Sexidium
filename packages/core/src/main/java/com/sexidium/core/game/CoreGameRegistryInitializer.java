package com.sexidium.core.game;

import com.sexidium.core.game.chaos.ChaosGame;
import com.sexidium.core.game.experience.ExperienceGame;
import com.sexidium.core.game.modes.minigames.CombatGame;
import com.sexidium.core.game.modes.minigames.FugitiveGame;
import com.sexidium.core.game.modes.minigames.GatherGame;
import com.sexidium.core.game.modes.minigames.RaceGame;
import com.sexidium.core.game.modes.minigames.TntWarGame;

import java.util.List;

public final class CoreGameRegistryInitializer {
  public static final String CATEGORY_MINIGAMES = "minigames";
  public static final String CATEGORY_EXPERIENCE = "experience";
  public static final String CATEGORY_CHAOS = "chaos";

  private CoreGameRegistryInitializer() {
  }

  public static GameRegistry create() {
    GameRegistry registry = new GameRegistry();
    register(registry);
    return registry;
  }

  public static void register(GameRegistry registry) {
    if (registry == null) {
      return;
    }
    registerMinigames(registry);
    registerExperience(registry);
    registerChaos(registry);
  }

  /**
   * Chaos: one shared, open-ended world that assigns each player a random set of challenge twists and
   * reshuffles them every few minutes. Platform-agnostic (challenges are), so it auto-registers on both
   * platforms. Started via {@code /sx start chaos}; others join with {@code /sx join chaos}.
   */
  private static void registerChaos(GameRegistry registry) {
    registry.register(new GameModeDescriptor(
        ChaosGame.MODE_ID, CATEGORY_CHAOS, "Chaos", 1,
        List.of("roulette", "random"), "nether_star",
        List.of("<gray>Random challenge twists, reshuffled often.</gray>")),
        (ctx, id, args) -> new ChaosGame(ctx, args));
  }

  // Every minigame requires at least 2 players: a match needs an opponent. The descriptor minimum
  // here seeds each lobby's minimum (ModeResolver) and is shown in every menu, so keeping it >= 2
  // here is what guarantees the rule across the GUI and matchmaking.
  private static void registerMinigames(GameRegistry registry) {
    registry.register(new GameModeDescriptor(
        "race", CATEGORY_MINIGAMES, "Race for Item", 2,
        List.of("raceforitem", "item"), "diamond",
        List.of("<gray>Sprint to find the listed items first.</gray>")),
        (ctx, id, args) -> new RaceGame(ctx, args));

    registry.register(new GameModeDescriptor(
        "gather", CATEGORY_MINIGAMES, "Gather and Duel", 2,
        List.of("duel", "gatherandduel", "gatherduel"), "iron_pickaxe",
        List.of("<gray>Mine resources, gear up, then duel.</gray>")),
        (ctx, id, args) -> new GatherGame(ctx, args));

    registry.register(new GameModeDescriptor(
        "tntwar", CATEGORY_MINIGAMES, "TNT War", 2,
        List.of("tnt", "war"), "tnt",
        List.of("<gray>Blast the enemy team's blocks and players.</gray>")),
        (ctx, id, args) -> new TntWarGame(ctx, args));

    registry.register(new GameModeDescriptor(
        "combat", CATEGORY_MINIGAMES, "Combat Item Mode", 2,
        List.of("kit", "kitpvp"), "iron_sword",
        List.of("<gray>Kit PvP — last team standing wins.</gray>")),
        (ctx, id, args) -> new CombatGame(ctx, args));

    registry.register(new GameModeDescriptor(
        "fugitive", CATEGORY_MINIGAMES, "Fugitive", 2,
        List.of("manhunt", "thefugitive", "fled"), "compass",
        List.of("<gray>Hunters chase the fugitive before they escape.</gray>")),
        (ctx, id, args) -> new FugitiveGame(ctx, args));
  }

  /**
   * The composable experience. It is started as {@code /sx start experience <challenge ids...>}; the
   * challenge ids arrive as the mode args and are composed by {@link ExperienceGame}.
   */
  private static void registerExperience(GameRegistry registry) {
    registry.register(new GameModeDescriptor(
        ExperienceGame.MODE_ID, CATEGORY_EXPERIENCE, "Experience", 1,
        List.of("exp", "experiences"), "grass_block",
        List.of("<gray>Composable survival challenges that persist.</gray>")),
        (ctx, id, args) -> new ExperienceGame(ctx, args));
  }
}
