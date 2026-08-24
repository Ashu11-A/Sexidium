import { createElement } from "react";
import { renderCard } from "./engine.js";
import { EventCard, EVENT_CARD_WIDTH, EVENT_CARD_HEIGHT } from "../components/event-card.js";
import type { EventData } from "../../types/event.js";

export type { EventData };

export function generateEventImage(data: EventData): Promise<Buffer> {
  return renderCard(createElement(EventCard, { data }), {
    width: EVENT_CARD_WIDTH,
    height: EVENT_CARD_HEIGHT,
  });
}
