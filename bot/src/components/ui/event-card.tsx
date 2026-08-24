import { discordFontFamily } from "../../lib/discord-theme.js";
import { hexToRgba } from "../../lib/color.js";
import type { EventData } from "../../types/event.js";

export const EVENT_CARD_WIDTH = 1024;
export const EVENT_CARD_HEIGHT = 512;

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div tw="flex flex-col" style={{ marginRight: 52 }}>
      <span tw="text-sm" style={{ color: hexToRgba("#ffffff", 0.7), fontWeight: 700, letterSpacing: 1 }}>
        {label}
      </span>
      <span tw="text-2xl" style={{ color: "#ffffff", fontWeight: 700, marginTop: 4 }}>
        {value}
      </span>
    </div>
  );
}

export function EventCard({ data }: { data: EventData }) {
  const background = `linear-gradient(135deg, ${hexToRgba(data.accent, 0.95)}, #1e1f22)`;
  return (
    <div
      tw="flex flex-col w-full h-full"
      style={{
        width: EVENT_CARD_WIDTH,
        height: EVENT_CARD_HEIGHT,
        fontFamily: discordFontFamily,
        backgroundImage: background,
        padding: 56,
      }}
    >
      <div tw="flex items-center">
        <div tw="rounded-full" style={{ width: 10, height: 38, backgroundColor: "#ffffff", marginRight: 16 }} />
        <span
          tw="text-2xl"
          style={{ color: hexToRgba("#ffffff", 0.85), fontWeight: 700, letterSpacing: 2, textTransform: "uppercase" }}
        >
          Sexidium Event
        </span>
      </div>

      <span tw="text-7xl" style={{ color: "#ffffff", fontWeight: 700, marginTop: 26, lineHeight: 1.05 }}>
        {data.title}
      </span>
      <span tw="text-3xl" style={{ color: hexToRgba("#ffffff", 0.92), fontWeight: 600, marginTop: 14 }}>
        {data.gameLabel}
      </span>
      <span tw="text-xl" style={{ color: hexToRgba("#ffffff", 0.75), fontWeight: 500, marginTop: 10 }}>
        {data.tagline}
      </span>

      <div tw="flex" style={{ marginTop: "auto" }}>
        <Field label="WHEN" value={data.dateLabel} />
        <Field label="DURATION" value={data.durationLabel} />
        <Field label="SERVER" value={data.serverName} />
      </div>
    </div>
  );
}
