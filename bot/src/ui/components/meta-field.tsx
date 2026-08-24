import { discordColors } from "../../lib/discord-theme.js";
import { SectionLabel } from "./section-label.js";

interface MetaFieldProps {
  label: string;
  value: string;
}

export function MetaField({ label, value }: MetaFieldProps) {
  return (
    <div tw="flex flex-col" style={{ flex: 1 }}>
      <SectionLabel>{label}</SectionLabel>
      <span tw="text-sm" style={{ color: discordColors.textNormal, fontWeight: 500 }}>
        {value}
      </span>
    </div>
  );
}
