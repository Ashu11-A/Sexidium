package com.sexidium.core.menu.scene;

/**
 * An axis-aligned rectangle in the scene's GUI-pixel coordinate space (origin top-left, +y down). It is
 * the single layout primitive every {@link Element} is positioned with: the {@link SceneRenderer} draws
 * into these boxes and the in-game {@code SceneCompiler} converts them to chest-title {@code <shift>}
 * offsets and slot indices. Pure data — no AWT — so the model stays platform-agnostic and headless.
 */
public record Box(int x, int y, int width, int height) {

  public Box {
    if (width < 0 || height < 0) {
      throw new IllegalArgumentException("Box dimensions must be non-negative: " + width + "x" + height);
    }
  }

  public static Box of(int x, int y, int width, int height) {
    return new Box(x, y, width, height);
  }

  /** The x just past the right edge (x + width). */
  public int right() {
    return x + width;
  }

  /** The y just past the bottom edge (y + height). */
  public int bottom() {
    return y + height;
  }

  /** The horizontal centre (rounded down). */
  public int centerX() {
    return x + width / 2;
  }

  /** The vertical centre (rounded down). */
  public int centerY() {
    return y + height / 2;
  }

  /** This box moved by {@code (dx, dy)}, same size. */
  public Box translate(int dx, int dy) {
    return new Box(x + dx, y + dy, width, height);
  }

  /** This box shrunk by {@code pad} on every side (clamped at zero size). */
  public Box inset(int pad) {
    return inset(pad, pad);
  }

  /** This box shrunk by {@code padX} left/right and {@code padY} top/bottom (clamped at zero size). */
  public Box inset(int padX, int padY) {
    int w = Math.max(0, width - padX * 2);
    int h = Math.max(0, height - padY * 2);
    return new Box(x + padX, y + padY, w, h);
  }

  /** A {@code w×h} box centred inside this one (useful for placing a sprite/glyph in a slot). */
  public Box centeredChild(int w, int h) {
    return new Box(x + (width - w) / 2, y + (height - h) / 2, w, h);
  }

  /** True if this box and {@code other} overlap by a positive area (touching edges alone do not count). */
  public boolean intersects(Box other) {
    return x < other.right() && other.x() < right() && y < other.bottom() && other.y() < bottom();
  }
}
