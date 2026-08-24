package com.sexidium.core.menu.scene;

/**
 * One drawable primitive in a {@link Scene}. The model is deliberately small — solid {@link Fill}s,
 * scaled {@link Sprite}s, and {@link Text} runs — because every richer "component" (a tile, a status
 * pill, a list row, the ornate frame) is <i>composed</i> from these primitives by the scene templates.
 * That keeps the renderer trivial and the same primitives drive both backends: the {@link SceneRenderer}
 * rasterises them to a PNG, and the in-game compiler maps each one onto a chest-title glyph/shift/text
 * run.
 *
 * <p>Colours are stored as packed ARGB {@code int}s (not {@code java.awt.Color}) so the model carries no
 * AWT dependency and stays usable in headless core code and on any adapter.</p>
 */
public sealed interface Element permits Element.Fill, Element.Sprite, Element.Text {

  /** Where this element draws, in scene GUI-pixel space. */
  Box box();

  /** How a sprite's source pixels map into its {@link Box}. */
  enum Fit {
    /** Fill the box exactly, ignoring aspect (may distort). */
    STRETCH,
    /** Largest size that fits inside the box, aspect preserved, centred (letterboxed). */
    CONTAIN,
    /** Smallest size that covers the box, aspect preserved, centred (cropped). */
    COVER,
    /** Draw at the sprite's native size, top-left anchored in the box (no scaling). */
    NONE
  }

  enum HAlign { LEFT, CENTER, RIGHT }

  enum VAlign { TOP, MIDDLE, BOTTOM }

  /** A solid rectangle, optionally rounded. {@code cornerRadius == 0} is a plain rectangle. */
  record Fill(Box box, int argb, int cornerRadius) implements Element {
    public Fill(Box box, int argb) {
      this(box, argb, 0);
    }
  }

  /**
   * A named sprite from the {@link ComponentAtlas} drawn into {@code box} per {@link Fit}. {@code spriteId}
   * is the atlas key (e.g. {@code frame}, {@code item/system/home}); resolution failures render nothing
   * (the renderer logs/skips) so a missing asset never throws mid-compose.
   */
  record Sprite(String spriteId, Box box, Fit fit) implements Element {
    public Sprite(String spriteId, Box box) {
      this(spriteId, box, Fit.CONTAIN);
    }
  }

  /**
   * A bitmap-font text run. {@code fontId} selects a registered {@link BitmapFont}; the string is laid out
   * left-to-right with that font's advances and aligned inside {@code box}. Glyphs absent from the font are
   * skipped (advancing by the font's space width) so unknown characters never crash a render.
   */
  record Text(String value, Box box, String fontId, int argb, HAlign hAlign, VAlign vAlign)
      implements Element {
    public Text(String value, Box box, String fontId, int argb) {
      this(value, box, fontId, argb, HAlign.LEFT, VAlign.TOP);
    }
  }
}
