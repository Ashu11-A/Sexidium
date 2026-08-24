import { discordColors, discordFontFamily } from "../../lib/discord-theme.js";
import { hexToRgba } from "../../lib/color.js";
import type { ServerInfoData } from "../../types/serverinfo.js";
import { Card, CardHeader } from "./card.js";
import { SectionLabel } from "./section-label.js";

export const SERVER_INFO_CARD_WIDTH = 720;
export const SERVER_INFO_CARD_HEIGHT = 420;

const ACCENT = discordColors.onlineStatus;

function Field({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div tw="flex flex-col" style={{ flex: 1 }}>
      <SectionLabel>{label}</SectionLabel>
      <span tw="text-2xl" style={{ color: color ?? discordColors.textNormal, fontWeight: 700 }}>
        {value}
      </span>
    </div>
  );
}

export function ServerInfoCard({ data }: { data: ServerInfoData }) {
  return (
    <Card width={SERVER_INFO_CARD_WIDTH} height={SERVER_INFO_CARD_HEIGHT}>
      <CardHeader title={data.serverName} subtitle={data.motd} />

      {/* Online count hero */}
      <div
        tw="flex items-center rounded-2xl"
        style={{
          backgroundColor: hexToRgba(ACCENT, 0.12),
          border: `1px solid ${hexToRgba(ACCENT, 0.45)}`,
          padding: "20px 26px",
          marginBottom: 26,
        }}
      >
        <div tw="rounded-full" style={{ width: 16, height: 16, backgroundColor: ACCENT, marginRight: 16 }} />
        <span tw="text-5xl" style={{ color: ACCENT, fontWeight: 700 }}>
          {String(data.online)}
        </span>
        <span tw="text-2xl" style={{ color: discordColors.textMuted, fontWeight: 600, marginLeft: 10 }}>
          / {String(data.max)} online
        </span>
      </div>

      <div tw="flex" style={{ marginBottom: 22 }}>
        <Field label="Address" value={data.address} color={discordColors.brandAccent} />
        <Field label="Port" value={String(data.port)} />
      </div>

      <div tw="flex">
        <Field label="Version" value={data.version} />
        <Field label="TPS" value={data.tps === null ? "—" : data.tps.toFixed(1)} />
      </div>
    </Card>
  );
}
