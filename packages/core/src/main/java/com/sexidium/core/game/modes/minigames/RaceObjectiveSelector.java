package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.modes.minigames.race.RaceCatalog;
import com.sexidium.core.game.modes.minigames.race.RaceObjective;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Pure objective-selection logic for {@link RaceGame}: rolls a round's tier mix (via {@link RaceCatalog})
 * and turns each tier into a concrete item-or-structure {@link RaceObjective}, honouring the configured
 * pools, amounts and points. Reads config through a small {@link Config} seam so the rule stays free of
 * any platform dependency and is unit-testable; {@code RaceGame} supplies the lambdas.
 */
final class RaceObjectiveSelector {

  /** Narrow config view used by objective selection — supplied by the facade. */
  interface Config {
    boolean getBoolean(String key, boolean fallback);

    double getDouble(String key, double fallback);

    int getInt(String key, int fallback);

    String getString(String key, String fallback);

    List<String> getStringList(String key);

    List<Map<String, Object>> getMapList(String key);
  }

  private final Random random;
  private final Config config;

  RaceObjectiveSelector(Random random, Config config) {
    this.random = random;
    this.config = config;
  }

  /** Rolls a fresh round of objectives, returning the chosen list. */
  List<RaceObjective> chooseObjectives() {
    List<RaceObjective> objectives = new ArrayList<>();
    Set<String> used = new LinkedHashSet<>();
    boolean structuresEnabled = config.getBoolean("structures.enabled", true);
    double structureChance = config.getDouble("structures.chance", 0.5);
    for (String tier : RaceCatalog.rollDifficultyMix(random)) {
      List<String> structurePool = structurePool(tier);
      if (structuresEnabled && !structurePool.isEmpty() && random.nextDouble() < structureChance) {
        addStructure(tier, structurePool, used, objectives);
      } else {
        addItem(tier, used, objectives);
      }
    }
    return objectives;
  }

  private void addItem(String tier, Set<String> used, List<RaceObjective> objectives) {
    List<Map<String, Object>> configured = config.getMapList("targets." + tier + ".items");
    // One item per difficulty: a target is cleared by obtaining a single item of its tier.
    int defaultAmount = 1;
    int amount = Math.max(1, config.getInt("targets." + tier + ".amount", defaultAmount));
    int points = Math.max(1, config.getInt("targets." + tier + ".points", RaceCatalog.defaultPoints(tier)));
    if (!configured.isEmpty()) {
      Map<String, Object> entry = pickUnusedEntry(configured, used);
      ItemKey key = itemKey(String.valueOf(entry.getOrDefault("material", entry.getOrDefault("item", "diamond"))));
      int targetAmount = number(entry.get("amount"), amount);
      int targetPoints = number(entry.get("points"), points);
      boolean explicitName = entry.containsKey("name");
      String name = explicitName ? String.valueOf(entry.get("name")) : key.value();
      used.add("i:" + key.qualifiedName());
      objectives.add(RaceObjective.item(tier, key, targetAmount, targetPoints, name, explicitName));
      return;
    }
    ItemKey key = pickUnusedItem(RaceCatalog.defaultItems(tier), used);
    used.add("i:" + key.qualifiedName());
    objectives.add(RaceObjective.item(tier, key, amount, points, key.value(), false));
  }

  private void addStructure(String tier, List<String> pool, Set<String> used, List<RaceObjective> objectives) {
    int points = Math.max(1, config.getInt("targets." + tier + ".points", RaceCatalog.defaultPoints(tier)));
    String id = null;
    for (int attempt = 0; attempt < pool.size() * 2; attempt++) {
      String candidate = pool.get(random.nextInt(pool.size())).toLowerCase(Locale.ROOT).trim();
      if (used.add("s:" + candidate)) {
        id = candidate;
        break;
      }
    }
    if (id == null) {
      addItem(tier, used, objectives);
      return;
    }
    objectives.add(RaceObjective.structure(tier, id, points, id));
  }

  private List<String> structurePool(String tier) {
    List<String> configured = config.getStringList("structures." + tier);
    return configured.isEmpty() ? RaceCatalog.defaultStructures(tier) : configured;
  }

  private Map<String, Object> pickUnusedEntry(List<Map<String, Object>> entries, Set<String> used) {
    for (int attempt = 0; attempt < entries.size() * 2; attempt++) {
      Map<String, Object> candidate = entries.get(random.nextInt(entries.size()));
      ItemKey key = itemKey(String.valueOf(candidate.getOrDefault("material", candidate.getOrDefault("item", ""))));
      if (!used.contains("i:" + key.qualifiedName())) {
        return candidate;
      }
    }
    return entries.get(random.nextInt(entries.size()));
  }

  private ItemKey pickUnusedItem(List<String> pool, Set<String> used) {
    for (int attempt = 0; attempt < pool.size() * 2; attempt++) {
      ItemKey key = itemKey(pool.get(random.nextInt(pool.size())));
      if (!used.contains("i:" + key.qualifiedName())) {
        return key;
      }
    }
    return itemKey(pool.get(random.nextInt(pool.size())));
  }

  static int number(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  static ItemKey itemKey(String value) {
    String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    if (normalized.contains(":")) {
      return new ItemKey(normalized.substring(0, normalized.indexOf(':')), normalized.substring(normalized.indexOf(':') + 1));
    }
    return ItemKey.minecraft(normalized.isBlank() ? "diamond" : normalized);
  }
}
