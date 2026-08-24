package com.sexidium.core.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Stateless string/argument helpers shared by every command handler. */
final class CommandText {
  private CommandText() {
  }

  static String arg(String[] args, int index) {
    return args.length > index ? args[index] : null;
  }

  static String lower(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  static boolean equalsAny(String value, String... candidates) {
    return Arrays.stream(candidates).anyMatch(candidate -> candidate.equalsIgnoreCase(value == null ? "" : value));
  }

  static String normalizeCategory(String category) {
    return category == null || category.isBlank()
        ? "core"
        : category.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
  }

  static String displayCategory(String category) {
    return category == null || category.isBlank() ? "core" : category;
  }

  static void addCommaSeparated(List<String> values, String csv) {
    if (csv == null) {
      return;
    }
    for (String value : csv.split(",")) {
      if (!value.isBlank()) {
        values.add(value.trim());
      }
    }
  }

  static String escape(String text) {
    return text == null ? "" : text.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
  }

  static String fmt(double value) {
    return String.valueOf(Math.round(value * 10.0) / 10.0);
  }

  /** Snaps a coordinate to the centre of its block ({@code floor + 0.5}) so NPCs stand mid-block. */
  static double blockCenter(double coordinate) {
    return Math.floor(coordinate) + 0.5D;
  }

  static String joinFrom(String[] args, int start) {
    return String.join(" ", Arrays.copyOfRange(args, start, args.length));
  }

  static int parseIntOr(String value, int fallback) {
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }
}
