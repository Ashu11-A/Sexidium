import type { ReactElement } from "react";
import satori from "satori";
import sharp from "sharp";
import { getFonts } from "../../lib/fonts.js";
import { loadAdditionalAsset } from "../../lib/glyphs.js";

export interface RenderOptions {
  width: number;
  /** Omit for auto-height (satori grows to fit the content). */
  height?: number;
}

/**
 * The one React-component-to-image engine: render any card element to a PNG via satori (React -> SVG)
 * then sharp (SVG -> PNG). Fonts + glyph/emoji fallback are wired once here, so every card wrapper is a
 * one-liner over this function.
 */
export async function renderCard(element: ReactElement, options: RenderOptions): Promise<Buffer> {
  const fonts = await getFonts();
  // satori's option type is a union of {width,height}|{width}|{height}; build the exact shape so a
  // possibly-undefined height doesn't confuse the overload resolution.
  const svg = await satori(
    element,
    options.height === undefined
      ? { width: options.width, fonts, loadAdditionalAsset }
      : { width: options.width, height: options.height, fonts, loadAdditionalAsset },
  );
  return sharp(Buffer.from(svg)).png().toBuffer();
}
