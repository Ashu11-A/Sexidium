import { createElement } from "react";
import satori from "satori";
import sharp from "sharp";
import { getFonts } from "../lib/fonts.js";
import { loadAdditionalAsset } from "../lib/glyphs.js";
import { ScoreCard, SCORE_CARD_WIDTH } from "../components/ui/score-card.js";
import type { ScoreData, UserRole } from "../types/score.js";

export type { ScoreData, UserRole };

export async function generateScoreImage(data: ScoreData): Promise<Buffer> {
  const fonts = await getFonts();

  const svg = await satori(createElement(ScoreCard, { data }), {
    width: SCORE_CARD_WIDTH,
    fonts,
    loadAdditionalAsset,
  });

  return sharp(Buffer.from(svg)).png().toBuffer();
}
