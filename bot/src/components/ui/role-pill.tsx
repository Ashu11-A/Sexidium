import { discordColors } from "../../lib/discord-theme.js";
import { hexToRgba } from "../../lib/color.js";
import type { UserRole } from "../../types/score.js";

// Cores padrão (sem cor definida) do Discord usam o tom neutro de texto
function resolveRoleColor(color: string): string {
  return color === "#000000" ? discordColors.textMuted : color;
}

interface RolePillProps {
  role: UserRole;
}

export function RolePill({ role }: RolePillProps) {
  const color = resolveRoleColor(role.color);
  const borderColor = color === discordColors.textMuted ? "#949ba4" : color;
  return (
    <div
      tw="flex items-center rounded-md"
      style={{
        backgroundColor: discordColors.backgroundSecondary,
        border: `1px solid ${hexToRgba(borderColor, 0.35)}`,
        padding: "4px 10px",
        marginRight: 6,
        marginBottom: 6,
      }}
    >
      <div
        tw="rounded-full"
        style={{ width: 12, height: 12, backgroundColor: color, marginRight: 7, flexShrink: 0 }}
      />
      <span tw="text-sm" style={{ color: discordColors.textNormal, fontWeight: 500 }}>
        {role.name}
      </span>
    </div>
  );
}
