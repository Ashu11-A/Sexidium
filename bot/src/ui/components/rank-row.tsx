import type { CSSProperties } from "react";
import { discordColors } from "../../lib/discord-theme.js";
import { hexToRgba } from "../../lib/color.js";
import { SkinAvatar } from "./skin-avatar.js";

const POSITION_COLORS: Record<number, string> = {
  0: "#f0b232",
  1: "#b5bac1",
  2: "#cd7f32",
};

interface RankRowProps {
  position: number;
  username: string;
  score: number;
  level: number;
  rankClass: string;
  rankColor: string;
  names: string[];
  avatarUrl: string;
  isLast: boolean;
}

export function RankRow({
  position,
  username,
  score,
  level,
  rankClass,
  rankColor,
  names,
  avatarUrl,
  isLast,
}: RankRowProps) {
  const positionColor = POSITION_COLORS[position] ?? discordColors.textMuted;
  const isTopThree = position < 3;
  const rowStyle: CSSProperties = {
    padding: "12px 18px",
    marginBottom: isLast ? 0 : 8,
    backgroundColor: isTopThree ? discordColors.backgroundPrimary : "transparent",
    borderRadius: 10,
  };
  const alts = names.length > 1 ? names.slice(1).join(", ") : "";

  return (
    <div tw="flex items-center" style={rowStyle}>
      <span
        tw="text-base text-center"
        style={{ color: positionColor, fontWeight: 700, width: 40, flexShrink: 0 }}
      >
        {position + 1}
      </span>

      <div tw="flex" style={{ marginLeft: 12, marginRight: 16 }}>
        <SkinAvatar src={avatarUrl} size={48} radius={10} />
      </div>

      <div tw="flex flex-col" style={{ flex: 1, overflow: "hidden" }}>
        <div tw="flex items-center">
          <span
            tw="text-lg"
            style={{
              color: isTopThree ? discordColors.textNormal : discordColors.textMuted,
              fontWeight: isTopThree ? 600 : 500,
              marginRight: 10,
            }}
          >
            {username}
          </span>
          <div
            tw="flex items-center rounded-md"
            style={{
              backgroundColor: hexToRgba(rankColor, 0.18),
              border: `1px solid ${hexToRgba(rankColor, 0.5)}`,
              padding: "2px 9px",
            }}
          >
            <span tw="text-sm" style={{ color: rankColor, fontWeight: 700 }}>
              {rankClass}
            </span>
          </div>
        </div>
        {alts ? (
          <span tw="text-xs" style={{ color: discordColors.textMuted, marginTop: 2 }}>
            {alts}
          </span>
        ) : null}
      </div>

      <div tw="flex flex-col items-end" style={{ marginLeft: 12 }}>
        <span tw="text-xl" style={{ color: rankColor, fontWeight: 700 }}>
          {score}
        </span>
        <span tw="text-xs" style={{ color: discordColors.textMuted, fontWeight: 600 }}>
          lvl {level}
        </span>
      </div>
    </div>
  );
}
