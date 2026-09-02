package com.sexidium.core.platform.hud;

import java.util.Locale;

/**
 * The colour a text element is drawn in.
 *
 * <h2>What this is now: the floor, not the whole story</h2>
 * A row's words come from a {@link com.sexidium.core.i18n.LocalizedText} template, and those templates
 * are MiniMessage — so a countdown that wants to be red says {@code <red>} in the lang file and every
 * driver that renders through the chat component pipeline (the vanilla title, the scoreboard sidebar)
 * obeys it. The overlay driver used to be unable to: it flattened the template to plain text and took
 * the colour from the generated layout's own {@code color} key, so one declaration came out red on one
 * surface and white on the other.
 *
 * <p>That half is fixed. The generated layout sets {@code use-legacy-format} and the publisher
 * serializes each line to ampersand codes, so BetterHud rebuilds the runs of colour a template
 * declared — which is also what lets ONE row carry two colours, a green tick beside a dimmed label.</p>
 *
 * <p>This stays, and is still load-bearing, as the colour a span carrying no code of its own is drawn
 * in — which is every span of every template that declares none. Declare it to match what the template
 * says, or leave it at {@link #DEFAULT} when the template colours itself throughout.</p>
 *
 * <p><b>Decorations are still lost on the overlay.</b> Nothing in BetterHud references Adventure's
 * {@code TextDecoration}; its renderer reads {@code color()} and nothing else, so a
 * {@code <strikethrough>} is parsed and dropped. A consumer that needs "done" to read as done says it
 * with a glyph as well as with a colour.</p>
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
