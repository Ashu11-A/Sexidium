import { discordColors } from "../../lib/discord-theme.js";

interface SkinAvatarProps {
  /** A `data:` URI (a rendered Minecraft head) or any image URL. */
  src: string;
  size: number;
  /** Corner radius — a Minecraft head reads as a rounded square, not a circle. */
  radius?: number;
}

/** Square avatar for Minecraft skin heads (distinct from the round Discord {@link Avatar}). */
export function SkinAvatar({ src, size, radius = 12 }: SkinAvatarProps) {
  return (
    <div
      tw="flex"
      style={{
        width: size,
        height: size,
        overflow: "hidden",
        flexShrink: 0,
        borderRadius: radius,
        backgroundColor: discordColors.backgroundTertiary,
      }}
    >
      <img
        src={src}
        width={size}
        height={size}
        style={{ width: size, height: size, borderRadius: radius, objectFit: "cover" }}
      />
    </div>
  );
}
