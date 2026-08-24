package com.sexidium.core.menu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A platform-agnostic chest-GUI definition: a MiniMessage title, a row count, and slot buttons.
 *
 * <p>{@code backgroundArt} is an optional glyph id (see {@link MenuArt}) the renderer paints behind
 * the slots via the negative-space title trick — but only for a viewer known to have loaded the
 * Sexidium resource pack. It is purely cosmetic: a view renders identically (minus the art) on
 * Bedrock, on a no-pack Java client, and in headless tests.</p>
 */
public final class MenuView {
  private final String title;
  private final int rows;
  private int plainRows;
  private final Map<Integer, MenuButton> buttons = new LinkedHashMap<>();
  private String backgroundArt;
  private String screenArt;
  private String overlayArt;
  private boolean animated;

  public MenuView(String title, int rows) {
    this.title = title == null ? "" : title;
    this.rows = Math.max(1, Math.min(6, rows));
    this.plainRows = this.rows;
  }

  public String title() {
    return title;
  }

  public int rows() {
    return rows;
  }

  /**
   * The row count to use when the view renders <i>without</i> the baked resource-pack art (a no-pack or
   * Bedrock viewer). Defaults to {@link #rows()}. A view whose baked art needs a full six-row window but
   * which reads fine in a smaller plain chest (the hub) sets this smaller so plain viewers get a compact
   * window; see {@link #plainSize()}. The renderer picks {@link #size()} vs {@link #plainSize()} per viewer.
   */
  public int plainRows() {
    return plainRows;
  }

  /** Sets the plain-mode (no-pack) row count, clamped to 1–6. Returns this view for chaining. */
  public MenuView plainRows(int plainRows) {
    this.plainRows = Math.max(1, Math.min(6, plainRows));
    return this;
  }

  /** The inventory slot count for a plain (no-pack) render: {@link #plainRows()} × 9. */
  public int plainSize() {
    return plainRows * 9;
  }

  /** The background glyph id this view requests, or {@code null} for a plain title. */
  public String backgroundArt() {
    return backgroundArt;
  }

  /** Requests a {@link MenuArt} background glyph behind the slots (pack-loaded Java viewers only). */
  public MenuView background(String glyphId) {
    this.backgroundArt = glyphId == null || glyphId.isBlank() ? null : glyphId;
    return this;
  }

  /** The baked per-screen scene id this view renders as (e.g. {@code main-hub}), or {@code null}. */
  public String screenArt() {
    return screenArt;
  }

  /** The transparent overlay glyph id drawn ON TOP of {@link #backgroundArt}, or {@code null}. */
  public String overlayArt() {
    return overlayArt;
  }

  /**
   * Renders this view's slots from a baked scene named {@code sceneId}: for a pack-loaded Java viewer the
   * adapter paints the transparent {@code screen/<sceneId>} overlay ON TOP of the view's {@link #background}
   * (the chest frame) and renders every button as an invisible hitbox (so only the baked art shows, clicks
   * intact). It sets {@link #overlayArt} — NOT the background — so the chest frame behind it stays visible.
   * A view that wants the overlay over the chest frame keeps its {@code .background(...)}; a view that sets
   * only {@code screenArt} (no background) gets the overlay over the plain chest. No-pack / Bedrock viewers
   * ignore it and get the ordinary chest, so it stays purely additive.
   */
  public MenuView screenArt(String sceneId) {
    this.screenArt = sceneId == null || sceneId.isBlank() ? null : sceneId;
    this.overlayArt = this.screenArt == null ? null : MenuArt.screenGlyphId(this.screenArt);
    return this;
  }

  /** Whether this view has animated buttons (any button carrying {@code nameFrames}) the platform cycles. */
  public boolean animated() {
    return animated;
  }

  /** Marks this view as animated so the platform starts cycling its buttons' {@code nameFrames} in place. */
  public MenuView animated(boolean animated) {
    this.animated = animated;
    return this;
  }

  public int size() {
    return rows * 9;
  }

  public MenuView set(int slot, MenuButton button) {
    if (slot >= 0 && slot < size() && button != null) {
      buttons.put(slot, button);
    }
    return this;
  }

  /** Places the button in the first free slot. */
  public MenuView add(MenuButton button) {
    if (button == null) {
      return this;
    }
    for (int slot = 0; slot < size(); slot++) {
      if (!buttons.containsKey(slot)) {
        buttons.put(slot, button);
        return this;
      }
    }
    return this;
  }

  public Map<Integer, MenuButton> buttons() {
    return buttons;
  }

  public MenuButton button(int slot) {
    return buttons.get(slot);
  }
}
