import { createElement } from "react";
import satori from "satori";
import sharp from "sharp";
import { getFonts } from "../lib/fonts.js";
import { loadAdditionalAsset } from "../lib/glyphs.js";
import { ProfileCard, PROFILE_CARD_WIDTH, PROFILE_CARD_HEIGHT } from "../components/ui/profile-card.js";
import type { ProfileData } from "../types/profile.js";

export type { ProfileData };

export async function generateProfileImage(data: ProfileData): Promise<Buffer> {
  const fonts = await getFonts();

  const svg = await satori(createElement(ProfileCard, { data }), {
    width: PROFILE_CARD_WIDTH,
    height: PROFILE_CARD_HEIGHT,
    fonts,
    loadAdditionalAsset,
  });

  return sharp(Buffer.from(svg)).png().toBuffer();
}
