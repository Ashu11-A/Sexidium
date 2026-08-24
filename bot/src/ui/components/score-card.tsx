import { discordColors, discordFontFamily, getScoreColor } from "../../lib/discord-theme.js";
import { hexToRgba } from "../../lib/color.js";
import type { ScoreData } from "../../types/score.js";
import { SectionLabel } from "./section-label.js";
import { MetaField } from "./meta-field.js";
import { RolePill } from "./role-pill.js";

export const SCORE_CARD_WIDTH = 420;

const BANNER_HEIGHT = 120;
const AVATAR_SIZE = 88;
const AVATAR_RING = 6;
const STATUS_SIZE = 26;
const MAX_VISIBLE_ROLES = 8;

interface ScoreCardProps {
  data: ScoreData;
}

export function ScoreCard({ data }: ScoreCardProps) {
  const scoreColor = getScoreColor(data.score);
  const bannerGradient = `linear-gradient(135deg, ${hexToRgba(data.accentColor, 0.95)}, ${hexToRgba(data.accentColor, 0.45)})`;
  const avatarOffsetTop = BANNER_HEIGHT - AVATAR_SIZE / 2;

  const visibleRoles = data.roles.slice(0, MAX_VISIBLE_ROLES);
  const hiddenRoleCount = data.roles.length - visibleRoles.length;

  return (
    <div tw="flex" style={{ width: SCORE_CARD_WIDTH, padding: 1, fontFamily: discordFontFamily }}>
      <div
        tw="flex flex-col w-full rounded-2xl"
        style={{
          position: "relative",
          backgroundColor: discordColors.backgroundSecondary,
          border: `1px solid ${discordColors.backgroundTertiary}`,
          overflow: "hidden",
        }}
      >
        {/* Banner */}
        <div tw="flex w-full" style={{ height: BANNER_HEIGHT, overflow: "hidden" }}>
          {data.bannerUrl ? (
            <img
              src={data.bannerUrl}
              width={SCORE_CARD_WIDTH}
              height={BANNER_HEIGHT}
              style={{ width: SCORE_CARD_WIDTH, height: BANNER_HEIGHT, objectFit: "cover" }}
            />
          ) : (
            <div tw="flex w-full h-full" style={{ backgroundImage: bannerGradient }} />
          )}
        </div>

        {/* Avatar sobreposto ao banner */}
        <div
          tw="flex"
          style={{
            position: "absolute",
            top: avatarOffsetTop,
            left: 20,
            width: AVATAR_SIZE + AVATAR_RING * 2,
            height: AVATAR_SIZE + AVATAR_RING * 2,
          }}
        >
          <div
            tw="flex items-center justify-center rounded-full"
            style={{
              width: AVATAR_SIZE + AVATAR_RING * 2,
              height: AVATAR_SIZE + AVATAR_RING * 2,
              backgroundColor: discordColors.backgroundSecondary,
            }}
          >
            <div
              tw="flex rounded-full"
              style={{ width: AVATAR_SIZE, height: AVATAR_SIZE, overflow: "hidden" }}
            >
              <img
                src={data.avatarUrl}
                width={AVATAR_SIZE}
                height={AVATAR_SIZE}
                style={{ width: AVATAR_SIZE, height: AVATAR_SIZE, borderRadius: AVATAR_SIZE / 2, objectFit: "cover" }}
              />
            </div>
          </div>
          <div
            tw="rounded-full"
            style={{
              position: "absolute",
              right: AVATAR_RING - 2,
              bottom: AVATAR_RING - 2,
              width: STATUS_SIZE,
              height: STATUS_SIZE,
              backgroundColor: discordColors.onlineStatus,
              border: `5px solid ${discordColors.backgroundSecondary}`,
            }}
          />
        </div>

        {/* Corpo do card */}
        <div tw="flex flex-col" style={{ padding: `${AVATAR_SIZE / 2 + 22}px 20px 22px` }}>
          <span
            tw="text-2xl"
            style={{ color: discordColors.textNormal, fontWeight: 700, lineHeight: 1.2 }}
          >
            {data.username}
          </span>
          <span tw="text-sm" style={{ color: discordColors.textMuted, fontWeight: 500, marginTop: 2 }}>
            @{data.handle}
          </span>

          <div
            tw="w-full"
            style={{ height: 1, backgroundColor: discordColors.backgroundTertiary, margin: "16px 0" }}
          />

          {/* Score */}
          <div tw="flex flex-col">
            <SectionLabel>Score</SectionLabel>
            <span tw="text-4xl" style={{ color: scoreColor, fontWeight: 700, lineHeight: 1 }}>
              {data.score}
            </span>
          </div>

          {/* Datas */}
          <div tw="flex" style={{ marginTop: 18 }}>
            <MetaField label="Conta criada" value={data.accountCreatedAt} />
            <MetaField label="Entrou no servidor" value={data.joinedServerAt ?? "—"} />
          </div>

          {/* Funções */}
          {data.roles.length > 0 && (
            <div tw="flex flex-col" style={{ marginTop: 18 }}>
              <SectionLabel>Funções</SectionLabel>
              <div tw="flex" style={{ flexWrap: "wrap" }}>
                {visibleRoles.map((role) => (
                  <RolePill key={role.name} role={role} />
                ))}
                {hiddenRoleCount > 0 && (
                  <div
                    tw="flex items-center rounded-md"
                    style={{
                      backgroundColor: discordColors.backgroundSecondary,
                      border: `1px solid ${discordColors.backgroundTertiary}`,
                      padding: "4px 10px",
                      marginBottom: 6,
                    }}
                  >
                    <span tw="text-sm" style={{ color: discordColors.textMuted, fontWeight: 600 }}>
                      +{hiddenRoleCount}
                    </span>
                  </div>
                )}
              </div>
            </div>
          )}

          {/* Identificador */}
          <div tw="flex flex-col" style={{ marginTop: 18 }}>
            <SectionLabel>ID do usuário</SectionLabel>
            <span tw="text-sm" style={{ color: discordColors.textMuted, fontWeight: 500 }}>
              {data.userId}
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
