package com.sexidium.core.platform.hud;

import java.util.Locale;

/**
 * The colour a text element is drawn in.
 *
 * <h2>Why a spec has to say this at all</h2>
 * A row's words come from a {@link com.sexidium.core.i18n.LocalizedText} template, and those templates
 * are MiniMessage — so a countdown that wants to be red says {@code <red>} in the lang file and every
 * driver that renders through the chat component pipeline (the vanilla title, the scoreboard sidebar)
 * obeys it. An overlay driver cannot: BetterHud draws through a font atlas, so its renderer flattens
 * the template to plain text and takes the colour from the generated layout's own {@code color} key.
 * With nothing to fill that key from it wrote white, and one declaration came out red on one surface
 * and white on the other — which is exactly what "two counters" looks like when both halves draw.
 *
 * <p>So the colour is declared here, beside the row, where BOTH renderers can read it. The tags in the
 * lang file stay: they are what the component pipeline uses, and they must agree with this.</p>
 *
 * <p>The names are Adventure's {@code NamedTextColor} names, which is what makes {@link #id()} valid
 * both as a MiniMessage tag and as a value a platform overlay can parse.</p>
 */
public enum HudColor {
  BLACK,
  DARK_BLUE,
  DARK_GREEN,
  DARK_AQUA,
  DARK_RED,
  DARK_PURPLE,
  GOLD,
  GRAY,
  DARK_GRAY,
  BLUE,
  GREEN,
  AQUA,
  RED,
  LIGHT_PURPLE,
  YELLOW,
  WHITE;

  /** What a row is drawn in when its declaration does not say. */
  public static final HudColor DEFAULT = WHITE;

  /** The Adventure name of this colour: {@code dark_aqua}, {@code red}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }
}
