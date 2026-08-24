import { createElement } from "react";
import { renderCard } from "./engine.js";
import { RankCard, RANK_CARD_WIDTH, RANK_CARD_HEIGHT } from "../components/rank-card.js";
import type { RankEntry } from "../../types/rank.js";

export type { RankEntry };

export function generateRankImage(entries: RankEntry[]): Promise<Buffer> {
  return renderCard(createElement(RankCard, { entries }), {
    width: RANK_CARD_WIDTH,
    height: RANK_CARD_HEIGHT,
  });
}
