package com.sexidium.core.menu.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * A complete screen layout: a fixed-size GUI-pixel canvas plus an ordered list of {@link Element}s drawn
 * back-to-front (painter's order). One {@code Scene} is the shared description that both backends consume —
 * the {@link SceneRenderer} rasterises it to a {@link java.awt.image.BufferedImage}, and the in-game
 * compiler translates the same elements into chest-title glyph/shift/text markup — so an offline PNG is a
 * faithful preview of the in-game render.
 *
 * <p>The canvas is the chest's GUI-pixel rectangle: width is {@code MenuArt.CHEST_WIDTH} (176) and height
 * comes from the chest's row count, but {@code Scene} stays decoupled from {@code MenuArt} and just holds
 * the numbers. Build with {@link #builder(String, int, int)}.</p>
 */
public record Scene(String id, int width, int height, List<Element> elements) {

  public Scene {
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("Scene canvas must be positive: " + width + "x" + height);
    }
    elements = List.copyOf(elements);
  }

  public static Builder builder(String id, int width, int height) {
    return new Builder(id, width, height);
  }

  /** Fluent accumulator for a scene's elements, in draw order (first added = drawn first/underneath). */
  public static final class Builder {
    private final String id;
    private final int width;
    private final int height;
    private final List<Element> elements = new ArrayList<>();

    private Builder(String id, int width, int height) {
      this.id = id;
      this.width = width;
      this.height = height;
    }

    /** Add one element on top of the current stack. */
    public Builder add(Element element) {
      if (element != null) {
        elements.add(element);
      }
      return this;
    }

    /** Add several elements in order. */
    public Builder addAll(List<? extends Element> toAdd) {
      for (Element element : toAdd) {
        add(element);
      }
      return this;
    }

    public Scene build() {
      return new Scene(id, width, height, elements);
    }
  }
}
