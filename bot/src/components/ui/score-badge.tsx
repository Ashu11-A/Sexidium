import { discordColors, getScoreColor } from "../../lib/discord-theme.js";

interface ScoreBadgeProps {
  value: number;
}

export function ScoreBadge({ value }: ScoreBadgeProps) {
  const color = getScoreColor(value);
  return (
    <div
      tw="flex items-center justify-center rounded-lg"
      style={{
        backgroundColor: discordColors.backgroundTertiary,
        border: `1px solid ${color}40`,
        padding: "8px 18px",
        minWidth: 78,
      }}
    >
      <span tw="text-lg" style={{ color, fontWeight: 700 }}>
        {value}
      </span>
    </div>
  );
}
