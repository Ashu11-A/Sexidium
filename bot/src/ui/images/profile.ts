import { createElement } from "react";
import { renderCard } from "./engine.js";
import { ProfileCard, PROFILE_CARD_WIDTH, PROFILE_CARD_HEIGHT } from "../components/profile-card.js";
import type { ProfileData } from "../../types/profile.js";

export type { ProfileData };

export function generateProfileImage(data: ProfileData): Promise<Buffer> {
  return renderCard(createElement(ProfileCard, { data }), {
    width: PROFILE_CARD_WIDTH,
    height: PROFILE_CARD_HEIGHT,
  });
}
