import { createElement } from "react";
import satori from "satori";
import sharp from "sharp";
import { getFonts } from "../lib/fonts.js";
import { loadAdditionalAsset } from "../lib/glyphs.js";
import { RankCard, RANK_CARD_WIDTH, RANK_CARD_HEIGHT } from "../components/ui/rank-card.js";
import type { RankEntry } from "../types/rank.js";

export type { RankEntry };

export async function generateRankImage(entries: RankEntry[]): Promise<Buffer> {
  const fonts = await getFonts();

  const svg = await satori(createElement(RankCard, { entries }), {
    width: RANK_CARD_WIDTH,
    height: RANK_CARD_HEIGHT,
    fonts,
    loadAdditionalAsset,
  });

  return sharp(Buffer.from(svg)).png().toBuffer();
}
