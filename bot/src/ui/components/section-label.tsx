import { discordColors } from "../../lib/discord-theme.js";

interface SectionLabelProps {
  children: string;
}

export function SectionLabel({ children }: SectionLabelProps) {
  return (
    <span
      tw="text-xs"
      style={{
        color: discordColors.textMuted,
        fontWeight: 700,
        letterSpacing: 0.6,
        textTransform: "uppercase",
        marginBottom: 6,
      }}
    >
      {children}
    </span>
  );
}
