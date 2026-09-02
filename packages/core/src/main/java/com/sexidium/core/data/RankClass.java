package com.sexidium.core.data;

import java.util.Locale;

/**
 * The seven Sexidium rank classes, ordered from worst to best:
 * {@code OMEGA < EPSILON < DELTA < GAMMA < BETA < ALPHA < SIGMA}.
 *
 * <p>A player's class is derived from their rank <em>level</em> (level = points / points-per-level)
 * via {@link #forLevel(int)}. Each class carries a display name and a hex colour reused everywhere a
 * rank tag is rendered: the in-game scoreboard-team name prefix (Paper), the Discord role
 * colour and the satori rank cards. Keeping the thresholds and colours in one place guarantees the
 * Minecraft server and the Discord bot agree on what tag a player wears.</p>
 *
 * <p>The TypeScript bot mirrors this table in {@code bot/src/lib/ranks.ts}; the HTTP bridge also sends
 * the resolved class/colour in its JSON so the bot never has to guess.</p>
 */
public enum RankClass {
  OMEGA("Omega", "#9AA0A6", 0),
  EPSILON("Epsilon", "#57F287", 5),
  DELTA("Delta", "#1ABC9C", 10),
  GAMMA("Gamma", "#3498DB", 20),
  BETA("Beta", "#9B59B6", 35),
  ALPHA("Alpha", "#F0B232", 55),
  SIGMA("Sigma", "#E74C3C", 80);

  private final String displayName;
  private final String hexColor;
  private final int minLevel;

  RankClass(String displayName, String hexColor, int minLevel) {
    this.displayName = displayName;
    this.hexColor = hexColor;
    this.minLevel = minLevel;
  }

  public String displayName() {
    return displayName;
  }

  /** Hex colour string {@code #RRGGBB}; usable directly as a MiniMessage {@code <#RRGGBB>} colour. */
  public String hexColor() {
    return hexColor;
  }

  public int minLevel() {
    return minLevel;
  }

  /** Resolves the class a level falls into (highest class whose threshold the level meets). */
  public static RankClass forLevel(int level) {
    RankClass resolved = OMEGA;
    for (RankClass candidate : values()) {
      if (level >= candidate.minLevel) {
        resolved = candidate;
      }
    }
    return resolved;
  }

  /** Case-insensitive parse by display name (e.g. {@code "sigma"}); null when unknown. */
  public static RankClass fromName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String normalized = name.trim().toLowerCase(Locale.ROOT);
    for (RankClass candidate : values()) {
      if (candidate.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
        return candidate;
      }
    }
    return null;
  }
}
