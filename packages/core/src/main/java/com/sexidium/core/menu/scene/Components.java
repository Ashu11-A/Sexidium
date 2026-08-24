package com.sexidium.core.menu.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * The reusable component layer: factory methods that emit {@link Element} primitives for the visual
 * vocabulary the reference mockups share — the ornate frame, the title plaque, grid tiles, list rows,
 * status pills, count text, ONLINE dots, signal bars, dividers, head-wells, and the back button. Every
 * screen {@link SceneTemplates template} is assembled from these, so a tweak to (say) a pill's shape is
 * made once and every screen inherits it.
 *
 * <p>For the M1 slice these are drawn procedurally (rounded {@link Element.Fill}s + {@link Element.Text})
 * over the existing icon sprites and the {@code UI/frame} border, so no new art has to be authored to see
 * a faithful preview. As real component sprites are added they replace the procedural pieces behind the
 * same method signatures.</p>
 */
public final class Components {

  // ----- font ids (registered with the SceneRenderer) -----------------------------------------
  public static final String FONT_DISPLAY = "display"; // chunky title caps (item/font/char_*)
  public static final String FONT_BODY = "body";       // small body text (names, counts, sub-lines)

  // ----- atlas sprite ids ---------------------------------------------------------------------
  public static final String SPRITE_FRAME = "frame";

  // ----- palette (packed ARGB) — UltimateGUI "Medieval" wood scheme ---------------------------
  // Fully-opaque canvas base. A baked screen is meant to REPLACE the vanilla chest grid (show only our
  // art), so the background must be opaque edge-to-edge — anything translucent lets the slot grid bleed
  // through. Drawn first, under the frame/panel, so the whole 176px-wide canvas is solid. The hues match
  // the medieval pack (deep wood base, dark-wood tiles, gilt-brown borders, cream caps) and keep the same
  // dark-tile / cream-text contrast the screens were designed around.
  public static final int OPAQUE_BG = 0xFF3B2A1C;   // deep wood base that hides the chest slot grid
  public static final int PANEL = 0xCC4A3526;       // inner panel behind the slots
  public static final int TILE = 0xAA2E2013;        // a grid/list tile background (dark wood)
  public static final int TILE_EDGE = 0xFF8A5E36;   // tile border accent (gilt brown)
  public static final int DIVIDER = 0x66D8B070;     // section divider line
  public static final int TEXT_WHITE = 0xFFF3E9D2;  // parchment cream
  public static final int TEXT_GREY = 0xFFC2A877;   // muted tan sub-text
  public static final int GREEN = 0xFF6FB84A;
  public static final int RED = 0xFFB54430;
  public static final int GOLD = 0xFFE0A838;
  public static final int PILL_JOIN = 0xFF8A5E2E;
  public static final int PILL_BACK = 0xFF7A3B2E;
  public static final int OFFLINE = 0xFF6B5D4A;

  private Components() {
  }

  /** The ornate border sprite over a fully-opaque base, with a translucent inner panel for depth. The
   * opaque base makes the baked screen hide the vanilla chest grid entirely (show only our art). */
  public static List<Element> frame(int width, int height) {
    List<Element> out = new ArrayList<>();
    out.add(new Element.Fill(Box.of(0, 0, width, height), OPAQUE_BG, 0));
    out.add(new Element.Fill(Box.of(6, 16, width - 12, height - 22), PANEL, 4));
    out.add(new Element.Sprite(SPRITE_FRAME, Box.of(0, 0, width, height), Element.Fit.STRETCH));
    return out;
  }

  /** The centred title plaque text in display caps near the top of the frame. */
  public static Element plaque(String title, int width) {
    return new Element.Text(title.toUpperCase(java.util.Locale.ROOT), Box.of(16, 3, width - 32, 11),
        FONT_DISPLAY, TEXT_WHITE, Element.HAlign.CENTER, Element.VAlign.MIDDLE);
  }

  /** A square grid tile: background, centred icon, and a bold label under it (optional 2-line desc). */
  public static List<Element> gridTile(Box box, String iconSpriteId, String label, String desc) {
    List<Element> out = new ArrayList<>();
    out.add(new Element.Fill(box, TILE_EDGE, 3));
    out.add(new Element.Fill(box.inset(1), TILE, 3));
    int iconSize = Math.min(box.width(), box.height()) - (desc == null ? 14 : 22);
    Box icon = new Box(box.centerX() - iconSize / 2, box.y() + 3, iconSize, iconSize);
    out.add(new Element.Sprite(iconSpriteId, icon, Element.Fit.CONTAIN));
    out.add(new Element.Text(label, new Box(box.x() + 1, icon.bottom() + 1, box.width() - 2, 6),
        FONT_BODY, TEXT_WHITE, Element.HAlign.CENTER, Element.VAlign.MIDDLE));
    if (desc != null) {
      out.add(new Element.Text(desc, new Box(box.x() + 1, icon.bottom() + 8, box.width() - 2, 6),
          FONT_BODY, TEXT_GREY, Element.HAlign.CENTER, Element.VAlign.MIDDLE));
    }
    return out;
  }

  /** A horizontal list row: background + left icon + title + up to two grey sub-lines. */
  public static List<Element> listRow(Box box, String iconSpriteId, String title, String sub1,
      String sub2) {
    List<Element> out = new ArrayList<>();
    out.add(new Element.Fill(box, TILE_EDGE, 3));
    out.add(new Element.Fill(box.inset(1), TILE, 3));
    int icon = box.height() - 4;
    out.add(new Element.Sprite(iconSpriteId, new Box(box.x() + 2, box.y() + 2, icon, icon),
        Element.Fit.CONTAIN));
    int textX = box.x() + icon + 5;
    int textW = box.width() - icon - 8;
    out.add(new Element.Text(title, new Box(textX, box.y() + 2, textW, 7), FONT_BODY, TEXT_WHITE));
    if (sub1 != null) {
      out.add(new Element.Text(sub1, new Box(textX, box.y() + 10, textW, 5), FONT_BODY, TEXT_GREY));
    }
    if (sub2 != null) {
      out.add(new Element.Text(sub2, new Box(textX, box.y() + 16, textW, 5), FONT_BODY, TEXT_GREY));
    }
    return out;
  }

  /** A rounded status/action pill with centred caps text (PUBLIC, PRIVATE, JOIN, BACK, START…). */
  public static List<Element> pill(Box box, String text, int fillArgb, int textArgb) {
    List<Element> out = new ArrayList<>();
    out.add(new Element.Fill(box, fillArgb, Math.min(box.height() / 2, 4)));
    out.add(new Element.Text(text, box.inset(2, 0), FONT_BODY, textArgb, Element.HAlign.CENTER,
        Element.VAlign.MIDDLE));
    return out;
  }

  /** A right-aligned "x/y" count, e.g. {@code 8/12}, coloured by fullness (green→gold→red). */
  public static Element count(Box box, int current, int max) {
    int color = GREEN;
    if (max > 0) {
      double ratio = current / (double) max;
      color = ratio >= 1.0 ? RED : ratio >= 0.75 ? GOLD : GREEN;
    }
    return new Element.Text(current + "/" + max, box, FONT_BODY, color, Element.HAlign.RIGHT,
        Element.VAlign.MIDDLE);
  }

  /** A small presence dot (green = online, grey = offline) at the given top-left, ~5px. */
  public static Element presenceDot(int x, int y, boolean online) {
    return new Element.Fill(Box.of(x, y, 5, 5), online ? GREEN : OFFLINE, 2);
  }

  /** Ascending signal/wifi bars (4) growing left→right, coloured by strength fraction. */
  public static List<Element> signalBars(int x, int yBottom, int filledFraction4) {
    List<Element> out = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      int h = 2 + i * 2;
      int color = i < filledFraction4 ? GREEN : 0x55B7A6D6;
      out.add(new Element.Fill(Box.of(x + i * 3, yBottom - h, 2, h), color, 0));
    }
    return out;
  }

  /** A labelled section divider: a thin line with a caps label at the left. */
  public static List<Element> divider(Box box, String label) {
    List<Element> out = new ArrayList<>();
    out.add(new Element.Text(label, new Box(box.x(), box.y(), box.width(), 6), FONT_BODY, TEXT_GREY));
    out.add(new Element.Fill(new Box(box.x(), box.bottom() - 1, box.width(), 1), DIVIDER, 0));
    return out;
  }

  /**
   * A head-well: the framed slot a live player head sits in (the head itself stays a real chest item, so
   * the preview shows the well plus an optional placeholder sprite). Returns the well chrome.
   */
  public static List<Element> headWell(Box box, String placeholderSpriteId) {
    List<Element> out = new ArrayList<>();
    out.add(new Element.Fill(box, TILE_EDGE, 3));
    out.add(new Element.Fill(box.inset(1), 0xAA241636, 3));
    if (placeholderSpriteId != null) {
      out.add(new Element.Sprite(placeholderSpriteId, box.inset(2), Element.Fit.CONTAIN));
    }
    return out;
  }

  /** The standard bottom-left "← BACK" pill. */
  public static List<Element> backButton(int x, int y) {
    return pill(Box.of(x, y, 34, 9), "< BACK", PILL_BACK, TEXT_WHITE);
  }
}
