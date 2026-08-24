package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.modes.minigames.race.RaceObjective;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Pure MiniMessage / display-name formatting for Race objectives: builds the per-objective coloured label
 * (a {@code <lang:>} translatable for default-named items, the literal name otherwise, gold for
 * structures) and the comma-joined target summary. The item translation lookup is supplied as a function
 * so this stays free of any platform dependency.
 */
final class RaceObjectiveDisplay {
  private final Function<ItemKey, String> itemTranslationKey;

  RaceObjectiveDisplay(Function<ItemKey, String> itemTranslationKey) {
    this.itemTranslationKey = itemTranslationKey;
  }

  String objectiveDisplayMini(RaceObjective objective) {
    if (objective.isStructure()) {
      return "<gold>" + escapeMini(prettyName(objective.structureId())) + "</gold>";
    }
    if (!objective.explicitName()) {
      String translationKey = itemTranslationKey.apply(objective.itemKey());
      if (translationKey != null && !translationKey.isBlank()) {
        return "<lang:" + translationKey + ">";
      }
    }
    String name = objective.name() == null ? objective.itemKey().value() : objective.name();
    return escapeMini(name);
  }

  String targetSummary(List<RaceObjective> objectives) {
    List<String> names = new ArrayList<>();
    for (RaceObjective objective : objectives) {
      names.add(objectiveDisplayMini(objective) + (objective.isItem() ? " x" + objective.amount() : ""));
    }
    return String.join(", ", names);
  }

  static String escapeMini(String value) {
    return value.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
  }

  static String prettyName(String id) {
    String value = id == null ? "" : id;
    int colon = value.indexOf(':');
    if (colon >= 0) {
      value = value.substring(colon + 1);
    }
    String[] parts = value.split("_");
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
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
}
