import { discordColors } from "../../lib/discord-theme.js";

interface AvatarProps {
  src: string;
  size: number;
}

export function Avatar({ src, size }: AvatarProps) {
  return (
    <div
      tw="flex rounded-full"
      style={{
        width: size,
        height: size,
        overflow: "hidden",
        flexShrink: 0,
        backgroundColor: discordColors.backgroundTertiary,
      }}
    >
      <img
        src={src}
        width={size}
        height={size}
        style={{ width: size, height: size, borderRadius: size / 2, objectFit: "cover" }}
      />
    </div>
  );
}
