import { createElement } from "react";
import { renderCard } from "./engine.js";
import {
  ServerInfoCard,
  SERVER_INFO_CARD_WIDTH,
  SERVER_INFO_CARD_HEIGHT,
} from "../components/server-info-card.js";
import type { ServerInfoData } from "../../types/serverinfo.js";

export type { ServerInfoData };

export function generateServerInfoImage(data: ServerInfoData): Promise<Buffer> {
  return renderCard(createElement(ServerInfoCard, { data }), {
    width: SERVER_INFO_CARD_WIDTH,
    height: SERVER_INFO_CARD_HEIGHT,
  });
}
