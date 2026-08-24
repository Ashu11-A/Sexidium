import { discordColors, discordFontFamily } from "../../lib/discord-theme.js";
import { hexToRgba } from "../../lib/color.js";
import type { ProfileData } from "../../types/profile.js";
import { SectionLabel } from "./section-label.js";

export const PROFILE_CARD_WIDTH = 540;
export const PROFILE_CARD_HEIGHT = 420;

const AVATAR_SIZE = 96;

interface ProfileCardProps {
  data: ProfileData;
}

function Stat({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div tw="flex flex-col items-center" style={{ flex: 1 }}>
      <span tw="text-2xl" style={{ color, fontWeight: 700, lineHeight: 1 }}>
        {value}
      </span>
      <span tw="text-xs" style={{ color: discordColors.textMuted, fontWeight: 600, marginTop: 6 }}>
        {label}
      </span>
    </div>
  );
}

export function ProfileCard({ data }: ProfileCardProps) {
  const accent = data.rankColor;
  const headerGradient = `linear-gradient(135deg, ${hexToRgba(accent, 0.85)}, ${hexToRgba(accent, 0.25)})`;
  const altNames = data.names.length > 1 ? data.names.join(", ") : data.names[0] ?? "";

  return (
    <div tw="flex" style={{ width: PROFILE_CARD_WIDTH, height: PROFILE_CARD_HEIGHT, fontFamily: discordFontFamily, padding: 1 }}>
      <div
        tw="flex flex-col w-full h-full rounded-2xl"
        style={{
          backgroundColor: discordColors.backgroundSecondary,
          border: `1px solid ${discordColors.backgroundTertiary}`,
          overflow: "hidden",
        }}
      >
        {/* Header band with avatar + class tag */}
        <div tw="flex items-center" style={{ backgroundImage: headerGradient, padding: "22px 24px" }}>
          <div
            tw="flex rounded-full"
            style={{ width: AVATAR_SIZE, height: AVATAR_SIZE, overflow: "hidden", border: `4px solid ${discordColors.backgroundSecondary}` }}
          >
            <img
              src={data.avatarUrl}
              width={AVATAR_SIZE}
              height={AVATAR_SIZE}
              style={{ width: AVATAR_SIZE, height: AVATAR_SIZE, borderRadius: AVATAR_SIZE / 2, objectFit: "cover" }}
            />
          </div>
          <div tw="flex flex-col" style={{ marginLeft: 18 }}>
            <span tw="text-3xl" style={{ color: "#ffffff", fontWeight: 700, lineHeight: 1.1 }}>
              {data.username}
            </span>
            <div
              tw="flex items-center rounded-md"
              style={{ backgroundColor: hexToRgba("#000000", 0.35), padding: "4px 12px", marginTop: 8 }}
            >
              <span tw="text-lg" style={{ color: "#ffffff", fontWeight: 700 }}>
                {data.rankClass}
              </span>
              <span tw="text-sm" style={{ color: hexToRgba("#ffffff", 0.85), fontWeight: 600, marginLeft: 8 }}>
                level {data.level}
              </span>
            </div>
          </div>
        </div>

        {/* Body */}
        <div tw="flex flex-col" style={{ padding: "22px 24px" }}>
          <div tw="flex flex-col">
            <SectionLabel>Points</SectionLabel>
            <span tw="text-5xl" style={{ color: accent, fontWeight: 700, lineHeight: 1 }}>
              {data.points}
            </span>
          </div>

          <div tw="flex" style={{ marginTop: 22 }}>
            <Stat label="WINS" value={String(data.wins)} color={discordColors.textNormal} />
            <Stat label="KILLS" value={String(data.kills)} color={discordColors.textNormal} />
            <Stat label="GAMES" value={String(data.games)} color={discordColors.textNormal} />
          </div>

          <div tw="flex flex-col" style={{ marginTop: 22 }}>
            <SectionLabel>Linked accounts</SectionLabel>
            <span tw="text-sm" style={{ color: discordColors.textMuted, fontWeight: 500 }}>
              {altNames}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
