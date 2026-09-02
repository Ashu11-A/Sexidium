package com.sexidium.core.menu;

/**
 * Marks a chest menu's title as a Sexidium menu so a modded <b>client</b> could recognise it and
 * re-skin the screen, without changing what the server sends to anyone else.
 *
 * <p>The adapter always opens a plain vanilla {@code GENERIC_9xN} container — that is the
 * universal floor every client (vanilla Java, and Bedrock-via-Geyser) can render. To let a modded
 * client upgrade only <i>our</i> menus (and never hijack a real chest), the server prefixes the title
 * with a single invisible marker code point; the client checks for it and, if present, paints a custom
 * overlay (after stripping the marker), else it leaves the screen alone. The marker is one
 * Private-Use-Area code point ({@code U+E000}) which survives the {@code MiniMessage -> Component ->
 * open-screen packet -> client} round-trip as literal text and carries no glyph (so it adds no visible
 * character for a client that lacks the mod). The client reads the real container size from the screen
 * itself, so the marker need only be a yes/no flag — no extra encoded data, nothing visible.</p>
 *
 * <p>Shipped for forward compatibility: no shipped consumer strips the marker today, and every
 * vanilla/Geyser client simply never sees it act. Everything fails safe: if the marker is stripped,
 * mangled, or never read, the screen is simply the plain chest. All methods are pure so they can be
 * unit-tested headless.</p>
 */
public final class MenuSentinel {
  /** Private-Use-Area marker code point. Carries no glyph, so it is invisible on a stock client. */
  public static final char MARKER = '\uE000';

  private MenuSentinel() {
  }

  /** Prefixes {@code title} with the invisible marker. Idempotent: an already-marked title is unchanged. */
  public static String encode(String title) {
    String safeTitle = title == null ? "" : title;
    if (isSexidium(safeTitle)) {
      return safeTitle;
    }
    return MARKER + safeTitle;
  }

  /** Whether {@code title} (raw, or the client-rendered plain text) carries the Sexidium marker. */
  public static boolean isSexidium(String title) {
    return title != null && !title.isEmpty() && title.charAt(0) == MARKER;
  }

  /** The original title with the marker removed; a no-op when not marked. */
  public static String strip(String title) {
    if (!isSexidium(title)) {
      return title == null ? "" : title;
    }
    return title.substring(1);
  }
}
