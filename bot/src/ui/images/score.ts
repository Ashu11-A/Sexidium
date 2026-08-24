import { createElement } from "react";
import { renderCard } from "./engine.js";
import { ScoreCard, SCORE_CARD_WIDTH } from "../components/score-card.js";
import type { ScoreData, UserRole } from "../../types/score.js";

export type { ScoreData, UserRole };

export function generateScoreImage(data: ScoreData): Promise<Buffer> {
  // Width-only render: the score card auto-heights to its content.
  return renderCard(createElement(ScoreCard, { data }), { width: SCORE_CARD_WIDTH });
}
