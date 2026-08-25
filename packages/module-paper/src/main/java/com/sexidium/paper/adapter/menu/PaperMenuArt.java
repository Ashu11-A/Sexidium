package com.sexidium.paper.adapter.menu;

import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.MenuView;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * The Paper half of the negative-space art layer: Adventure {@link TagResolver}s that turn the
 * platform-agnostic {@code <glyph:id>} and {@code <shift:n>} markup into the real Sexidium font glyphs
 * and spacers (the technique behind Nexo/ItemsAdder custom GUIs, owned natively here). See
 * {@link MenuArt} for the data and {@code docs/interface/menus.md} for the pipeline.
 *
 * <p>Two MiniMessage instances back the graceful fallback: {@link #ART} renders the tags to font
 * components (for a viewer who loaded the resource pack), {@link #PLAIN} drops them entirely (so a
 * no-pack Java client never sees a literal {@code <glyph:…>} in a title). Bedrock never reaches here —
 * it gets a Cumulus Form.</p>
 */
public final class PaperMenuArt {
  private static final Key GLYPH_FONT = Key.key(MenuArt.GLYPH_FONT);
  private static final Key SPACE_FONT = Key.key(MenuArt.SPACE_FONT);

  private static final MiniMessage ART = MiniMessage.builder()
      .editTags(tags -> tags.resolver(glyphResolver(true)).resolver(shiftResolver(true))
          .resolver(glyphTileResolver(true)))
      .build();
  private static final MiniMessage PLAIN = MiniMessage.builder()
      .editTags(tags -> tags.resolver(glyphResolver(false)).resolver(shiftResolver(false))
          .resolver(glyphTileResolver(false)))
      .build();

  private PaperMenuArt() {
  }

  /**
   * Renders a view's chest title. When {@code packLoaded} and the view requested a background glyph,
   * the title is prefixed with the negative-space + glyph markup so the bitmap paints over the slots;
   * otherwise the plain title is rendered with art tags stripped.
   */
  public static Component title(MenuView view, boolean packLoaded) {
    String raw = view.title() == null ? "" : view.title();
    if (!packLoaded) {
      return PLAIN.deserialize(raw);
    }
    String overlay = view.overlayArt();
    boolean hasOverlay = overlay != null && MenuArt.glyph(overlay) != null;
    if (hasOverlay) {
      // Two stacked glyphs: the chest frame (from the view's background, or the row-count frame) UNDERNEATH a
      // transparent baked overlay (the hub cards) — drawn second so it lands on top. Each glyph triple is
      // cursor-neutral (frameShift + renderWidth + titleReturnShift == 0), so they stack at the same anchor.
      // No title text (the overlay carries the art).
      String bg = view.backgroundArt();
      boolean bgScreen = bg != null && MenuArt.isScreenGlyph(bg) && MenuArt.glyph(bg) != null;
      String frameGlyph = bgScreen ? bg : MenuArt.chestGlyphId(view.rows());
      return ART.deserialize(glyphMarkup(frameGlyph) + glyphMarkup(overlay));
    }
    if (view.backgroundArt() != null) {
      // Full-window glyphs (baked screens AND the calibration overlay) name an exact "screen/" glyph and
      // paint edge-to-edge with no title text; older size-keyed BG_* markers still pick the row-count frame.
      String bg = view.backgroundArt();
      boolean bakedScreen = MenuArt.isScreenGlyph(bg) && MenuArt.glyph(bg) != null;
      String glyphId = bakedScreen ? bg : MenuArt.chestGlyphId(view.rows());
      String title = bakedScreen ? "" : raw;
      return ART.deserialize(glyphMarkup(glyphId) + title);
    }
    return ART.deserialize(raw);
  }

  /**
   * The cursor-neutral markup that paints a background glyph over the slot grid. For a TILED background (the
   * 768px chest/screen art, split into ≤256px font tiles so Minecraft's 256px per-glyph atlas can render it)
   * this is one cursor-neutral triple PER TILE: a shift to the tile's on-screen left edge, the tile glyph
   * (its font provider's {@code ascent} sets the row's vertical place), then a return shift. For a non-tiled
   * glyph (the ≤256px calibration overlay) it is a single triple. Every triple nets to zero cursor movement,
   * so all tiles share the title anchor and stacking another background (frame + overlay) still aligns 1:1.
   */
  private static String glyphMarkup(String glyphId) {
    MenuArt.Glyph glyph = MenuArt.glyph(glyphId);
    if (glyph != null && glyph.tiled()) {
      StringBuilder markup = new StringBuilder();
      for (MenuArt.Tile tile : MenuArt.tiles(glyph)) {
        markup.append("<shift:").append(MenuArt.tileFrameShift(tile.leftX())).append(">")
            .append("<gtile:").append((int) tile.codepoint()).append(">")
            .append("<shift:").append(MenuArt.tileReturnShift(tile.leftX(), tile.renderWidth())).append(">");
      }
      return markup.toString();
    }
    return "<shift:" + MenuArt.frameShift(glyphId) + ">"
        + "<glyph:" + glyphId + ">"
        + "<shift:" + MenuArt.titleReturnShift(glyphId) + ">";
  }

  /** Renders a button name/lore line, honouring (or stripping) art tags per pack status. */
  public static Component text(String miniMessage, boolean packLoaded) {
    return (packLoaded ? ART : PLAIN).deserialize(miniMessage == null ? "" : miniMessage);
  }

  private static TagResolver glyphResolver(boolean render) {
    return TagResolver.resolver("glyph", (args, context) -> {
      String id = args.popOr("glyph id required").value();
      if (!render) {
        return Tag.selfClosingInserting(Component.empty());
      }
      MenuArt.Glyph glyph = MenuArt.glyph(id);
      if (glyph == null) {
        return Tag.selfClosingInserting(Component.empty());
      }
      // WHITE is load-bearing, not cosmetic: vanilla AbstractContainerScreen.renderLabels draws the
      // chest title at 0x404040 (dark grey), and Minecraft MULTIPLIES a bitmap-font glyph by the text
      // colour — so an un-coloured background glyph renders at ~25% brightness (the "faded/dark art"
      // bug). Forcing white means ×1.0, i.e. the art shows its true, vibrant colours.
      return Tag.selfClosingInserting(
          Component.text(String.valueOf(glyph.codepoint()))
              .font(GLYPH_FONT)
              .color(net.kyori.adventure.text.format.NamedTextColor.WHITE));
    });
  }

  /**
   * Renders ONE tile glyph by its raw code point ({@code <gtile:NNNNN>}), in the glyph font and forced WHITE
   * (same colour-multiply reasoning as {@link #glyphResolver}). Used by the tiled-background markup, where a
   * single background expands to a grid of code points rather than the one {@code <glyph:id>} code point.
   */
  private static TagResolver glyphTileResolver(boolean render) {
    return TagResolver.resolver("gtile", (args, context) -> {
      int codepoint = args.popOr("tile codepoint required").asInt().orElse(-1);
      if (!render || codepoint < 0) {
        return Tag.selfClosingInserting(Component.empty());
      }
      return Tag.selfClosingInserting(
          Component.text(String.valueOf((char) codepoint))
              .font(GLYPH_FONT)
              .color(net.kyori.adventure.text.format.NamedTextColor.WHITE));
    });
  }

  private static TagResolver shiftResolver(boolean render) {
    return TagResolver.resolver("shift", (args, context) -> {
      int pixels = args.popOr("shift pixels required").asInt().orElse(0);
      if (!render || pixels == 0) {
        return Tag.selfClosingInserting(Component.empty());
      }
      return Tag.selfClosingInserting(Component.text(MenuArt.shift(pixels)).font(SPACE_FONT));
    });
  }
}
