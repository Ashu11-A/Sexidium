package com.sexidium.core.game.team;

/**
 * The palette teams are drawn from, in assignment order. Each colour carries a human display name, a
 * MiniMessage colour tag (for chat / boss bars / scoreboards) and a wool {@code ItemKey} value used as
 * the team's icon in the team-select GUI. The number of entries also caps the maximum team count.
 */
public enum TeamColor {
  RED("Red", "red", "red_wool", 0xFF5555),
  BLUE("Blue", "blue", "blue_wool", 0x5555FF),
  GREEN("Green", "green", "lime_wool", 0x55FF55),
  YELLOW("Yellow", "yellow", "yellow_wool", 0xFFFF55),
  AQUA("Aqua", "aqua", "light_blue_wool", 0x55FFFF),
  PINK("Pink", "light_purple", "pink_wool", 0xFF55FF),
  ORANGE("Orange", "gold", "orange_wool", 0xFFAA00),
  WHITE("White", "white", "white_wool", 0xFFFFFF);

  private final String displayName;
  private final String miniMessageColor;
  private final String woolItem;
  private final int rgb;

  TeamColor(String displayName, String miniMessageColor, String woolItem, int rgb) {
    this.displayName = displayName;
    this.miniMessageColor = miniMessageColor;
    this.woolItem = woolItem;
    this.rgb = rgb;
  }

  public String displayName() {
    return displayName;
  }

  /** MiniMessage colour tag name, e.g. {@code "light_purple"} — wrap text as {@code <tag>...</tag>}. */
  public String miniMessageColor() {
    return miniMessageColor;
  }

  /** Wraps text in this colour for MiniMessage rendering. */
  public String colorize(String text) {
    return "<" + miniMessageColor + ">" + text + "</" + miniMessageColor + ">";
  }

  /** Minecraft item value (no namespace) used as this team's GUI icon. */
  public String woolItem() {
    return woolItem;
  }

  /** Packed 0xRRGGBB colour, used for particle wireframes / region debug rendering. */
  public int rgb() {
    return rgb;
  }

  public static int maxTeams() {
    return values().length;
  }
}
