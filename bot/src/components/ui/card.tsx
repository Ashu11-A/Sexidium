import type { ReactNode } from "react";
import { discordColors, discordFontFamily } from "../../lib/discord-theme.js";

interface CardProps {
  children: ReactNode;
  width: number;
  height: number;
}

export function Card({ children, width, height }: CardProps) {
  return (
    <div
      tw="flex w-full h-full"
      style={{
        width,
        height,
        backgroundColor: discordColors.backgroundTertiary,
        fontFamily: discordFontFamily,
        padding: 40,
      }}
    >
      <div
        tw="flex flex-col w-full h-full rounded-2xl"
        style={{
          backgroundColor: discordColors.backgroundSecondary,
          border: `1px solid ${discordColors.backgroundTertiary}`,
          padding: "32px 36px",
        }}
      >
        {children}
      </div>
    </div>
  );
}

interface CardHeaderProps {
  title: string;
  subtitle?: string;
}

export function CardHeader({ title, subtitle }: CardHeaderProps) {
  return (
    <div tw="flex flex-col" style={{ marginBottom: 28 }}>
      <div tw="flex items-center">
        <div
          tw="rounded-full"
          style={{
            width: 6,
            height: 30,
            backgroundColor: discordColors.brandAccent,
            marginRight: 14,
          }}
        />
        <span tw="text-3xl" style={{ color: discordColors.textNormal, fontWeight: 700 }}>
          {title}
        </span>
      </div>
      {subtitle && (
        <span
          tw="text-sm"
          style={{ color: discordColors.textMuted, fontWeight: 500, marginTop: 8, marginLeft: 20 }}
        >
          {subtitle}
        </span>
      )}
    </div>
  );
}
