import { createElement } from "react";
import satori from "satori";
import sharp from "sharp";
import { getFonts } from "../lib/fonts.js";
import { loadAdditionalAsset } from "../lib/glyphs.js";
import { EventCard, EVENT_CARD_WIDTH, EVENT_CARD_HEIGHT } from "../components/ui/event-card.js";
import type { EventData } from "../types/event.js";

export type { EventData };

export async function generateEventImage(data: EventData): Promise<Buffer> {
  const fonts = await getFonts();

  const svg = await satori(createElement(EventCard, { data }), {
    width: EVENT_CARD_WIDTH,
    height: EVENT_CARD_HEIGHT,
    fonts,
    loadAdditionalAsset,
  });

  return sharp(Buffer.from(svg)).png().toBuffer();
}
